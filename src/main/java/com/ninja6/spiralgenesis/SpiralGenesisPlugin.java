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
            dataStorage.save();
        }
        getLogger().info("SpiralGenesis disabled.");
    }

    public void reload() {
        reloadConfig();
        loadConfiguration();
        if (dataStorage != null) {
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
     */
    public void handlePlayerFirstJoin(Player player, String clientType) {
        if (dataStorage.hasSpawn(player.getUniqueId())) {
            return;
        }

        getLogger().info("Allocating new spiral plot for " + clientType + " player " + player.getName() + " (" + player.getUniqueId() + ")...");
        int nextIndex = dataStorage.getCurrentIndex();

        if (spawnManager == null) {
            initSpawnManager();
        }

        if (spawnManager == null) {
            getLogger().severe("Cannot allocate spawn: SpawnManager world is unavailable!");
            return;
        }

        spawnManager.allocateNextSafeSpawn(nextIndex).thenAccept(res -> {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;

                dataStorage.setCurrentIndex(res.index() + 1);
                dataStorage.setSpawn(player.getUniqueId(), res.location(), res.index(), res.gridU(), res.gridV(), player.getName(), clientType);

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
        });
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
