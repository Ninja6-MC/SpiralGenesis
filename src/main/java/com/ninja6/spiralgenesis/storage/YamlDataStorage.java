package com.ninja6.spiralgenesis.storage;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    /** Scratch file the next snapshot is written to before it replaces {@link #dataFile}. */
    private final Path tempFile;

    /** Guards {@link #yaml} mutation and serialisation. */
    private final Object yamlLock = new Object();

    /**
     * Serialises whole save operations against each other.
     *
     * <p>{@link #save()} has three callers on three different threads - the asynchronous
     * flush task, {@code /sgen reload} on the main thread, and shutdown - so two of them
     * can overlap. They would otherwise share one temp path, each truncating the other's
     * half-written scratch file and racing to rename it, which can publish an older
     * snapshot over a newer one. Held across serialisation as well as the write, so the
     * snapshot that reaches disk last is always the one taken last.
     */
    private final Object writeLock = new Object();
    private YamlConfiguration yaml;

    private final Map<UUID, StoredSpawn> spawnCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final AtomicInteger currentIndex = new AtomicInteger();
    private final AtomicBoolean dirty = new AtomicBoolean();

    private ScheduledTask flushTask;

    public YamlDataStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = plugin.getDataFolder().toPath().resolve("data.yml");
        this.tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
    }

    @Override
    public void load() {
        try {
            Files.createDirectories(dataFile.getParent());
            if (!Files.exists(dataFile)) {
                Files.createFile(dataFile);
            }
            // A scratch file left by a crash is never a recovery candidate: the rename that
            // publishes it is the last step, so anything still under the temp name was
            // incomplete when the process died, and data.yml still holds the last complete
            // snapshot. Removing it keeps a stale half-file from being mistaken for a backup.
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to prepare data.yml", e);
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
        synchronized (writeLock) {
            String serialised;
            synchronized (yamlLock) {
                if (yaml == null) {
                    return;
                }
                yaml.set("current-spiral-index", currentIndex.get());
                // Serialising under the yaml lock is cheap and in-memory; the disk write
                // below happens outside it so a slow disk never stalls an allocation.
                serialised = yaml.saveToString();
                dirty.set(false);
            }

            try {
                writeAtomically(serialised);
            } catch (IOException e) {
                dirty.set(true); // Retry on the next flush rather than dropping the change.
                plugin.getLogger().log(Level.SEVERE, "Failed to save data.yml", e);
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanup) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to remove the partial data.yml.tmp", cleanup);
                }
            }
        }
    }

    /**
     * Publishes a snapshot without ever leaving {@code data.yml} truncated.
     *
     * <p>Writing in place would mean truncating the only copy of every plot assignment and
     * the spiral counter, then refilling it: a crash, a forced stop or a full disk inside
     * that window leaves an empty or half-written file and the allocations are gone. The
     * snapshot therefore goes to a sibling scratch file, is forced to the platter, and only
     * then replaces the target by rename - so a reader sees either the previous complete
     * file or the new complete one.
     *
     * <p>The rename is atomic where the filesystem supports it. {@code ATOMIC_MOVE} is
     * required on the same filesystem by every mainstream platform, but a few - network and
     * fuse mounts in particular - refuse it, and a plain replace there is still strictly
     * better than truncating in place.
     *
     * <p>The containing directory is deliberately not synced. That final fsync is what makes
     * the rename itself survive a power cut on ext4-style filesystems, but the JDK exposes no
     * portable way to open a directory as a channel - it fails outright on Windows, a
     * first-class target for this plugin. The data loss this closes is the truncation window,
     * which is wide and routine; the unsynced-rename window is a single metadata commit.
     */
    private void writeAtomically(String serialised) throws IOException {
        byte[] bytes = serialised.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(tempFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            // Without this the rename can be committed ahead of the contents it publishes,
            // which turns a crash into a valid-looking but empty data.yml.
            channel.force(true);
        }

        try {
            Files.move(tempFile, dataFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
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
    public Map<UUID, StoredSpawn> getAllRecords() {
        // Map.copyOf rejects null keys and values; the cache can hold neither, because
        // setSpawn builds the record itself and ConcurrentHashMap refuses a null either way.
        return Map.copyOf(spawnCache);
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
