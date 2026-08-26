package com.ninja6.spiralgenesis;

import com.ninja6.spiralgenesis.commands.SpiralCommand;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.hook.AuthMeHook;
import com.ninja6.spiralgenesis.hook.FloodgateHook;
import com.ninja6.spiralgenesis.config.AllocationTrigger;
import com.ninja6.spiralgenesis.listeners.AuthMeHookListener;
import com.ninja6.spiralgenesis.listeners.PlayerActionGateListener;
import com.ninja6.spiralgenesis.listeners.PlayerSpawnListener;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.protection.NoOpProtectionProvider;
import com.ninja6.spiralgenesis.protection.ProtectionProvider;
import com.ninja6.spiralgenesis.protection.ProtectionProviders;
import com.ninja6.spiralgenesis.protection.SpawnProtectionBackfill;
import com.ninja6.spiralgenesis.protection.SpawnProtector;
import com.ninja6.spiralgenesis.storage.DataStorage;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.logging.Level;

/**
 * Main plugin lifecycle entrypoint for SpiralGenesis.
 */
public class SpiralGenesisPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private DataStorage dataStorage;
    private SpawnManager spawnManager;
    private FloodgateHook floodgateHook;
    private AuthMeHook authMeHook;
    private PlayerActionGateListener actionGate;

    /**
     * The protection provider in force. Selected at startup and again on reload, and never
     * null: a server with the feature off, or with the configured plugin missing, holds the
     * no-op rather than a null to be checked at every call site.
     *
     * <p>Reached through {@link #getSpawnProtector()} rather than directly at every call
     * site: the protector is what holds the size, the logging decisions and the rule that a
     * failed claim never costs a player their plot.
     */
    private ProtectionProvider protectionProvider = NoOpProtectionProvider.INSTANCE;

    /**
     * The one object every protection call in the plugin goes through.
     *
     * <p>Built once and never rebuilt, because it reads the provider and the configured size
     * through suppliers rather than holding them - so {@code /sgen reload} swapping either
     * one is picked up without this reference having to be replaced.
     */
    private volatile SpawnProtector spawnProtector;

    /**
     * The {@code /sgen protect} backfill currently running, or {@code null}.
     *
     * <p>One at a time. Two concurrent passes over the same stored spawns would double the
     * per-tick cost the batching exists to bound, and every claim the second one attempted
     * would already have been made by the first.
     */
    private final AtomicReference<SpawnProtectionBackfill> backfill = new AtomicReference<>();

    /** Login plugins found at startup. Reported, and used to word the gate's timeout warning. */
    private List<String> detectedLoginPlugins = List.of();

    /**
     * Login plugins this project is aware of, for reporting only.
     *
     * <p>Never branched on. The gate works by observing that <em>something</em> is holding
     * the player, which is what lets it cover login plugins not on this list, including
     * ones that do not exist yet. The list exists so an administrator can read which mode
     * was chosen out of the startup log instead of inferring it from player behaviour.
     */
    private static final List<String> KNOWN_LOGIN_PLUGINS = List.of(
            "AuthMe", "nLogin", "LibreLogin", "OpeNLogin", "UserLogin", "JPremium");

    /** Players with an allocation currently in flight; guards against double assignment. */
    private final Set<UUID> allocating = ConcurrentHashMap.newKeySet();

    /**
     * Players with an in-cell repair in flight.
     *
     * <p>A player killed by their own plot respawns into it again seconds later, so without
     * this a single griefed spawn would start a fresh search per death, each one rewriting
     * storage under the others.
     */
    private final Set<UUID> repairing = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        this.dataStorage = new YamlDataStorage(this);
        this.dataStorage.load();

        this.floodgateHook = new FloodgateHook();
        this.authMeHook = new AuthMeHook();
        this.protectionProvider = ProtectionProviders.create(this, pluginConfig);
        // getProtectionProvider() rather than the field, so a test that overrides the getter
        // to inject a provider is reaching every call site rather than none of them.
        this.spawnProtector = new SpawnProtector(getLogger(), this::getProtectionProvider,
                () -> pluginConfig.getProtectionSize());

        initSpawnManager();

        this.detectedLoginPlugins = KNOWN_LOGIN_PLUGINS.stream()
                .filter(name -> getServer().getPluginManager().getPlugin(name) != null)
                .toList();

        // Register event listeners
        this.actionGate = new PlayerActionGateListener(this, this::handlePlayerFirstJoin,
                this::reassertSpawnTeleport,
                () -> pluginConfig.getActionTimeoutSeconds(),
                () -> String.join(", ", detectedLoginPlugins));
        getServer().getPluginManager().registerEvents(actionGate, this);
        getServer().getPluginManager().registerEvents(new PlayerSpawnListener(this, actionGate), this);

        registerAuthMeAdapter();
        reportAllocationTrigger();

        if (floodgateHook.isInstalled()) {
            getLogger().info("Detected Geyser/Floodgate! Enabling Bedrock auto-authentication hooks.");
        }

        // Register administrative command suite
        PluginCommand sgen = getCommand("sgen");
        if (sgen != null) {
            SpiralCommand cmd = new SpiralCommand(this);
            sgen.setExecutor(cmd);
            sgen.setTabCompleter(cmd);
        }

        getLogger().info("SpiralGenesis v" + getDescription().getVersion() + " successfully enabled!");
    }

    @Override
    public void onDisable() {
        // Released before anything else, because the server cancels the repeating task that
        // would otherwise have cleared it. A reload that left this set would come back up
        // refusing /sgen protect with "already running" and no job anywhere to finish.
        backfill.set(null);
        if (dataStorage != null) {
            dataStorage.shutdown();
        }
        getLogger().info("SpiralGenesis disabled.");
    }

    /**
     * Registers the AuthMe fast path, if AuthMe is present.
     *
     * <p>Optional in the strong sense: {@link AuthMeHookListener} names AuthMe's event
     * classes directly, so registering it resolves them, and a version whose events have
     * moved would throw here. Allocation does not depend on this succeeding - the action
     * gate covers AuthMe like any other login plugin - so a failure is reported and
     * swallowed rather than taking the whole plugin's enable down with it.
     */
    private void registerAuthMeAdapter() {
        if (!authMeHook.isInstalled()) {
            return;
        }
        try {
            getServer().getPluginManager().registerEvents(new AuthMeHookListener(this), this);
            getLogger().info("Detected AuthMe-Reloaded; allocating on its login event.");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "AuthMe is installed but its API could not be bound. "
                    + "Falling back to the generic action gate.", t);
        }
    }

    /** States which gating mode is in effect, and why, while the log is still readable. */
    private void reportAllocationTrigger() {
        List<String> found = detectedLoginPlugins;
        String detected = found.isEmpty() ? "none detected" : String.join(", ", found);

        if (pluginConfig.getAllocationTrigger() == AllocationTrigger.ON_JOIN) {
            getLogger().info("Allocation trigger: ON_JOIN (login plugins: " + detected + ").");
            if (!found.isEmpty()) {
                getLogger().warning("ON_JOIN allocates before " + detected + " has authenticated "
                        + "anyone. Use FIRST_ACTION unless this server authenticates elsewhere.");
            }
            return;
        }
        getLogger().info("Allocation trigger: FIRST_ACTION (login plugins: " + detected + ").");
    }

    public void reload() {
        reloadConfig();
        loadConfiguration();
        if (dataStorage != null) {
            // Flush first: load() replaces in-memory state from disk, so any pending
            // change that has not been written yet would otherwise be discarded.
            dataStorage.save();
            dataStorage.load();
        }
        // Re-selected rather than kept: protection.enabled, the provider and the claim size
        // are all reloadable, and the minimum-size check the provider makes at construction
        // is only correct for the size it was constructed with.
        this.protectionProvider = ProtectionProviders.create(this, pluginConfig);
        initSpawnManager();
    }

    private void loadConfiguration() {
        this.pluginConfig = new PluginConfig(getConfig());
        // Every load, including a reload: a value the plugin quietly corrected is one the
        // owner reads back out of the file and believes, so it has to be said each time.
        for (String warning : pluginConfig.getWarnings()) {
            getLogger().warning(warning);
        }
    }

    /**
     * Binds the spawn manager to the configured world, or to the first loaded one.
     *
     * <p>Package-private as a test seam: because of that fallback the manager is only ever
     * absent on a server with no worlds at all, so the branch in
     * {@link #handlePlayerFirstJoin} that copes with it cannot otherwise be reached from a
     * test that has a player to allocate.
     */
    void initSpawnManager() {
        World world = Bukkit.getWorld(pluginConfig.getWorldName());
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        if (world != null) {
            this.spawnManager = new SpawnManager(this, world, pluginConfig);
        } else {
            getLogger().warning("Could not find target world '" + pluginConfig.getWorldName() + "' for SpawnManager.");
        }
    }

    /**
     * Handles first join allocation for incoming players asynchronously.
     *
     * <p>Allocation is idempotent per player: a scan takes several ticks to resolve, and in
     * that window storage still reports the player as unassigned. AuthMe fires both
     * {@code RegisterEvent} and {@code LoginEvent} for a fresh registration, so without the
     * in-flight guard below a single player would be allocated two separate plots.
     */
    public void handlePlayerFirstJoin(Player player, String clientType) {
        UUID uuid = player.getUniqueId();

        if (dataStorage.hasSpawn(uuid)) {
            // Nothing to allocate, so nothing left to wait for either.
            forgetFromGate(uuid);
            return;
        }

        if (spawnManager == null) {
            initSpawnManager();
        }

        if (spawnManager == null) {
            // Effectively unreachable: initSpawnManager falls back to the first loaded world,
            // so this needs Bukkit.getWorlds() to be empty, which cannot be true while a
            // player is connected. Left as a guard rather than an assertion because the
            // fallback is a detail of that method, not a contract.
            //
            // Returns before takeAllocation, so the player keeps their place in the gate.
            getLogger().severe("Cannot allocate spawn: SpawnManager world is unavailable!");
            return;
        }

        if (!takeAllocation(uuid)) {
            return; // An allocation for this player is already in flight.
        }

        getLogger().info("Allocating new spiral plot for " + clientType + " player " + player.getName() + " (" + uuid + ")...");

        // Completed once the outcome is settled either way, and what the in-flight guard is
        // released on. Chaining that release onto the allocation future instead cleared it
        // as soon as the entity task was *scheduled*, leaving at least a tick in which
        // storage still reported the player unassigned and nothing guarded them - wide
        // enough for a second caller to reserve a second index and burn it.
        CompletableFuture<Void> applied = new CompletableFuture<>();
        applied.whenComplete((ignored, ex) -> allocating.remove(uuid));

        // The index is claimed atomically inside the scan, so concurrent joins never
        // resolve to the same grid cell.
        //
        // Wrapped because allocateNextSafeSpawn does real work on this thread before it
        // returns a future - it claims an index and requests the first chunk - so a throw
        // there escapes before exceptionally() below is ever attached. That would leave the
        // guard held for the lifetime of the process, and a player permanently unallocatable.
        try {
            allocateSpawn(dataStorage::reserveNextIndex).thenAccept(res -> {
                // Player state must be touched on the thread owning that player. The entity
                // scheduler is that thread on Folia and the main thread on Paper; it also drops
                // the task automatically if the player disconnects before it runs.
                boolean scheduled = runForPlayer(player, () -> {
                    try {
                        if (!player.isOnline()) return;

                        dataStorage.setSpawn(uuid, res.location(), res.index(), res.gridU(), res.gridV(), player.getName(), clientType);

                        player.setRespawnLocation(res.location(), true);
                        player.teleportAsync(res.location()).thenAccept(success -> {
                            if (Boolean.TRUE.equals(success)) {
                                getLogger().info("Assigned & teleported " + player.getName() + " to plot #" + res.index() +
                                        " at (" + res.location().getBlockX() + ", " + res.location().getBlockY() + ", " + res.location().getBlockZ() + ")");
                                return;
                            }
                            // Recoverable rather than fatal: the plot is recorded and their
                            // respawn point already points at it. But storage now claims a
                            // location the player is not standing at, so it is marked for a
                            // retry on their next uncancelled action - a login plugin
                            // cancelling teleports for unauthenticated players is the
                            // likeliest cause, and that action is the signal it let go.
                            markUnreached(player);
                            getLogger().warning("Assigned " + player.getName() + " to plot #" + res.index()
                                    + " but the teleport did not complete; they are recorded at ("
                                    + res.location().getBlockX() + ", " + res.location().getBlockY() + ", "
                                    + res.location().getBlockZ() + ") without having been moved there. "
                                    + "Will retry on their next uncancelled action.");
                        }).exceptionally(ex -> {
                            // thenAccept above runs only on normal completion, so without this
                            // a teleport that fails outright is exactly as silent as the case
                            // the warning was added for.
                            markUnreached(player);
                            getLogger().log(Level.WARNING, "Assigned " + player.getName() + " to plot #"
                                    + res.index() + " but the teleport failed; they are recorded there "
                                    + "without having been moved. Will retry on their next "
                                    + "uncancelled action.", ex);
                            return null;
                        });

                        // Last, and deliberately so. It is after the write, because a claim
                        // around a point that is not yet the player's recorded spawn is a
                        // claim around ground they may never be sent to - and under
                        // PLAYER_CLAIM they would have paid for it. It is also after the
                        // respawn point and the teleport request, so everything the player is
                        // owed has already been asked for by the time a claim is attempted:
                        // the claim is an enhancement to allocation, and nothing about it may
                        // sit in front of the placement.
                        //
                        // Still inside this task rather than in the teleport callback above,
                        // which resolves on whichever thread finished the teleport. This task
                        // runs on the thread that owns the player - the main thread on every
                        // server where a real provider exists - which is what the provider's
                        // threading contract requires.
                        getSpawnProtector().protect(uuid, res.location(), "first allocation");
                    } finally {
                        // Inside the task, so the guard outlives the write that makes
                        // hasSpawn() true rather than being released before it.
                        applied.complete(null);
                    }
                }, () -> {
                    getLogger().fine("Player " + player.getName()
                            + " disconnected before their plot could be applied; index already reserved.");
                    applied.complete(null);
                });

                // A refused task means neither callback above ever runs, and the guard
                // would leak.
                if (!scheduled) {
                    applied.complete(null);
                }
            }).exceptionally(ex -> {
                getLogger().log(Level.SEVERE, "Error while asynchronously allocating spiral spawn for " + player.getName(), ex);
                applied.complete(null);
                return null;
            });
        } catch (Exception e) {
            // Exception, not Throwable: an OutOfMemoryError or StackOverflowError reported as
            // a per-player allocation failure would leave the server running in a state
            // nobody has assessed. Releasing the guard first keeps this player allocatable
            // if the server does survive.
            getLogger().log(Level.SEVERE, "Spawn allocation for " + player.getName()
                    + " failed before it could start.", e);
            applied.complete(null);
        } catch (Throwable t) {
            applied.complete(null);
            throw t;
        }
    }

    /**
     * Claims the exclusive right to allocate this player, and stops the gate watching them.
     *
     * <p>The two halves are one operation and are kept in one place so they cannot drift
     * apart. Surrendering the gate is only safe once ownership is held: doing it earlier
     * loses a player whose caller then bails out, since the gate's {@code release} drops
     * them before calling in and there is nothing left to retry with. Doing it later leaves
     * the gate free to release the same player again on their next action, which is what the
     * AuthMe fast path used to do to anyone allocated on {@code LoginEvent}.
     *
     * <p>Every caller that reaches allocation passes through here, so a bail-out is
     * recognisable by returning above it. Both directions are pinned by
     * {@code AllocationOwnershipTest}.
     *
     * @return false if an allocation for this player is already in flight
     */
    private boolean takeAllocation(UUID uuid) {
        if (!allocating.add(uuid)) {
            return false;
        }
        forgetFromGate(uuid);
        return true;
    }

    /**
     * Starts an allocation.
     *
     * <p>Package-private as a test seam, for the reason {@link SpawnManager#loadChunk} and
     * the region hops beside it are: MockBukkit implements neither async chunk loading nor
     * the region schedulers, and this method builds its own SpawnManager, so those seams
     * cannot be reached from outside.
     */
    CompletableFuture<SpawnManager.LocationResult> allocateSpawn(IntSupplier indexSupplier) {
        return spawnManager.allocateNextSafeSpawn(indexSupplier);
    }

    /**
     * Runs {@code action} on the thread that owns this player, or {@code retired} instead if
     * they disconnect first.
     *
     * <p>Package-private as a test seam: MockBukkit's entity scheduler throws rather than
     * scheduling. The {@link ScheduledTask} the real scheduler hands the callback is not
     * used by either of them, so it is not passed on.
     *
     * @return false if the scheduler refused the work outright, in which case neither
     *         callback will ever run
     */
    boolean runForPlayer(Player player, Runnable action, Runnable retired) {
        ScheduledTask scheduled = player.getScheduler().run(this, task -> action.run(), retired);
        return scheduled != null;
    }

    /**
     * Moves a player off a plot that is no longer safe, without giving up their cell.
     *
     * <p>Called from the respawn path, which is synchronous and therefore cannot search for
     * a replacement point itself. The search stays inside the cell the player already owns:
     * their builds are there, and advancing their spiral index would mean that making
     * somebody's spawn lethal is also how you evict them from their land.
     *
     * @param confirmFirst re-read the stored point before doing anything. Set when the
     *                     respawn handler could not judge it inline because its chunk was
     *                     not resident, which is the ordinary case for a plot nobody is
     *                     standing on - most of those turn out to be perfectly fine.
     */
    public void repairSpawn(Player player, StoredSpawn record, boolean confirmFirst) {
        SpawnManager manager = spawnManager;
        Location stored = record.toLocation();
        if (manager == null || stored == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!repairing.add(uuid)) {
            return; // A repair for this player is already running.
        }

        if (!confirmFirst) {
            startRepairSearch(player, record, stored);
            return;
        }

        // Every hop goes through SpawnManager rather than the region scheduler directly,
        // and that is not stylistic. Submitting to the region scheduler for a chunk nobody
        // has loaded queues a task on Folia that may never run: the caller has just sent
        // this player to world spawn, so the plot's region can be unowned by the time the
        // repair starts, and the whole repair silently never happened. Verified against a
        // live Folia server, where exactly that occurred. The manager awaits the chunk
        // first and only then hops onto the thread owning it, which is the ordering the
        // candidate probe has always used.
        manager.revalidate(stored).whenComplete((safe, ex) -> {
            if (ex != null) {
                repairing.remove(uuid);
                getLogger().log(Level.WARNING, "Could not re-check plot #" + record.index()
                        + " for " + player.getName() + "; leaving it as recorded.", ex);
                return;
            }
            if (Boolean.TRUE.equals(safe)) {
                repairing.remove(uuid);
                return; // Fine after all, which is the ordinary outcome.
            }
            startRepairSearch(player, record, stored);
        });
    }

    /** Announces the repair and hands off to the in-cell search. */
    private void startRepairSearch(Player player, StoredSpawn record, Location stored) {
        getLogger().warning("Plot #" + record.index() + " is no longer safe for "
                + player.getName() + " at (" + stored.getBlockX() + ", "
                + stored.getBlockY() + ", " + stored.getBlockZ()
                + "); searching that cell for a replacement point.");
        try {
            searchInCell(record.index())
                    .whenComplete((res, ex) -> applyRepair(player, record, stored, res, ex));
        } catch (Throwable t) {
            // The search does real work before it returns a future - it requests the first
            // chunk - so a throw there escapes before whenComplete is attached, and would
            // otherwise hold the guard for the life of the process.
            repairing.remove(player.getUniqueId());
            getLogger().log(Level.SEVERE, "In-cell search for plot #" + record.index()
                    + " failed before it could start.", t);
        }
    }

    /**
     * Runs the in-cell replacement search.
     *
     * <p>Package-private as a test seam, for the same reason {@link #allocateSpawn} is:
     * MockBukkit implements neither async chunk loading nor the region schedulers the real
     * search hops through, so the repair path is otherwise unreachable from a test - which
     * would leave the claim that follows a revalidation move with no coverage at the level
     * it was specified.
     */
    CompletableFuture<SpawnManager.LocationResult> searchInCell(int index) {
        return spawnManager.findSafeSpawnInCell(index);
    }

    /**
     * Applies the outcome of an in-cell repair search on the player's own thread.
     *
     * <p>Storage is rewritten only when a replacement was found. A cell where every sampled
     * candidate failed leaves the record alone deliberately: {@code max-candidates} samples
     * a dozen points out of the hundreds a cell holds, so "no candidate passed" is not
     * evidence the plot is unusable, and overwriting it would lose the assignment for good.
     */
    private void applyRepair(Player player, StoredSpawn record, Location stored,
                             SpawnManager.LocationResult res, Throwable error) {
        UUID uuid = player.getUniqueId();
        if (error != null) {
            repairing.remove(uuid);
            getLogger().log(Level.SEVERE, "In-cell search failed while repairing plot #"
                    + record.index() + " for " + player.getName() + ".", error);
            return;
        }

        ScheduledTask scheduled = player.getScheduler().run(this, task -> {
            try {
                if (!player.isOnline()) {
                    return;
                }
                if (res == null) {
                    World world = Bukkit.getWorld(record.worldName());
                    getLogger().warning("No safe point found among the "
                            + pluginConfig.getMaxCandidates() + " sampled candidates in plot #"
                            + record.index() + "; sending " + player.getName()
                            + " to world spawn. Their plot assignment is unchanged.");
                    if (world != null && !player.isDead()) {
                        player.teleportAsync(world.getSpawnLocation());
                    }
                    return;
                }

                dataStorage.setSpawn(uuid, res.location(), record.index(), record.gridU(),
                        record.gridV(), player.getName(), record.clientType());
                player.setRespawnLocation(res.location(), true);
                // A player still on the death screen is not somewhere to be teleported
                // from; updating their respawn point above is what places them, and it is
                // also the only lever that works on Folia, whose respawn never consults a
                // plugin. Anyone already back in the world is moved directly.
                if (!player.isDead()) {
                    player.teleportAsync(res.location());
                }
                getLogger().info("Repaired plot #" + record.index() + " for " + player.getName()
                        + "; moved within the same cell to (" + res.location().getBlockX() + ", "
                        + res.location().getBlockY() + ", " + res.location().getBlockZ() + ").");

                // Revalidation moved the spawn, so the claim has to move with it or the
                // player ends up protected at a point they no longer spawn at - which is the
                // failure this whole path exists to prevent, one level down. Last, like the
                // allocation path and for the same reason: the player is placed before
                // anything is claimed. The old square is left standing, as on every path that
                // moves a spawn, and unlike the commands this one has no output to say so in,
                // so the log carries it.
                getSpawnProtector().protect(uuid, res.location(), "revalidation repair");
                String stale = getSpawnProtector().handOffStaleClaim(uuid, player.getName(),
                        stored, res.location(), false, null);
                if (stale != null) {
                    getLogger().info(stale + " It was the spawn point that failed its"
                            + " re-check, so it is worth a look before it is removed.");
                }
            } finally {
                repairing.remove(uuid);
            }
        }, () -> repairing.remove(uuid));

        if (scheduled == null) {
            repairing.remove(uuid);
        }
    }

    /**
     * Re-asserts a teleport that was recorded but never carried out.
     *
     * <p>Storage claims a location the player has never been to whenever the first-join
     * teleport is refused, which a login plugin holding an unauthenticated player will do -
     * LibreLogin cancels every {@code PlayerTeleportEvent} while its limbo has them. That
     * left an inconsistency nothing ever reconciled. Retrying on the player's next
     * unsuppressed action is the same signal the allocation gate already trusts, and by
     * then whatever was refusing teleports has let go.
     *
     * <p>Paper only, in practice. Measured on folia 1.21.11 build 14: a plugin registered
     * for {@code PlayerTeleportEvent} is never called for a {@code teleportAsync}, and the
     * teleport completes, where the identical run on Paper is refused. So on Folia this
     * path is not reachable by a login plugin cancelling teleports - it remains reachable
     * through the other ways a teleport can fail, which is why the mark is set on the
     * future's failure rather than on any particular cause.
     *
     * <p><b>Nothing about spawn protection happens here, and that was checked rather than
     * assumed.</b> The question is whether this path can reach a spawn that has no claim
     * yet. It cannot, for two reasons that hold together.
     *
     * <p>A player is only ever marked for a retry from inside the teleport callbacks in
     * {@link #handlePlayerFirstJoin}, and the claim is requested at the end of the same task
     * those callbacks were armed from. That task runs on the thread owning the player, and
     * this method needs a subsequent uncancelled action from that player to fire at all - a
     * later tick, at the earliest - so the claim has certainly been attempted by then, even
     * though a completed teleport future could run its callback first. And this path never
     * moves a spawn: it re-sends the player to the location already in storage, so there is
     * no new ground for a claim to be needed around. Retrying the claim here would only ask
     * for a square that was either created a moment ago or refused for a reason that has not
     * changed since. The correct behaviour is to do nothing, which is what this does.
     *
     * <p>Session-scoped, and deliberately left that way. Two things make it defensible.
     * Nothing on disk distinguishes a player who was never moved to their plot from one who
     * was moved and walked away, so a flag in {@link StoredSpawn} would have to guess which
     * it was looking at, and guessing wrong yanks a settled player off whatever they have
     * built. And the case largely repairs itself: {@code setRespawnLocation} is applied
     * before the teleport is ever attempted, so an affected player arrives at their plot on
     * their next death whether or not this ever fires. Persisting the mark would buy the
     * difference between "on your next action" and "on your next action or death", at the
     * cost of a schema change and a heuristic that can be wrong.
     */
    private void reassertSpawnTeleport(Player player) {
        StoredSpawn record = dataStorage.getRecord(player.getUniqueId());
        Location target = record == null ? null : record.toLocation();
        if (target == null) {
            return;
        }
        player.getScheduler().run(this, task -> {
            if (!player.isOnline()) {
                return;
            }
            player.setRespawnLocation(target, true);
            player.teleportAsync(target).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    getLogger().info("Re-asserted the plot teleport for " + player.getName()
                            + " after their first uncancelled action.");
                    return;
                }
                getLogger().warning("Re-asserted plot teleport for " + player.getName()
                        + " was refused again; they remain recorded at a plot they have not "
                        + "been moved to. Run 'sgen tp' or investigate what is cancelling "
                        + "teleports for this player.");
            }).exceptionally(ex -> {
                getLogger().log(Level.WARNING, "Re-asserted plot teleport for "
                        + player.getName() + " failed.", ex);
                return null;
            });
        }, null);
    }

    /** Queues a teleport retry with the action gate, if there is one. */
    private void markUnreached(Player player) {
        if (actionGate != null) {
            actionGate.markUnreached(player);
        }
    }

    /** Drops a player from the action gate, if there is one. */
    private void forgetFromGate(UUID uuid) {
        if (actionGate != null) {
            actionGate.forget(uuid);
        }
    }

    /**
     * Allocates a held player immediately, bypassing the action gate.
     *
     * <p>The escape hatch for a login plugin whose limbo the gate cannot read. nLogin and
     * JPremium are closed source, so their limbo implementations cannot be verified the way
     * AuthMe's, OpeNLogin's and LibreLogin's were, and a login plugin that suppresses
     * actions below the Bukkit event layer would leave a player held until the timeout.
     * Every such plugin can run a console command on successful login, which makes this
     * reachable without SpiralGenesis knowing anything about it.
     *
     * <p>Idempotent, like every other path into allocation: calling it for a player who
     * already has a plot does nothing.
     */
    public void allocateNow(Player player, String clientType) {
        handlePlayerFirstJoin(player, clientType);
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public DataStorage getDataStorage() {
        return dataStorage;
    }

    public SpawnManager getSpawnManager() {
        return spawnManager;
    }

    public FloodgateHook getFloodgateHook() {
        return floodgateHook;
    }

    public AuthMeHook getAuthMeHook() {
        return authMeHook;
    }

    /**
     * The protection provider selected for this server. Never {@code null}.
     *
     * <p>Callers must invoke {@link ProtectionProvider#reserve} on the thread that owns the
     * region containing the claim - the main thread on every server where a real provider is
     * available, since none of them run on Folia. The GriefPrevention implementation logs a
     * stack trace at SEVERE and refuses the claim when it is called from anywhere else.
     */
    public ProtectionProvider getProtectionProvider() {
        return protectionProvider;
    }

    /**
     * The shared protector every protection call in this plugin goes through. Never
     * {@code null}.
     *
     * <p>Built lazily on first use as well as in {@link #onEnable()}, because
     * {@link #reload()} is reachable in a test before enable has finished and a null here
     * would turn a configuration reload into a failed allocation.
     */
    public SpawnProtector getSpawnProtector() {
        SpawnProtector protector = spawnProtector;
        if (protector == null) {
            protector = new SpawnProtector(getLogger(), this::getProtectionProvider,
                    () -> pluginConfig.getProtectionSize());
            spawnProtector = protector;
        }
        return protector;
    }

    /**
     * Starts the {@code /sgen protect} backfill, if one is not already running.
     *
     * <p>The job itself is bounded per tick; this only owns the "one at a time" guard and
     * the driving. The report is delivered on whichever thread the last batch ran on, so a
     * caller that has to answer a player is responsible for hopping back to them.
     *
     * @param report invoked once with the summary line when the run finishes
     * @return the job that was started, or {@code null} if one was already running
     */
    public SpawnProtectionBackfill startProtectionBackfill(Consumer<String> report) {
        // Read before the snapshot rather than after: copying every stored assignment is the
        // expensive half of starting, and a caller who is only going to be turned away
        // should not pay for it.
        if (backfill.get() != null) {
            return null;
        }
        SpawnProtectionBackfill job = new SpawnProtectionBackfill(getSpawnProtector(),
                dataStorage.getAllRecords());
        if (!backfill.compareAndSet(null, job)) {
            return null;
        }
        if (job.isFinished()) {
            // Nothing stored at all. Finishing here rather than scheduling a task that would
            // cancel itself on its first run.
            backfill.set(null);
            report.accept(job.summary());
            return job;
        }
        try {
            driveBackfill(job, () -> {
                backfill.set(null);
                getLogger().info(job.summary());
                report.accept(job.summary());
            });
        } catch (Throwable t) {
            // Scheduling can be refused outright - runAtFixedRate throws once the plugin is
            // disabling - and a throw here would otherwise leave the guard holding a job that
            // will never run, so every later /sgen protect answers "already running" for the
            // life of the process. Clearing it is what keeps the command usable.
            backfill.set(null);
            getLogger().log(Level.WARNING, "The spawn protection backfill could not be "
                    + "scheduled, so nothing was claimed.", t);
            report.accept("The spawn protection backfill could not be scheduled; see the console.");
            return null;
        }
        return job;
    }

    /** Whether a backfill is running right now. */
    public boolean isProtectionBackfillRunning() {
        return backfill.get() != null;
    }

    /**
     * Runs a backfill job one batch per tick until it says it is finished.
     *
     * <p>Package-private as a test seam, for the same reason {@link #runForPlayer} is: the
     * global region scheduler is Paper's, MockBukkit does not implement it, and the batching
     * that is actually worth testing lives in the job rather than in the scheduling.
     *
     * <p>The global region scheduler and not the async one, and not a player's: the claim
     * calls have to be on the main thread, and the owners being backfilled are mostly
     * offline and have no thread of their own. On Folia this scheduler is not the thread
     * owning any particular region - which is exactly why nothing is claimed there anyway,
     * since no supported claim plugin runs on Folia and the provider resolves to the no-op.
     */
    void driveBackfill(SpawnProtectionBackfill job, Runnable onFinish) {
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (job.runBatch()) {
                task.cancel();
                onFinish.run();
            }
        }, 1L, 1L);
    }
}
