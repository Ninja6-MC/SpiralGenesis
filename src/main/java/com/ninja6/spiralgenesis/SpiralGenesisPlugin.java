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
import com.ninja6.spiralgenesis.storage.DataStorage;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        this.dataStorage = new YamlDataStorage(this);
        this.dataStorage.load();

        this.floodgateHook = new FloodgateHook();
        this.authMeHook = new AuthMeHook();

        initSpawnManager();

        // Register event listeners
        this.actionGate = new PlayerActionGateListener(this, this::handlePlayerFirstJoin,
                () -> pluginConfig.getActionTimeoutSeconds());
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
        List<String> found = KNOWN_LOGIN_PLUGINS.stream()
                .filter(name -> getServer().getPluginManager().getPlugin(name) != null)
                .toList();
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
        initSpawnManager();
    }

    private void loadConfiguration() {
        this.pluginConfig = new PluginConfig(getConfig());
    }

    private void initSpawnManager() {
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
            return;
        }

        if (spawnManager == null) {
            initSpawnManager();
        }

        if (spawnManager == null) {
            getLogger().severe("Cannot allocate spawn: SpawnManager world is unavailable!");
            return;
        }

        if (!allocating.add(uuid)) {
            return; // An allocation for this player is already in flight.
        }

        getLogger().info("Allocating new spiral plot for " + clientType + " player " + player.getName() + " (" + uuid + ")...");

        // The index is claimed atomically inside the scan, so concurrent joins never
        // resolve to the same grid cell.
        spawnManager.allocateNextSafeSpawn(dataStorage::reserveNextIndex).thenAccept(res -> {
            // Player state must be touched on the thread owning that player. The entity
            // scheduler is that thread on Folia and the main thread on Paper; it also drops
            // the task automatically if the player disconnects before it runs.
            player.getScheduler().run(this, task -> {
                if (!player.isOnline()) return;

                dataStorage.setSpawn(uuid, res.location(), res.index(), res.gridU(), res.gridV(), player.getName(), clientType);

                player.setRespawnLocation(res.location(), true);
                player.teleportAsync(res.location()).thenAccept(success -> {
                    if (success) {
                        getLogger().info("Assigned & teleported " + player.getName() + " to plot #" + res.index() +
                                " at (" + res.location().getBlockX() + ", " + res.location().getBlockY() + ", " + res.location().getBlockZ() + ")");
                    }
                });
            }, () -> getLogger().fine("Player " + player.getName()
                    + " disconnected before their plot could be applied; index already reserved."));
        }).exceptionally(ex -> {
            getLogger().log(Level.SEVERE, "Error while asynchronously allocating spiral spawn for " + player.getName(), ex);
            return null;
        }).whenComplete((ignored, ex) -> allocating.remove(uuid));
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
        if (actionGate != null) {
            actionGate.forget(player.getUniqueId());
        }
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
}
