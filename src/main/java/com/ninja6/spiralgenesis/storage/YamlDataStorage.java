package com.ninja6.spiralgenesis.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * YAML-based persistent storage implementation for SpiralGenesis (`data.yml`).
 */
public class YamlDataStorage implements DataStorage {

    private final JavaPlugin plugin;
    private final File dataFile;
    private YamlConfiguration yaml;
    private final Map<UUID, Location> spawnCache = new ConcurrentHashMap<>();
    private int currentIndex = 0;

    public YamlDataStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    @Override
    public void load() {
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create data.yml", e);
            }
        }

        yaml = YamlConfiguration.loadConfiguration(dataFile);
        currentIndex = yaml.getInt("current-spiral-index", 0);

        spawnCache.clear();
        ConfigurationSection playersSec = yaml.getConfigurationSection("players");
        if (playersSec != null) {
            for (String key : playersSec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String worldName = playersSec.getString(key + ".world", "world");
                    double x = playersSec.getDouble(key + ".x");
                    double y = playersSec.getDouble(key + ".y");
                    double z = playersSec.getDouble(key + ".z");
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        spawnCache.put(uuid, new Location(world, x, y, z));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    @Override
    public synchronized void save() {
        if (yaml == null) return;
        yaml.set("current-spiral-index", currentIndex);
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save data.yml", e);
        }
    }

    @Override
    public boolean hasSpawn(UUID uuid) {
        return spawnCache.containsKey(uuid);
    }

    @Override
    public Location getSpawn(UUID uuid) {
        return spawnCache.get(uuid);
    }

    @Override
    public synchronized void setSpawn(UUID uuid, Location location, int index, int gridU, int gridV, String playerName, String clientType) {
        spawnCache.put(uuid, location);
        if (yaml != null) {
            String path = "players." + uuid.toString();
            yaml.set(path + ".name", playerName);
            yaml.set(path + ".client", clientType);
            yaml.set(path + ".assigned-index", index);
            yaml.set(path + ".grid-u", gridU);
            yaml.set(path + ".grid-v", gridV);
            yaml.set(path + ".x", location.getX());
            yaml.set(path + ".y", location.getY());
            yaml.set(path + ".z", location.getZ());
            yaml.set(path + ".world", location.getWorld() != null ? location.getWorld().getName() : "world");
            yaml.set(path + ".assigned-date", Instant.now().toString());
            save();
        }
    }

    @Override
    public synchronized void removeSpawn(UUID uuid) {
        spawnCache.remove(uuid);
        if (yaml != null) {
            yaml.set("players." + uuid.toString(), null);
            save();
        }
    }

    @Override
    public int getCurrentIndex() {
        return currentIndex;
    }

    @Override
    public synchronized void setCurrentIndex(int index) {
        this.currentIndex = index;
        if (yaml != null) {
            yaml.set("current-spiral-index", index);
            save();
        }
    }
}
