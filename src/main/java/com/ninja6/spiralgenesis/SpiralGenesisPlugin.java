package com.ninja6.spiralgenesis;

import com.ninja6.spiralgenesis.commands.SpiralCommand;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.hook.AuthMeHook;
import com.ninja6.spiralgenesis.hook.FloodgateHook;
import com.ninja6.spiralgenesis.listeners.AuthMeHookListener;
import com.ninja6.spiralgenesis.listeners.PlayerSpawnListener;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.storage.DataStorage;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

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
        getServer().getPluginManager().registerEvents(new PlayerSpawnListener(this), this);

        if (authMeHook.isInstalled()) {
            getLogger().info("Detected AuthMe-Reloaded! Enabling authentication-gated spawn listener.");
            getServer().getPluginManager().registerEvents(new AuthMeHookListener(this), this);
        }

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
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;

                dataStorage.setSpawn(uuid, res.location(), res.index(), res.gridU(), res.gridV(), player.getName(), clientType);

                player.setRespawnLocation(res.location(), true);
                player.teleportAsync(res.location()).thenAccept(success -> {
                    if (success) {
                        getLogger().info("Assigned & teleported " + player.getName() + " to plot #" + res.index() +
                                " at (" + res.location().getBlockX() + ", " + res.location().getBlockY() + ", " + res.location().getBlockZ() + ")");
                    }
                });
            });
        }).exceptionally(ex -> {
            getLogger().log(Level.SEVERE, "Error while asynchronously allocating spiral spawn for " + player.getName(), ex);
            return null;
        }).whenComplete((ignored, ex) -> allocating.remove(uuid));
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
