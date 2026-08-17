package com.ninja6.spiralgenesis.storage;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * YAML-based persistent storage implementation for SpiralGenesis (`data.yml`).
 *
 * <p>Writes are coalesced and flushed off the main thread: an allocation only marks the
 * store dirty, and a periodic asynchronous task serialises and writes the file. This keeps
 * player joins free of blocking disk I/O on the server thread.
 */
public class YamlDataStorage implements DataStorage {

    /** How often the background flush task checks for pending changes. */
    private static final long FLUSH_INTERVAL_SECONDS = 5L;

    private final JavaPlugin plugin;
    private final Path dataFile;

    /** Guards {@link #yaml} mutation and serialisation. */
    private final Object yamlLock = new Object();
    private YamlConfiguration yaml;

    private final Map<UUID, StoredSpawn> spawnCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final AtomicInteger currentIndex = new AtomicInteger();
    private final AtomicBoolean dirty = new AtomicBoolean();

    private ScheduledTask flushTask;

    public YamlDataStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = plugin.getDataFolder().toPath().resolve("data.yml");
    }

    @Override
    public void load() {
        try {
            Files.createDirectories(dataFile.getParent());
            if (!Files.exists(dataFile)) {
                Files.createFile(dataFile);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create data.yml", e);
        }

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(dataFile.toFile());

        spawnCache.clear();
        nameIndex.clear();

        int highestAssigned = -1;
        ConfigurationSection playersSec = loaded.getConfigurationSection("players");
        if (playersSec != null) {
            for (String key : playersSec.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping malformed UUID key in data.yml: " + key);
                    continue;
                }

                ConfigurationSection sec = playersSec.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }

                StoredSpawn record = new StoredSpawn(
                        sec.getString("world", "world"),
                        sec.getDouble("x"),
                        sec.getDouble("y"),
                        sec.getDouble("z"),
                        sec.getInt("assigned-index", -1),
                        sec.getInt("grid-u"),
                        sec.getInt("grid-v"),
                        sec.getString("name", ""),
                        sec.getString("client", "UNKNOWN")
                );

                spawnCache.put(uuid, record);
                if (record.playerName() != null && !record.playerName().isEmpty()) {
                    nameIndex.put(record.playerName().toLowerCase(Locale.ROOT), uuid);
                }
                highestAssigned = Math.max(highestAssigned, record.index());
            }
        }

        // Self-healing: if a crash lost the counter write, recover from the highest index
        // actually handed out. Skipping indices is harmless; reusing one is not.
        int stored = loaded.getInt("current-spiral-index", 0);
        currentIndex.set(Math.max(stored, highestAssigned + 1));

        synchronized (yamlLock) {
            this.yaml = loaded;
        }

        startFlushTask();
    }

    private void startFlushTask() {
        if (flushTask != null) {
            return;
        }
        try {
            flushTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin, task -> flushIfDirty(),
                    FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } catch (IllegalStateException e) {
            // Plugin not enabled (e.g. under test) - fall back to synchronous saves only.
            plugin.getLogger().fine("Asynchronous flush task unavailable; saving synchronously.");
        }
    }

    private void flushIfDirty() {
        if (dirty.get()) {
            save();
        }
    }

    @Override
    public void save() {
        String serialised;
        synchronized (yamlLock) {
            if (yaml == null) {
                return;
            }
            yaml.set("current-spiral-index", currentIndex.get());
            // Serialising under the lock is cheap and in-memory; the actual disk write below
            // happens outside it so a slow disk never stalls a caller holding the lock.
            serialised = yaml.saveToString();
            dirty.set(false);
        }

        try {
            Files.writeString(dataFile, serialised, StandardCharsets.UTF_8);
        } catch (IOException e) {
            dirty.set(true); // Retry on the next flush rather than dropping the change.
            plugin.getLogger().log(Level.SEVERE, "Failed to save data.yml", e);
        }
    }

    @Override
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        save();
    }

    @Override
    public boolean hasSpawn(UUID uuid) {
        return spawnCache.containsKey(uuid);
    }

    @Override
    public Location getSpawn(UUID uuid) {
        StoredSpawn record = spawnCache.get(uuid);
        if (record == null) {
            return null;
        }
        Location location = record.toLocation();
        if (location == null) {
            plugin.getLogger().warning("Spawn for " + uuid + " references world '"
                    + record.worldName() + "', which is not loaded.");
        }
        return location;
    }

    @Override
    public StoredSpawn getRecord(UUID uuid) {
        return spawnCache.get(uuid);
    }

    @Override
    public void setSpawn(UUID uuid, Location location, int index, int gridU, int gridV,
                         String playerName, String clientType) {
        StoredSpawn record = StoredSpawn.of(location, index, gridU, gridV, playerName, clientType);
        spawnCache.put(uuid, record);
        if (playerName != null && !playerName.isEmpty()) {
            nameIndex.put(playerName.toLowerCase(Locale.ROOT), uuid);
        }

        synchronized (yamlLock) {
            if (yaml == null) {
                return;
            }
            String path = "players." + uuid;
            yaml.set(path + ".name", record.playerName());
            yaml.set(path + ".client", record.clientType());
            yaml.set(path + ".assigned-index", record.index());
            yaml.set(path + ".grid-u", record.gridU());
            yaml.set(path + ".grid-v", record.gridV());
            yaml.set(path + ".x", record.x());
            yaml.set(path + ".y", record.y());
            yaml.set(path + ".z", record.z());
            yaml.set(path + ".world", record.worldName());
            yaml.set(path + ".assigned-date", Instant.now().toString());
        }
        dirty.set(true);
    }

    @Override
    public void removeSpawn(UUID uuid) {
        StoredSpawn removed = spawnCache.remove(uuid);
        if (removed != null && removed.playerName() != null) {
            nameIndex.remove(removed.playerName().toLowerCase(Locale.ROOT), uuid);
        }

        synchronized (yamlLock) {
            if (yaml == null) {
                return;
            }
            yaml.set("players." + uuid, null);
        }
        dirty.set(true);
    }

    @Override
    public UUID findByName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        return nameIndex.get(playerName.toLowerCase(Locale.ROOT));
    }

    @Override
    public int getCurrentIndex() {
        return currentIndex.get();
    }

    @Override
    public int reserveNextIndex() {
        int reserved = currentIndex.getAndIncrement();
        dirty.set(true);
        return reserved;
    }
}
