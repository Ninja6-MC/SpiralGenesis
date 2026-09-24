package com.ninja6.spiralgenesis;

import com.ninja6.spiralgenesis.commands.SpiralCommand;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.hook.AuthMeHook;
import com.ninja6.spiralgenesis.hook.FloodgateHook;
import com.ninja6.spiralgenesis.config.AllocationTrigger;
import com.ninja6.spiralgenesis.listeners.AuthMeHookListener;
import com.ninja6.spiralgenesis.listeners.PlayerActionGateListener;
import com.ninja6.spiralgenesis.listeners.PlayerSpawnListener;
import com.ninja6.spiralgenesis.manager.CellReserver;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.protection.NoOpProtectionProvider;
import com.ninja6.spiralgenesis.protection.ProtectionProvider;
import com.ninja6.spiralgenesis.protection.ProtectionProviders;
import com.ninja6.spiralgenesis.config.ClaimOwnership;
import com.ninja6.spiralgenesis.protection.SpawnClaimRelease;
import com.ninja6.spiralgenesis.protection.SpawnProtectionBackfill;
import com.ninja6.spiralgenesis.protection.SpawnProtector;
import com.ninja6.spiralgenesis.storage.DataStorage;
import com.ninja6.spiralgenesis.storage.StorageFailure;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Main plugin lifecycle entrypoint for SpiralGenesis.
 */
public class SpiralGenesisPlugin extends JavaPlugin {

    /** The one permission node, declared in plugin.yml, that gates every admin surface. */
    public static final String ADMIN_PERMISSION = "spiralgenesis.admin";

    /**
     * The configuration in force. Volatile because a reload replaces it on the thread that
     * typed the command while region threads read it for the gate's timeout, the protection
     * size and the held-player bind retry.
     */
    private volatile PluginConfig pluginConfig;
    private DataStorage dataStorage;
    /**
     * The bound spawn manager, or {@code null} while {@code origin.world} names no loaded
     * world.
     *
     * <p>Volatile because a reload can unbind it while allocation is running: the reload
     * arrives on the thread that typed the command, and {@code initSpawnManager} is also
     * reached from {@link #handlePlayerFirstJoin}, which the action gate invokes from
     * whichever region thread saw the player act. A stale non-null read here is the whole
     * failure this refusal exists to prevent - it would keep allocating into a world the
     * configuration no longer names.
     */
    private volatile SpawnManager spawnManager;

    /**
     * Configured world name the unresolved-world error has already been reported for.
     *
     * <p>Written from the same threads, and swapped atomically rather than read and then
     * set: two region threads retrying the bind together would otherwise both see the name
     * unreported and both log it.
     */
    private final AtomicReference<String> unresolvedWorldReported = new AtomicReference<>();

    /**
     * Serialises every write of {@link #spawnManager} with the configuration read it was
     * derived from.
     *
     * <p>The reload binds on the thread that typed the command - the main thread on Paper,
     * the global region or the sender's region on Folia - while a held player's retry binds
     * on whichever region thread saw them act. Without this, a retry that read the old
     * {@code origin.world} could store its {@code null} after the reload had bound the new
     * one, and the reload would then find nothing to resume held players with. Under the
     * lock the last bind is always derived from the newest configuration: a retry either
     * finishes before the reload's bind, which overwrites it, or starts after it, and then
     * reads the configuration the reload wrote before taking the lock.
     */
    private final Object bindLock = new Object();
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

    /**
     * The {@code /sgen release-all} job currently running, or {@code null}. One at a time,
     * for the reason {@link #backfill} is.
     */
    private final AtomicReference<SpawnClaimRelease> claimRelease = new AtomicReference<>();

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

    /**
     * Players already reported as having played here before the plugin was installed, so
     * the line is logged once per player rather than on every join and action.
     */
    private final Set<UUID> preInstallReported = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        this.dataStorage = createDataStorage();
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

    /**
     * Builds the storage backend.
     *
     * <p>Package-private as a test seam: a refused write is decided inside
     * {@link DataStorage#setSpawn}, after every check a caller can make, and the only way to
     * reach that point with storage failing is from inside the storage itself.
     */
    DataStorage createDataStorage() {
        return new YamlDataStorage(this);
    }

