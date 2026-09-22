package com.ninja6.spiralgenesis.storage;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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

    /**
     * Suffix for the copy of an unreadable file. UTC and colon-free, because a colon is not
     * a legal file-name character on Windows.
     */
    private static final DateTimeFormatter BROKEN_STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

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

    /**
     * Saves that have failed in a row, guarded by {@link #writeLock}.
     *
     * <p>The flush task retries every few seconds, so a failure that persists - a full disk,
     * a read-only mount - would otherwise print a SEVERE stack trace on every attempt and
     * bury everything else on the console. Only the first failure of a run is logged in
     * full; the repeats go to FINE, and the first success afterwards is logged once.
     */
    private int consecutiveSaveFailures;
    private YamlConfiguration yaml;

    private final Map<UUID, StoredSpawn> spawnCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final AtomicInteger currentIndex = new AtomicInteger();
    private final AtomicBoolean dirty = new AtomicBoolean();

    private ScheduledTask flushTask;

    /**
     * Why the last load failed, or {@code null} if it succeeded. Written only under
     * {@link #writeLock}; volatile because the mutators and every reader of
     * {@link #getFailure()} do not take that lock. Set before the records are dropped, so
     * it is never null while they are being cleared.
     */
    private volatile StorageFailure failure;

    /** Digest of the last unreadable file copied aside, guarded by {@link #writeLock}. */
    private byte[] brokenDigest;
    /** File name that copy was written to, guarded by {@link #writeLock}. */
    private String brokenCopy;

    public YamlDataStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = plugin.getDataFolder().toPath().resolve("data.yml");
        this.tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
    }

    @Override
    public LoadOutcome load() {
        // Under the write lock from end to end. A save already in flight (the async flush,
        // when reload runs save then load) may be writing the scratch file this deletes, and
        // once this decides the file is unreadable no save may slip in behind the decision
        // and publish an empty snapshot over it.
        synchronized (writeLock) {
            boolean existed;
            try {
                Files.createDirectories(dataFile.getParent());
                existed = Files.exists(dataFile);
                if (!existed) {
                    Files.createFile(dataFile);
                }
                // A scratch file left by a crash is never a recovery candidate: the rename
                // that publishes it is the last step, so anything still under the temp name
                // was incomplete when the process died, and data.yml still holds the last
                // complete snapshot. Removing it keeps a stale half-file from being mistaken
                // for a backup.
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to prepare data.yml", e);
                existed = Files.exists(dataFile);
            }

            YamlConfiguration loaded = new YamlConfiguration();
            if (existed) {
                // Read once and parsed from those bytes, so the copy set aside on failure is
                // exactly what failed to parse. YamlConfiguration.loadConfiguration is not
                // used: it swallows a parse error and returns an empty configuration, which
                // made an unreadable file indistinguishable from a fresh install and reset
                // the spiral counter to zero.
                byte[] bytes = null;
                try {
                    bytes = Files.readAllBytes(dataFile);
                    loaded.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
                } catch (IOException | InvalidConfigurationException e) {
                    enterFailedState(bytes, e);
                    return LoadOutcome.UNREADABLE;
                }
            }

            populate(loaded);
            failure = null;
            startFlushTask();
            // An empty but parseable file is what a fresh install leaves behind after its
            // first start, so it counts as nothing recorded rather than as records.
            return loaded.getKeys(false).isEmpty() ? LoadOutcome.NO_FILE : LoadOutcome.LOADED;
        }
    }

    /** Replaces the in-memory records with what was read from disk. */
    private void populate(YamlConfiguration loaded) {
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
    }

    /**
     * Drops every record and refuses every write until a later load succeeds.
     *
     * <p>Empty records alone would be the defect this replaces: the spiral counter restarts
     * at zero, every returning player looks unallocated, and the next flush publishes that
     * empty state over the only copy of the real one. So the configuration is dropped as
     * well, which {@link #save()} and every mutator check, and the file is copied aside
     * before anyone is tempted to edit it.
     *
     * <p>The copy is made once per distinct content. A {@code /sgen reload} that fails on
     * the same file again reports again but names the copy it already has, so retrying
     * while diagnosing does not fill the folder with duplicates.
     *
     * @param bytes the file's contents, or {@code null} if it could not be read at all
     */
    private void enterFailedState(byte[] bytes, Exception cause) {
        String error = firstLine(cause);
        // Published before a single record is dropped, and the clear is made under the lock
        // the mutators check it under. A setSpawn on another region thread either finished
        // before the clear, and is cleared with the rest, or sees the failure and does
        // nothing; none can land after the clear and survive the failed state. The copy is
        // named once it exists.
        failure = new StorageFailure(error, null);
        synchronized (yamlLock) {
            this.yaml = null;
            spawnCache.clear();
            nameIndex.clear();
            currentIndex.set(0);
            dirty.set(false);
        }

        String copy = null;
        String copyError = null;
        if (bytes != null) {
            byte[] digest = sha256(bytes);
            if (brokenCopy != null && Arrays.equals(digest, brokenDigest)) {
                copy = brokenCopy;
            } else {
                try {
                    copy = writeBrokenCopy(bytes);
                    brokenDigest = digest;
                    brokenCopy = copy;
                } catch (IOException e) {
                    copyError = e.toString();
                }
            }
        } else {
            copyError = "the file could not be read";
        }

        failure = new StorageFailure(error, copy);
        plugin.getLogger().log(Level.SEVERE, "data.yml could not be read, so storage is"
                + " unavailable: no spawn will be allocated or changed and nothing will be"
                + " saved until /sgen reload reads it. " + (copy != null
                        ? "A copy of it is at " + copy + "."
                        : "It could not be copied aside (" + copyError + "), so take a copy"
                                + " before editing it.")
                + " Error: " + error, cause);
    }

    /** Copies the unreadable file aside under a name that is not already taken. */
    private String writeBrokenCopy(byte[] bytes) throws IOException {
        String base = dataFile.getFileName() + ".broken-" + BROKEN_STAMP.format(Instant.now());
        for (int attempt = 1; ; attempt++) {
            String name = attempt == 1 ? base : base + "-" + attempt;
            try {
                Files.write(dataFile.resolveSibling(name), bytes, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return name;
            } catch (FileAlreadyExistsException e) {
                if (attempt >= 100) {
                    throw e;
                }
            }
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /** The first line of an exception's message, which is all a chat line or reply needs. */
    private static String firstLine(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline).strip();
    }

    @Override
    public StorageFailure getFailure() {
        return failure;
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

    /**
     * The flush task's body. Package-private as a test seam: MockBukkit does not run the
     * asynchronous scheduler, so a test calls this directly to stand in for the task.
     */
    void flushIfDirty() {
        if (dirty.get()) {
            save();
        }
    }

    @Override
    public void save() {
        synchronized (writeLock) {
            // Checked under the write lock, which load() holds while it decides, so no save
            // can run between an unreadable read and the state that records it. This is the
            // only way into writeAtomically, so neither the scratch file nor the rename can
            // touch data.yml while storage is failed.
            if (failure != null) {
                return;
            }
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
                boolean firstFailure = consecutiveSaveFailures++ == 0;
                if (firstFailure) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save data.yml; further"
                            + " failures are logged at FINE until a save succeeds", e);
                } else {
                    plugin.getLogger().fine("Failed to save data.yml (attempt "
                            + consecutiveSaveFailures + "): " + e);
                }
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanup) {
                    plugin.getLogger().log(firstFailure ? Level.WARNING : Level.FINE,
                            "Failed to remove the partial data.yml.tmp", cleanup);
                }
                return;
            }
            if (consecutiveSaveFailures > 0) {
                plugin.getLogger().info("Saved data.yml after " + consecutiveSaveFailures
                        + " failed attempt(s).");
                consecutiveSaveFailures = 0;
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
        if (failure != null) {
            plugin.getLogger().warning("data.yml was not saved: it could not be read, and the"
                    + " file on disk is left exactly as it was.");
            return;
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
        // Checked and applied under the lock enterFailedState clears under, so the check
        // and the write cannot straddle the clear.
        synchronized (yamlLock) {
            if (failure != null) {
                return;
            }
            spawnCache.put(uuid, record);
            if (playerName != null && !playerName.isEmpty()) {
                nameIndex.put(playerName.toLowerCase(Locale.ROOT), uuid);
            }
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
        synchronized (yamlLock) {
            if (failure != null) {
                return;
            }
            StoredSpawn removed = spawnCache.remove(uuid);
            if (removed != null && removed.playerName() != null) {
                nameIndex.remove(removed.playerName().toLowerCase(Locale.ROOT), uuid);
            }
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
        // Lock-free, unlike the record mutators. A reservation racing the failure can still
        // advance the zeroed counter, but nothing reads that counter until a successful load
        // replaces it, and the record the reservation was for is refused by setSpawn.
        StorageFailure failed = failure;
        if (failed != null) {
            throw new IllegalStateException("data.yml could not be read: " + failed.error());
        }
        int reserved = currentIndex.getAndIncrement();
        dirty.set(true);
        return reserved;
    }
}