    @Override
    public void onDisable() {
        // Released before anything else, because the server cancels the repeating task that
        // would otherwise have cleared it. A reload that left this set would come back up
        // refusing /sgen protect with "already running" and no job anywhere to finish.
        backfill.set(null);
        claimRelease.set(null);
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
            // change that has not been written yet would otherwise be discarded. While
            // storage is failed the save writes nothing, so this is also the retry that
            // reads the file an operator has just repaired, and not an overwrite of it.
            dataStorage.save();
            dataStorage.load();
        }
        // Re-selected rather than kept: protection.enabled, the provider and the claim size
        // are all reloadable, and the minimum-size check the provider makes at construction
        // is only correct for the size it was constructed with.
        this.protectionProvider = ProtectionProviders.create(this, pluginConfig);
        // Cleared so an operator-initiated reload always re-reports the bind outcome. The
        // suppression exists to keep the per-join retry quiet, and an administrator who has
        // just edited the file and reloaded is owed the answer whether or not the name they
        // tried is the same one that failed last time.
        this.unresolvedWorldReported.set(null);
        initSpawnManager();
        resumeHeldIfAvailable();
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
     * Binds the spawn manager to the configured world, and to nothing else.
     *
     * <p>There is deliberately no fallback to another world. Allocation force-overwrites a
     * player's respawn point and teleports them, so a bind to the wrong world cannot be
     * undone for anyone it has already touched, while declining to bind can be fixed by
     * correcting one line of config. Every caller of {@link #getSpawnManager()} already
     * treats an absent manager as "cannot allocate yet".
     *
     * <p>Absent is not fatal: {@link #handlePlayerFirstJoin} calls this again whenever the
     * manager is missing, so a world that only exists after enable - world managers create
     * theirs from their own {@code onEnable}, in load order nobody controls - is picked up
     * on the first join or held player's action that needs it. The error is reported once
     * per configured name, across all threads, so that retry does not fill the log.
     *
     * <p>Always rebinds, under {@link #bindLock}. A retry goes through
     * {@link #bindIfUnbound} instead.
     *
     * <p>Package-private as a test seam.
     */
    void initSpawnManager() {
        synchronized (bindLock) {
            PluginConfig config = pluginConfig;
            String configured = config.getWorldName();
            World world = Bukkit.getWorld(configured);
            if (world == null) {
                if (!configured.equals(unresolvedWorldReported.getAndSet(configured))) {
                    String loaded = Bukkit.getWorlds().stream().map(World::getName)
                            .collect(Collectors.joining(", "));
                    getLogger().severe("Configured world '" + configured + "' (origin.world) is not loaded "
                            + "yet. Allocation starts as soon as it is: a world loaded later is picked "
                            + "up on the next join, with no reload. Loaded worlds: "
                            + (loaded.isEmpty() ? "(none)" : loaded)
                            + ". If the name is wrong, correct origin.world and run /sgen reload.");
                }
                // Cleared as well as left unset: a reload that breaks the name must not leave
                // the previous world still bound behind a config that no longer names it.
                this.spawnManager = null;
                return;
            }
            unresolvedWorldReported.set(null);
            recordConfiguredCentre(config);
            // Resolved on every bind rather than with the protection provider: it is in force
            // whenever GriefPrevention is installed, protection enabled or not.
            this.spawnManager = new SpawnManager(this, world, config,
                    ProtectionProviders.createClaimLookup(this));
            getLogger().info("SpawnManager bound to world '" + world.getName()
                    + "' (origin.world: '" + configured + "').");
        }
    }

    /**
     * Records the configured spiral centre, so that a file written before centres had ids
     * has its plots placed on centre 0 at the origin and cell size configured when it is
     * first loaded, rather than wherever the centre has been moved to by the first
     * allocation after it.
     *
     * <p>Skipped while storage is failed; the reservation that follows the reload which
     * reads it does the same.
     */
    private void recordConfiguredCentre(PluginConfig config) {
        DataStorage storage = dataStorage;
        if (storage == null || storage.isFailed()) {
            return;
        }
        try {
            storage.centreFor(config.getOriginX(), config.getOriginZ(), config.getCellSize());
        } catch (IllegalStateException e) {
            // A reload that failed to read the file in between; the next reservation after
            // one that succeeds records it.
            getLogger().fine("Spiral centre not recorded: " + e.getMessage());
        }
    }

    /**
     * Re-resolves the world for a retry, if nothing is bound, and reports whether this call
     * bound it.
     *
     * <p>The check is repeated under the lock: a retry that saw nothing bound and then waited
     * on a reload's bind must not replace that manager with another of its own.
     */
    private boolean bindIfUnbound() {
        synchronized (bindLock) {
            if (spawnManager != null) {
                return false;
            }
            initSpawnManager();
            return spawnManager != null;
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
            // Nothing to allocate, so nothing left to wait for either. A plot recorded while
            // the player was away is placed here instead, at the point a new player would
            // have been allocated.
            forgetFromGate(uuid);
            placeIfOwed(player);
            return;
        }

        // Reached whenever origin.world names a world the server has not loaded:
        // initSpawnManager refuses to bind anywhere else, and this re-resolve is what picks
        // the world up if it appears later.
        boolean justBound = spawnManager == null && bindIfUnbound();

        AllocationUnavailable unavailable = allocationUnavailable();
        if (unavailable != null) {
            // Returns before takeAllocation, so the player is held rather than dropped.
            holdUnavailable(player, clientType, unavailable);
            return;
        }

        // After the hold rather than before it: the install time is read from storage, which
        // a failed load leaves unreadable, and a player held for that is decided here once a
        // reload resumes them. Before takeAllocation, so a skipped player reserves nothing.
        if (skipIfPreInstall(player)) {
            if (justBound) {
                resumeHeldIfAvailable();
            }
            return;
        }

        boolean owned = takeAllocation(uuid);
        if (justBound) {
            // After takeAllocation, which drops this player from the hold, so the players
            // resumed are the others who were waiting on the same bind.
            resumeHeldIfAvailable();
        }
        if (!owned) {
            return; // An allocation for this player is already in flight.
        }
        // Read again now the guard is held. An allocation for this player's previous session
        // can record its plot and release the guard on another region thread after the read
        // at the top of this method, and allocating on that stale answer would reserve a
        // second index and write over the first plot after it had been placed and claimed.
        // The record is written before that guard is released, so it is visible here.
        if (dataStorage.hasSpawn(uuid)) {
            allocating.remove(uuid);
            placeIfOwed(player);
            return;
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
            allocateSpawn(CellReserver.of(dataStorage)).thenAccept(outcome -> {
                SpawnManager.LocationResult res;
                switch (outcome) {
                    case SpawnManager.LocationResult found -> res = found;
                    case SpawnManager.BorderExhausted exhausted -> {
                        // An outcome, not an error: the scan that gave up has already put it
                        // on the console once, in plain text, as has the first refusal after
                        // the border returned to where a scan gave up, and every later join
                        // is refused for the same reason until the border changes. Repeating
                        // it here for each of them, with a trace, would bury everything else.
                        // The player stays where they are, which is inside the border.
                        getLogger().fine("No plot for " + player.getName() + ": "
                                + exhausted.message());
                        applied.complete(null);
                        holdIfUnavailable(player, clientType);
                        return;
                    }
                }
                // Player state must be touched on the thread owning that player. The entity
                // scheduler is that thread on Folia and the main thread on Paper; it also drops
                // the task automatically if the player disconnects before it runs.
                boolean scheduled = runForPlayer(player, () -> {
                    try {
                        // isConnected rather than isOnline: isOnline looks the UUID up, so it
                        // reads true for this entity again once the player has rejoined as
                        // a new one.
                        if (!player.isConnected()) {
                            recordForAbsentPlayer(player, clientType, res);
                            return;
                        }
                        // A reload that failed to read data.yml can land while the scan runs,
                        // or between any check made here and the write itself, so the write's
                        // own answer is what everything after it is gated on. A refused write
                        // records nothing, so the respawn point, teleport and claim below
                        // would point the player at a plot nobody holds.
                        if (!dataStorage.setSpawn(uuid, res.location(), res.centre(),
                                res.index(), res.gridU(), res.gridV(), player.getName(),
                                clientType, false)) {
                            applied.complete(null);
                            holdRefusedAllocation(player, clientType, res.plotLabel());
                            return;
                        }
                        sendToPlot(player, res.location(), res.plotLabel(), false);
                    } finally {
                        // Inside the task, so the guard outlives the write that makes
                        // hasSpawn() true rather than being released before it.
                        applied.complete(null);
                    }
                }, () -> {
                    // The player disconnected before the task ran. The index is theirs, so
                    // the plot is recorded against them rather than dropped; see
                    // recordForAbsentPlayer. Released after the write, for the reason the
                    // task above releases in its finally.
                    try {
                        recordForAbsentPlayer(player, clientType, res);
                    } finally {
                        applied.complete(null);
                    }
                });

                // A refused task means neither callback above ever runs. The entity
                // scheduler refuses only an entity that has already been retired, which is
                // the same disconnect the retired callback reports, so it is handled the
                // same way; the guard would otherwise leak as well.
                if (!scheduled) {
                    try {
                        recordForAbsentPlayer(player, clientType, res);
                    } finally {
                        applied.complete(null);
                    }
                }
            }).exceptionally(ex -> {
                getLogger().log(Level.SEVERE, "Error while asynchronously allocating spiral spawn for " + player.getName(), ex);
                applied.complete(null);
                holdIfUnavailable(player, clientType);
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
            holdIfUnavailable(player, clientType);
        } catch (Throwable t) {
            applied.complete(null);
            throw t;
        }
    }

    /**
     * Whether a player played on this server before SpiralGenesis was installed.
     *
     * <p>{@code hasPlayedBefore()} alone cannot say so: it is as true for a player who first
     * joined after the install and left before their plot was placed - the gate never
     * released them, allocation was held, or the write was refused - and treating them as
     * settled would leave them without a plot for good. So the first-played time the server
     * keeps for them is compared against the time the plugin first recorded anything, which
     * storage keeps for exactly this.
     *
     * <p>A player whose data file the server has read but who has no first-played time at
     * all counts as having played before. The server keeps that time beside its own data in
     * the player file, and reads the first-played and last-played times from it together; a
     * file with neither was written by a server that has never run Bukkit, so the player
     * predates any plugin on it. Checked against the CraftPlayer bytecode of paper 1.20.4:
     * the last-played time then stays at the 0 it is constructed with, while the first-played
     * time reads as the current join.
     *
     * <p>False while the install time is unknown, which is while storage is failed. That is
     * never taken as leave to allocate: {@link #allocationUnavailable} reports an unknown
     * install time as unreadable storage, so every caller that could allocate holds the
     * player first and decides once a reload reads the file.
     */
    public boolean isPreInstallPlayer(Player player) {
        if (!player.hasPlayedBefore()) {
            return false;
        }
        Instant installed = dataStorage.getInstalledAt();
        if (installed == null) {
            return false;
        }
        long firstPlayed = player.getFirstPlayed();
        if (player.getLastPlayed() <= 0L || firstPlayed <= 0L) {
            return true;
        }
        return firstPlayed < installed.toEpochMilli();
    }

    /**
     * Leaves a player who played here before SpiralGenesis was installed where they are, if
     * this is one.
     *
     * <p>Nothing is allocated, reserved, set or claimed: their bed, anchor and position are
     * theirs from before the plugin existed. Dropped from the gate, including any hold, so
     * nothing retries them on each action. A player with a record is never skipped, however
     * long they have played here, so a plot owed to them is still placed.
     *
     * <p>Logged at info, once per player per server run, with the command that places them.
     *
     * @return true if the player was skipped and the caller must not allocate them
     */
    public boolean skipIfPreInstall(Player player) {
        UUID uuid = player.getUniqueId();
        if (dataStorage.hasSpawn(uuid) || !isPreInstallPlayer(player)) {
            return false;
        }
        forgetFromGate(uuid);
        if (preInstallReported.add(uuid)) {
            getLogger().info(player.getName() + " played on this server before SpiralGenesis"
                    + " was installed (" + dataStorage.getInstalledAt() + "), so no plot is"
                    + " allocated and they are not moved. Run /sgen reassign "
                    + player.getName() + " to give them one; it replaces their bed or"
                    + " respawn anchor with the new plot.");
        }
        return true;
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
    CompletableFuture<SpawnManager.AllocationOutcome> allocateSpawn(CellReserver cells) {
        // Read once. The caller's guard is no longer proof that the field is still set: a
        // reload onto an unresolvable world unbinds it, and it can land between that guard
        // and this line. Failing the future rather than dereferencing null keeps the
        // outcome a reported failure instead of a swallowed NullPointerException.
        SpawnManager manager = spawnManager;
        if (manager == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "no world is bound; origin.world names no loaded world"));
        }
        return manager.allocateNextSafeSpawn(cells);
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
     * Records a found plot for a player who disconnected before it could be applied, and
     * marks them to be placed on it when they return.
     *
     * <p>The index was reserved for this player when the scan started, so it stays theirs:
     * dropping the result would leave the cell recorded against nobody and allocate them a
     * second one on their next join. Only the record is written here. The respawn point,
     * the teleport and the claim all need the player's own thread, which a disconnected
     * player does not have, so they wait for {@link #placeIfOwed}.
     *
     * <p>Reached from the retired callback, from a scheduler that refused the task, or from
     * the task itself: on whichever thread retired the entity or finished the scan. Nothing
     * here touches the player's state. {@link DataStorage#setSpawn} is safe from any thread,
     * as it already is for the region threads first allocations run on.
     *
     * <p>The mark is written with the record, in the same storage write, so a rejoin that
     * sees the record also sees the mark, and both survive a restart. A refused write is
     * handled as it is for a connected player: nothing is recorded or marked, and the
     * refusal is reported once.
     */
    private void recordForAbsentPlayer(Player player, String clientType,
                                       SpawnManager.LocationResult res) {
        UUID uuid = player.getUniqueId();
        if (!dataStorage.setSpawn(uuid, res.location(), res.centre(), res.index(), res.gridU(),
                res.gridV(), player.getName(), clientType, true)) {
            Player current = Bukkit.getPlayer(uuid);
            if (current == null || !current.isConnected()) {
                holdRefusedAllocation(player, clientType, res.plotLabel());
                return;
            }
            // Rejoined. Decided on the new session's own thread, as the placement task is,
            // because on Folia it can be found here before its join event has put it in
            // the gate. If it is not retired first, the retired callback reports the
            // departure the same way.
            boolean scheduled = runForPlayer(current,
                    () -> refuseForRejoined(current, clientType, res.plotLabel()),
                    () -> holdRefusedAllocation(current, clientType, res.plotLabel()));
            if (!scheduled) {
                holdRefusedAllocation(current, clientType, res.plotLabel());
            }
            return;
        }
        getLogger().info(player.getName() + " disconnected before plot " + res.plotLabel()
                + " could be applied; it is recorded at (" + res.location().getBlockX() + ", "
                + res.location().getBlockY() + ", " + res.location().getBlockZ()
                + ") and they will be placed there when they return.");

        // A player who rejoined while the scan ran was routed as unassigned, and their own
        // allocation returned because this one was still in flight. One the gate is still
        // watching is placed when it releases them, which now finds the record; anyone
        // else is placed from here.
        Player current = Bukkit.getPlayer(uuid);
        if (current != null && current.isConnected()
                && (actionGate == null || !actionGate.isPending(uuid))) {
            placeIfOwed(current);
        }
    }

    /**
     * Reports a refused write for a player who has rejoined, on their own thread.
     *
     * <p>A session the gate is still waiting on is left to it. Holding it would take it off
     * the gate, and a reload's resume would then allocate the player before they have
     * acted. Their release reaches allocation anyway, finds no record, and allocates or
     * holds them then. Any other session is held as the entity now connected, so a reload
     * that recovers storage allocates them.
     */
    private void refuseForRejoined(Player current, String clientType, String plot) {
        if (actionGate != null && actionGate.isPending(current.getUniqueId())) {
            getLogger().warning("Plot " + plot + " for " + current.getName()
                    + " was not recorded, because data.yml could not be read when it was"
                    + " written. They have rejoined and will be allocated after their"
                    + " first uncancelled action.");
            return;
        }
        holdRefusedAllocation(current, clientType, plot);
    }

    /**
     * Whether a player's plot was recorded while they were away and they have not been
     * placed on it yet.
     *
     * <p>The join handler routes such a player as it would an unassigned one, so they are
     * placed at the point a new player would have been allocated: at once for Bedrock and
     * under {@code ON_JOIN}, on their first uncancelled action otherwise.
     *
     * <p>Read from the record, where the mark is stored, so it is as current as storage:
     * loaded from {@code data.yml} at startup and on every reload, and false while storage
     * is failed, since no record is readable then.
     */
    public boolean isPlacementOwed(UUID uuid) {
        StoredSpawn record = dataStorage.getRecord(uuid);
        return record != null && record.placementOwed();
    }

    /**
     * Places a player on a plot recorded while they were away, if one is owed: sets their
     * respawn point there, sends them there and requests its claim, exactly as a first
     * allocation does.
     *
     * <p>Once only. The mark is read and cleared on the player's own thread, where every
     * placement of this player runs, so two callers cannot both place them, and a record
     * that cannot be resolved yet - its world not loaded, or storage failed - leaves the
     * mark for the next call. The clear is a storage write, and like any other it is made
     * before the player is moved and gates the move: a refused clear places nobody, so the
     * file never goes on claiming a placement that has already been made. A player already
     * standing on their plot is never marked, so this does nothing for an ordinary
     * returning player.
     */
    private void placeIfOwed(Player player) {
        UUID uuid = player.getUniqueId();
        if (!isPlacementOwed(uuid)) {
            return;
        }
        runForPlayer(player, () -> {
            if (!player.isConnected() || dataStorage.isFailed()) {
                return;
            }
            // Checked here, on the player's own thread, and not only by the caller: on Folia
            // a rejoined session can be found before its join event has put it in the gate.
            // A player the gate is waiting on keeps the mark, and is placed when the gate
            // releases them, since release drops them from pending before calling in.
            if (actionGate != null && actionGate.isPending(uuid)) {
                return;
            }
            StoredSpawn record = dataStorage.getRecord(uuid);
            Location plot = record == null ? null : record.toLocation();
            if (plot == null || !record.placementOwed()) {
                return;
            }
            if (!dataStorage.clearPlacementOwed(uuid)) {
                getLogger().warning("Plot " + record.plotLabel() + " for " + player.getName()
                        + " was not placed, because data.yml could not be read when the"
                        + " placement was recorded. They were not moved; they will be placed"
                        + " once /sgen reload reads it and they rejoin.");
                return;
            }
            sendToPlot(player, plot, record.plotLabel(), true);
        }, null);
    }

    /**
     * Sets a player's respawn point on their recorded plot, sends them there, and requests
     * the plot's claim, in that order. Called on the thread that owns the player, once the
     * plot is recorded.
     *
     * @param returning whether the plot was recorded while the player was away, which only
     *                  changes the line logged on arrival
     */
    private void sendToPlot(Player player, Location plot, String label, boolean returning) {
        player.setRespawnLocation(plot, true);
        player.teleportAsync(plot).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                getLogger().info((returning ? "Teleported returning player " : "Assigned & teleported ")
                        + player.getName() + " to plot " + label
                        + (returning ? ", recorded while they were away," : "")
                        + " at (" + plot.getBlockX() + ", " + plot.getBlockY() + ", " + plot.getBlockZ() + ")");
                return;
            }
            // Recoverable rather than fatal: the plot is recorded and their respawn point
            // already points at it. But storage now claims a location the player is not
            // standing at, so it is marked for a retry on their next uncancelled action - a
            // login plugin cancelling teleports for unauthenticated players is the likeliest
            // cause, and that action is the signal it let go.
            markUnreached(player);
            getLogger().warning("Assigned " + player.getName() + " to plot " + label
                    + " but the teleport did not complete; they are recorded at ("
                    + plot.getBlockX() + ", " + plot.getBlockY() + ", "
                    + plot.getBlockZ() + ") without having been moved there. "
                    + "Will retry on their next uncancelled action.");
        }).exceptionally(ex -> {
            // thenAccept above runs only on normal completion, so without this a teleport
            // that fails outright is exactly as silent as the case the warning was added for.
            markUnreached(player);
            getLogger().log(Level.WARNING, "Assigned " + player.getName() + " to plot "
                    + label + " but the teleport failed; they are recorded there "
                    + "without having been moved. Will retry on their next "
                    + "uncancelled action.", ex);
            return null;
        });

        // Last, and deliberately so. It is after the write, because a claim around a point
        // that is not yet the player's recorded spawn is a claim around ground they may never
        // be sent to - and under PLAYER_CLAIM they would have paid for it. It is also after
        // the respawn point and the teleport request, so everything the player is owed has
        // already been asked for by the time a claim is attempted: the claim is an
        // enhancement to allocation, and nothing about it may sit in front of the placement.
        //
        // Still on this thread rather than in the teleport callback above, which resolves on
        // whichever thread finished the teleport. Callers run on the thread that owns the
        // player - the main thread on every server where a real provider exists - which is
        // what the provider's threading contract requires.
        //
        // Allocation has already steered around every claim it could see, so a claim in the
        // way here was made after the scan chose the plot, and is reported at warning.
        getSpawnProtector().protectAllocated(player.getUniqueId(), plot, "first allocation");
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
        // No repair while storage is failed: it ends in a storage write and a respawn-point
        // change, and neither may happen against records that could not be read.
        if (manager == null || stored == null || dataStorage.isFailed()) {
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
                getLogger().log(Level.WARNING, "Could not re-check plot " + record.plotLabel()
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

    /**
     * Announces the repair and hands off to the in-cell search, or, for a record with no
     * cell to search, straight to the outcome of a search that found nothing.
     */
    private void startRepairSearch(Player player, StoredSpawn record, Location stored) {
        String where = " at (" + stored.getBlockX() + ", " + stored.getBlockY() + ", "
                + stored.getBlockZ() + ")";
        SpiralCell cell = recordedCell(record);
        if (cell == null) {
            // A point set by hand is on no spiral, and a plot on a centre data.yml does not
            // record has no cell anyone can rebuild, so there is nothing to search that is
            // known to be the player's. Handled like a cell where nothing passed: record
            // unchanged, player held at world spawn until the point is safe again or an
            // operator sets a new one.
            String reason = record.onSpiral()
                    ? "plot " + record.plotLabel() + " is on spiral centre " + record.centre()
                            + ", which data.yml does not record, so its cell cannot be found"
                    : "point " + record.plotLabel() + " was set by /sgen setspawn and is on no"
                            + " spiral, so there is no cell to search";
            getLogger().warning("The spawn point of " + player.getName() + where
                    + " is no longer safe, and " + reason + ". Set a new one with"
                    + " /sgen setspawn or /sgen reassign.");
            applyRepair(player, record, stored, null, null, "No replacement was searched for;");
            return;
        }
        getLogger().warning("Plot " + record.plotLabel() + " is no longer safe for "
                + player.getName() + where + "; searching that cell for a replacement point.");
        try {
            searchInCell(cell).whenComplete((res, ex) -> applyRepair(player, record, stored,
                    res, ex, "No safe point found among the " + pluginConfig.getMaxCandidates()
                            + " sampled candidates in plot " + record.plotLabel() + ";"));
        } catch (Throwable t) {
            // The search does real work before it returns a future - it requests the first
            // chunk - so a throw there escapes before whenComplete is attached, and would
            // otherwise hold the guard for the life of the process.
            repairing.remove(player.getUniqueId());
            getLogger().log(Level.SEVERE, "In-cell search for plot " + record.plotLabel()
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
     *
     * @param cell the record's own cell; see {@link #recordedCell}
     */
    CompletableFuture<SpawnManager.LocationResult> searchInCell(SpiralCell cell) {
        // Read once, for the reason allocateSpawn does: repairSpawn null-checked the
        // manager several ticks ago, across a revalidation that awaits a chunk.
        SpawnManager manager = spawnManager;
        if (manager == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "no world is bound; origin.world names no loaded world"));
        }
        return manager.findSafeSpawnInCell(cell);
    }

    /**
     * The cell a record was allocated in: its index on its own centre, at the origin and
     * cell size recorded for that centre, never the configured ones. After
     * {@code /sgen setcenter} or a reload with a new origin or cell size the configured
     * geometry is a different spiral, and its cell at the same index is usually another
     * player's. A record of a file written before centres had ids is on centre 0, which is
     * recorded at the geometry configured when that file was first loaded.
     *
     * @return the cell, or {@code null} for a point set by {@code /sgen setspawn}, which is
     *         on no spiral, or for a centre {@code data.yml} does not record
     */
    SpiralCell recordedCell(StoredSpawn record) {
        if (!record.onSpiral()) {
            return null;
        }
        SpiralCentre centre = dataStorage.getCentre(record.centre());
        return centre == null ? null : centre.cell(record.index());
    }

    /**
     * Whether a respawn point is the given plot, matched on its block column in the plot's
     * world rather than on the exact block, because a plot that has been built over places
     * the player above the stored point.
     *
     * <p>The one test for this, shared by the repair and by the death handler in
     * {@code PlayerSpawnListener}, so they cannot disagree about which point is the plot.
     *
     * @param point a respawn point, unresolved
     * @param plot  the stored plot
     */
    public static boolean isPlotColumn(Location point, Location plot) {
        if (point == null || plot == null) {
            return false;
        }
        World pointWorld = point.getWorld();
        return pointWorld != null && pointWorld.equals(plot.getWorld())
                && point.getBlockX() == plot.getBlockX()
                && point.getBlockZ() == plot.getBlockZ();
    }

    /**
     * Applies the outcome of an in-cell repair search on the player's own thread.
     *
     * <p>Storage is rewritten only when a replacement was found. A cell where every sampled
     * candidate failed leaves the record alone deliberately: {@code max-candidates} samples
     * a dozen points out of the hundreds a cell holds, so "no candidate passed" is not
     * evidence the plot is unusable, and overwriting it would lose the assignment for good.
     *
     * @param nothingFound the start of the line logged when {@code res} is {@code null},
     *                     saying why there is no replacement
     */
    private void applyRepair(Player player, StoredSpawn record, Location stored,
                             SpawnManager.LocationResult res, Throwable error,
                             String nothingFound) {
        UUID uuid = player.getUniqueId();
        if (error != null) {
            repairing.remove(uuid);
            getLogger().log(Level.SEVERE, "In-cell search failed while repairing plot "
                    + record.plotLabel() + " for " + player.getName() + ".", error);
            return;
        }

        ScheduledTask scheduled = player.getScheduler().run(this, task -> {
            try {
                // Storage can fail during the search, on a reload; the repair is dropped
                // rather than applied to records that are no longer there.
                if (!player.isOnline() || dataStorage.isFailed()) {
                    return;
                }
                if (res == null) {
                    World world = Bukkit.getWorld(record.worldName());
                    getLogger().warning(nothingFound + " sending " + player.getName()
                            + " to world spawn. Their plot assignment is unchanged.");
                    if (player.isDead()) {
                        // Still on the death screen, so there is nothing to teleport, and on
                        // Folia a forced point on the plot would put them straight back on
                        // it: its respawn checks only the feet and head blocks. Clearing the
                        // point sends this respawn to the main world's spawn on both
                        // platforms, the overworld's unless a Paper operator moved it, so
                        // it is the plot world's only while that is the main world. Only
                        // the plot is cleared; a bed or anchor elsewhere is theirs to keep.
                        // The listener puts the plot back at the next death, when the plot
                        // is re-checked again.
                        Location point = player.getPotentialBedLocation();
                        if (isPlotColumn(point, stored)) {
                            player.setRespawnLocation(null, false);
                        }
                    } else {
                        sendToWorldSpawn(player, world, null);
                    }
                    return;
                }

                // Read before anything is written, on this thread, which owns the player.
                // getPotentialBedLocation returns the stored point without resolving it, so
                // it touches no block in whatever region a bed may stand in.
                boolean headedForPlot = repairMovesRespawnPoint(
                        player.getPotentialBedLocation(), stored);

                // Gated on the write for the reason the allocation task is: storage can fail
                // after the check above, and a refused write records nothing, so the point
                // below would be one no record holds.
                // The mark is carried over: a repair moves the point within the same plot,
                // which is no placement of the player. Read now rather than from the record
                // the search started with, which is from before the search: a placement
                // since then has cleared it, on this same thread, and must stay cleared.
                StoredSpawn latest = dataStorage.getRecord(uuid);
                boolean owed = latest != null && latest.placementOwed();
                if (!dataStorage.setSpawn(uuid, res.location(), record.centre(), record.index(),
                        record.gridU(), record.gridV(), player.getName(), record.clientType(),
                        owed)) {
                    getLogger().warning("Repair of plot " + record.plotLabel() + " for "
                            + player.getName() + " was not recorded, because data.yml could not"
                            + " be read; nothing was moved.");
                    return;
                }
                if (headedForPlot) {
                    player.setRespawnLocation(res.location(), true);
                    // A player still on the death screen is not somewhere to be teleported
                    // from; updating their respawn point above is what places them, and it
                    // is also the only lever that works on Folia, whose respawn never
                    // consults a plugin. Anyone already back in the world is moved directly.
                    if (!player.isDead()) {
                        player.teleportAsync(res.location());
                    }
                    getLogger().info("Repaired plot " + record.plotLabel() + " for "
                            + player.getName() + "; moved within the same cell to ("
                            + res.location().getBlockX() + ", " + res.location().getBlockY()
                            + ", " + res.location().getBlockZ() + ").");
                } else {
                    // A bed, an anchor or a point forced elsewhere is where this player
                    // respawns, so neither it nor the player is moved: the repair only
                    // changes where the plot is recorded. A bed that stops working is
                    // handled when the server clears the point on respawn.
                    getLogger().info("Repaired plot " + record.plotLabel() + " for "
                            + player.getName() + "; recorded at ("
                            + res.location().getBlockX() + ", " + res.location().getBlockY()
                            + ", " + res.location().getBlockZ() + "). Their respawn point is"
                            + " elsewhere and was left alone.");
                }

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
     * Moves a player to world spawn, at a position there they can stand
     * ({@link SpawnManager#worldSpawnPoint}) rather than at the stored block, which can be
     * solid: a player placed inside it suffocates, and their plot is still unsafe at the
     * next death, so the loop never ends by itself.
     *
     * <p>Safe to call from any thread. The position is found on the thread owning world
     * spawn and the move is made on the player's own. When nothing in the spawn's column
     * passes, the stored block is used anyway, as it always was, unless the player is
     * already there.
     *
     * @param world    the world whose spawn is used unchecked when no world is bound, and
     *                 so there is no manager to check it with
     * @param onlyFrom when set, the move is dropped unless the player is still in this
     *                 block column by then: the repair, among others, may have moved them
     *                 on while the position was being found
     */
    public void sendToWorldSpawn(Player player, World world, Location onlyFrom) {
        // Read once, for the reason allocateSpawn does.
        SpawnManager manager = spawnManager;
        CompletableFuture<Location> target;
        if (manager != null) {
            target = manager.worldSpawnPoint();
        } else {
            target = CompletableFuture.completedFuture(
                    world == null ? null : world.getSpawnLocation());
        }
        target.whenComplete((safe, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Could not check world spawn for "
                        + player.getName() + "; using it as stored.", error);
            }
            player.getScheduler().run(this, task -> {
                if (!player.isOnline() || player.isDead()) {
                    return;
                }
                if (onlyFrom != null && !isPlotColumn(player.getLocation(), onlyFrom)) {
                    return;
                }
                Location destination = safe;
                if (destination == null) {
                    if (error == null) {
                        getLogger().warning("There is no clear, safe position in the column"
                                + " of world spawn to send " + player.getName() + " to, so"
                                + " the stored block is used. Move world spawn with"
                                + " /setworldspawn.");
                    }
                    if (onlyFrom != null || world == null) {
                        return;
                    }
                    destination = world.getSpawnLocation();
                }
                player.teleportAsync(destination);
            }, null);
        });
    }

    /**
     * Whether a repair should move the player's respawn point onto the repaired plot.
     *
     * <p>Only when there is no point at all, or when the point is the plot being repaired.
     * A bed, an anchor or a point forced elsewhere is a choice the player made, and a repair
     * of the plot is no reason to take it away. Whether that bed still works is not checked:
     * doing so reads blocks in whatever region holds it, and a bed that has stopped working
     * is already handled when the server clears the point on respawn.
     *
     * <p>Which point is the plot is {@link #isPlotColumn}.
     *
     * @param current the player's stored respawn point, unresolved, or null if unset
     * @param oldPlot the plot as it was recorded before the repair
     */
    private static boolean repairMovesRespawnPoint(Location current, Location oldPlot) {
        return current == null || isPlotColumn(current, oldPlot);
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
     * {@link #sendToPlot}, and the claim is requested at the end of the same call those
     * callbacks were armed from. That call runs on the thread owning the player, and
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
            // Read again here: the record came from storage that a failed reload can have
            // dropped since, and no respawn point is set from records that are gone.
            if (!player.isOnline() || dataStorage.isFailed()) {
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
     * Why allocation cannot run right now, or {@code null} if it can.
     *
     * <p>The single check for the "allocation unavailable" state, so every cause of it holds
     * players the same way. It reads the current state and changes nothing; re-resolving
     * the world is the caller's business.
     */
    AllocationUnavailable allocationUnavailable() {
        // An unknown install time is storage that cannot be read yet, never a reason to
        // allocate: without it a player from before the install looks like a new one.
        if (dataStorage != null
                && (dataStorage.isFailed() || dataStorage.getInstalledAt() == null)) {
            return AllocationUnavailable.STORAGE_FAILED;
        }
        if (spawnManager == null) {
            return AllocationUnavailable.WORLD_UNBOUND;
        }
        return null;
    }

    /**
     * Holds a player until allocation is available, reporting it once per hold.
     *
     * <p>A standing hold, not a single retry. The player stays in the gate across every
     * action they take, each of which retries the bind quietly, and is allocated by
     * {@link #resumeHeldIfAvailable} as soon as the reason clears, whether an action or a
     * reload clears it. The gate's timeout is not re-armed: allocating anyway is the one
     * thing that cannot be done while the reason holds.
     *
     * <p>Reported at warning, once per player per hold. The cause itself is reported at
     * severe where it is detected, once, and repeating it per player would bury it.
     */
    private void holdUnavailable(Player player, String clientType, AllocationUnavailable reason) {
        if (actionGate == null) {
            return;
        }
        if (actionGate.hold(player, clientType)) {
            getLogger().warning(reason.holdMessage(player.getName()));
        }
        // The reason can clear between the check that sent this player here and the hold
        // above, after whatever cleared it had already resumed everyone then held. Checking
        // once more stops them being left waiting for an action they may never take.
        resumeHeldIfAvailable();
    }

    /**
     * Holds a player whose allocation failed after it had started, if allocation has since
     * become unavailable.
     *
     * <p>{@code takeAllocation} has already dropped them from the gate, on the assumption
     * that the caller holding them will finish the job. A reload onto an unresolvable world
     * between that point and the scan breaks that assumption: nothing was allocated, nothing
     * is watching them any more, and they would go the rest of the session with no plot.
     *
     * <p>No online check here: {@code hold} drops a player whose entity has disconnected,
     * by {@code isConnected()}, after the entry is in place.
     */
    private void holdIfUnavailable(Player player, String clientType) {
        AllocationUnavailable reason = allocationUnavailable();
        if (reason == null) {
            return;
        }
        holdUnavailable(player, clientType, reason);
    }

    /**
     * Holds a player whose plot was found but whose record storage refused to write.
     *
     * <p>Held unconditionally rather than through {@link #holdIfUnavailable}: a successful
     * reload can land between the refusal and this call, and a player dropped there would
     * have been taken off the gate by {@code takeAllocation} with nothing left to retry
     * them. The resume below allocates them at once if that has happened.
     *
     * <p>Reported here, once, in place of the hold's own line, because this one also says
     * what was abandoned. The index the scan claimed is recorded against nobody: the failed
     * load has already dropped the counter it came from, and the next successful load takes
     * the counter from the file, so the index is either handed out again to whoever is
     * allocated next or skipped, and never held by two players.
     */
    private void holdRefusedAllocation(Player player, String clientType, String plot) {
        boolean held = false;
        if (actionGate != null) {
            actionGate.hold(player, clientType);
            // Read back rather than taken from hold's answer, which is also false for a
            // player this session already holds. A player who disconnected is dropped by
            // hold, or by the quit that follows it, so only a connected one is held.
            held = player.isConnected() && actionGate.isHeld(player.getUniqueId());
        }
        String refused = "Plot " + plot + " for " + player.getName() + " was not recorded,"
                + " because data.yml could not be read when it was written. They were not"
                + " moved and their respawn point is unchanged; ";
        if (held) {
            getLogger().warning(refused + "they are held and will be allocated once"
                    + " /sgen reload reads it successfully.");
        } else {
            getLogger().warning(refused + "they left before they could be held, and will be"
                    + " allocated when they next join.");
        }
        resumeHeldIfAvailable();
    }

    /**
     * Allocates every held player, if allocation is available.
     *
     * <p>Each one runs on the thread that owns them, as every other allocation does, and
     * through the same idempotent entry point an action takes, so a player an action is
     * already allocating is not allocated twice. They do not have to act again: every path
     * into a hold has already passed whatever the gate waits for, so the only thing they
     * were still waiting on is the reason that has just cleared.
     */
    private void resumeHeldIfAvailable() {
        if (actionGate == null || allocationUnavailable() != null) {
            return;
        }
        actionGate.forEachHeld((player, clientType) ->
                runForPlayer(player, () -> handlePlayerFirstJoin(player, clientType), null));
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

    /**
     * What an operator is told about failed storage, in chat or in reply to a command, or
     * {@code null} if storage is readable. One wording for every place that says it.
     */
    public String storageFailureNotice() {
        StorageFailure failure = dataStorage == null ? null : dataStorage.getFailure();
        if (failure == null) {
            return null;
        }
        return "SpiralGenesis could not read data.yml (" + failure.error() + "), so no spawn"
                + " is being allocated or changed and nothing is being saved. "
                + failure.copyNote() + " Repair or restore data.yml, then run /sgen reload.";
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
     * Starts the {@code /sgen release-all} job, if one is not already running.
     *
     * <p>Shaped exactly like {@link #startProtectionBackfill}: the job bounds itself per
     * tick, and this owns only the one-at-a-time guard and the driving.
     *
     * @param report invoked once with the summary line when the run finishes
     * @return the job that was started, or {@code null} if one was already running or it
     *         could not be scheduled
     */
    public SpawnClaimRelease startClaimRelease(Consumer<String> report) {
        if (claimRelease.get() != null) {
            return null;
        }
        SpawnClaimRelease job = new SpawnClaimRelease(getSpawnProtector(),
                dataStorage.getAllRecords(), line -> getLogger().info(line),
                this::claimReleaseHaltReason);
        if (!claimRelease.compareAndSet(null, job)) {
            return null;
        }
        if (job.isFinished()) {
            claimRelease.set(null);
            report.accept(job.summary());
            return job;
        }
        try {
            driveClaimRelease(job, () -> {
                claimRelease.set(null);
                getLogger().info(job.summary());
                report.accept(job.summary());
            });
        } catch (Throwable t) {
            // Cleared for the reason startProtectionBackfill clears its guard: a job that
            // was never scheduled must not leave every later run answering "already running".
            claimRelease.set(null);
            getLogger().log(Level.WARNING, "The spawn claim release could not be scheduled, "
                    + "so nothing was released.", t);
            report.accept("The spawn claim release could not be scheduled; see the console.");
            return null;
        }
        return job;
    }

    /**
     * Why a running {@code /sgen release-all} has to stop, or {@code null} to carry on.
     *
     * <p>The two refusals the command makes before starting, asked again before every
     * entry, because {@code /sgen reload} can change either while the job runs.
     */
    String claimReleaseHaltReason() {
        if (!getSpawnProtector().isActive()) {
            return "spawn protection stopped being active";
        }
        if (getPluginConfig().getClaimOwnership() == ClaimOwnership.PLAYER_CLAIM) {
            return "protection.claim-as was changed to PLAYER_CLAIM";
        }
        return null;
    }

    /** Whether a {@code /sgen release-all} job is running right now. */
    public boolean isClaimReleaseRunning() {
        return claimRelease.get() != null;
    }

    /**
     * Runs a release job one batch per tick until it says it is finished.
     *
     * <p>The global region scheduler, for the reasons {@link #driveBackfill} gives: releases
     * must happen on the main thread and the owners are mostly offline. Package-private as
     * the same test seam. On Folia the command never gets this far, because no supported
     * claim plugin runs there and it refuses while protection is inactive.
     */
    void driveClaimRelease(SpawnClaimRelease job, Runnable onFinish) {
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (job.runBatch()) {
                task.cancel();
                onFinish.run();
            }
        }, 1L, 1L);
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
