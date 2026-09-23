package com.ninja6.spiralgenesis.storage;

import com.ninja6.spiralgenesis.math.CellArea;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.math.SpiralMath;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Per-player key, written only while set, marking a placement still owed. */
    private static final String PLACEMENT_OWED_KEY = "placement-owed";

    /** Top-level key holding {@link #getInstalledAt()}, as an ISO-8601 instant. */
    private static final String INSTALLED_AT_KEY = "installed-at";

    /**
     * Top-level key holding the active centre's counter. Kept under the name it had before
     * centres had ids, so a version that predates them still loads the file and resumes the
     * spiral it would be allocating on.
     */
    private static final String COUNTER_KEY = "current-spiral-index";

    /**
     * Top-level table of spiral centres, {@code id: {x, z, cell-size, next-index}}. A file
     * written before centres had ids has none, and all of its records are on centre 0.
     */
    private static final String CENTRES_KEY = "centres";

    /** Top-level key holding the id of the centre {@link #COUNTER_KEY} counts for. */
    private static final String ACTIVE_CENTRE_KEY = "active-centre";

    /** Per-player key holding the id of the centre a plot's index is on. */
    private static final String CENTRE_KEY = "centre";

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

    /**
     * Every spiral centre known to this process, by id, guarded by {@link #yamlLock}.
     *
     * <p>Not cleared by a failed load, and merged rather than replaced by a successful one:
     * a centre recorded after the last save is still one a scan in flight may write, and
     * its counter state is what keeps that scan's index from being handed out again.
     */
    private final TreeMap<Integer, CentreState> centres = new TreeMap<>();

    /** Id of the centre the last reservation was made on, guarded by {@link #yamlLock}. */
    private int activeCentre;

    /**
     * Whether centre 0 is that of a file written before centres had ids and has not been
     * placed yet, guarded by {@link #yamlLock}. The next {@link #centreFor} places it at the
     * geometry it is given, which is the configured one.
     */
    private boolean legacyCentrePending;

    /**
     * Cells reserved by a scan and not yet recorded, refused or released, guarded by
     * {@link #yamlLock}. Not cleared by any load: the scan holding one may write it after.
     */
    private final Map<CellKey, SpiralCell> inFlight = new HashMap<>();

    /**
     * Spiral records by centre, then by grid position, then by player, guarded by
     * {@link #yamlLock}. The grid position is the index's, which does not depend on where
     * the centre is, so the index stays right when a centre's geometry becomes known.
     *
     * <p>A position holds every record on it, not one. Two records can share a centre and
     * index: a plot written by a version that predates centres reads as centre 0, and a
     * file from before indices were protected across reloads can hold one index twice.
     * Keeping one per position would let the second hide the first, and removing it would
     * leave the other player's cell open.
     */
    private final Map<Integer, Map<Long, Map<UUID, StoredSpawn>>> plotsByCell =
            new HashMap<>();

    /** Points set by hand, which are tested by their column, guarded by {@link #yamlLock}. */
    private final Map<UUID, StoredSpawn> manualPoints = new HashMap<>();

    /** See {@link #overlapProbes()}; guarded by {@link #yamlLock}. */
    private long overlapProbes;

    private final AtomicBoolean dirty = new AtomicBoolean();

    private ScheduledTask flushTask;

    /**
     * Why the last load failed, or {@code null} if it succeeded. Written only under
     * {@link #writeLock}; volatile because the mutators and every reader of
     * {@link #getFailure()} do not take that lock. Set before the records are dropped, so
     * it is never null while they are being cleared.
     */
    private volatile StorageFailure failure;

    /**
     * What {@link #getInstalledAt()} answers. Written under {@link #yamlLock}, and null
     * whenever {@link #yaml} is.
     */
    private volatile Instant installedAt;

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

            // An empty but parseable file is what a fresh install leaves behind after its
            // first start, so it counts as nothing recorded rather than as records. Decided
            // before the install time below is added to it.
            LoadOutcome outcome = loaded.getKeys(false).isEmpty()
                    ? LoadOutcome.NO_FILE : LoadOutcome.LOADED;
            Instant earliestAssigned = populate(loaded);
            // Settled before the failure is cleared, so storage never reads as recovered
            // while the install time is still unknown: an allocation in that window could
            // not tell a player from before the install apart from a new one.
            boolean installRecorded = settleInstalledAt(loaded, earliestAssigned);
            failure = null;
            startFlushTask();
            if (installRecorded) {
                // Written now rather than on the next flush: it is set once, and a crash
                // before that flush would have the next start take a later time for it.
                save();
            }
            return outcome;
        }
    }

    /**
     * Reads the install time from a loaded file, or records one if it has none.
     *
     * <p>A file with nothing recorded is a fresh install, so the time is now. A file with
     * records but no install time was written by a version that predates it, and takes the
     * earliest assignment it records: that version allocated the first player to join on
     * their first action. It is an upper bound rather than the install itself, because every
     * write of a record - a repair, a reassign, a setspawn - rewrites its assignment date, so
     * it can be later than the real install; that leans toward skipping. With no assignment
     * to go by it is now, which leaves every player who joined before this
     * start where they are - the mistake that can be put right with a command, where
     * allocating a settled player cannot be undone.
     *
     * <p>The value is read whether it is quoted or not. SnakeYAML resolves an unquoted
     * ISO-8601 value as a timestamp and hands back a {@link Date}, which is exactly what an
     * operator editing the key by hand writes. It is written back as a string, which the
     * dumper quotes because it would otherwise resolve as a timestamp, so it reads back
     * the same either way.
     *
     * @param earliestAssigned the earliest {@code assigned-date} in the file, or null
     * @return whether a time was recorded, so the file has to be written
     */
    private boolean settleInstalledAt(YamlConfiguration loaded, Instant earliestAssigned) {
        Object stored = loaded.get(INSTALLED_AT_KEY);
        Instant parsed = toInstant(stored);
        if (parsed != null) {
            synchronized (yamlLock) {
                installedAt = parsed;
            }
            return false;
        }
        Instant recorded = earliestAssigned != null ? earliestAssigned : Instant.now();
        if (stored != null) {
            plugin.getLogger().warning("data.yml has an " + INSTALLED_AT_KEY + " of '" + stored
                    + "', which is not an ISO-8601 instant; replacing it with " + recorded + ".");
        } else if (earliestAssigned != null) {
            plugin.getLogger().info("data.yml was written by an earlier version and has no "
                    + INSTALLED_AT_KEY + "; recording its earliest assignment, " + recorded
                    + ". Players who first joined before it are not allocated a plot.");
        }
        synchronized (yamlLock) {
            installedAt = recorded;
            loaded.set(INSTALLED_AT_KEY, recorded.toString());
        }
        return true;
    }

    /**
     * A YAML value as an instant: a timestamp SnakeYAML has already resolved, or a string
     * holding an ISO-8601 instant. Null for anything else, including null.
     */
    private static Instant toInstant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return value instanceof String text ? parseInstant(text) : null;
    }

    /** An ISO-8601 instant, or null for anything else, including null. */
    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Replaces the in-memory records with what was read from disk.
     *
     * @return the earliest {@code assigned-date} among the records, or null if none has one
     */
    private Instant populate(YamlConfiguration loaded) {
        spawnCache.clear();
        nameIndex.clear();

        Map<Integer, Integer> highestAssigned = new HashMap<>();
        Instant earliestAssigned = null;
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
                        sec.getString("client", "UNKNOWN"),
                        // Absent from every file written before the key existed, and from
                        // every record not owed a placement.
                        sec.getBoolean(PLACEMENT_OWED_KEY, false),
                        // Absent from every file written before centres had ids, all of whose
                        // plots are on the one spiral it had: centre 0.
                        sec.getInt(CENTRE_KEY, 0)
                );

                spawnCache.put(uuid, record);
                if (record.playerName() != null && !record.playerName().isEmpty()) {
                    nameIndex.put(record.playerName().toLowerCase(Locale.ROOT), uuid);
                }
                if (record.onSpiral()) {
                    highestAssigned.merge(record.centre(), record.index(), Math::max);
                }
                Instant assigned = toInstant(sec.get("assigned-date"));
                if (assigned != null
                        && (earliestAssigned == null || assigned.isBefore(earliestAssigned))) {
                    earliestAssigned = assigned;
                }
            }
        }

        // Self-healing: if a crash lost the counter write, recover from the highest index
        // actually handed out. Skipping indices is harmless; reusing one is not. Per centre,
        // so returning to one centre never inherits another's maximum.
        ConfigurationSection centresSec = loaded.getConfigurationSection(CENTRES_KEY);
        Map<Integer, SpiralCentre> fileCentres = readCentres(centresSec);
        Map<Integer, Integer> storedCounters = new HashMap<>();
        for (Integer id : fileCentres.keySet()) {
            storedCounters.put(id, centresSec.getInt(id + ".next-index", 0));
        }
        // The counter every version writes is the active centre's. A version that predates
        // centres advances it and nothing else, so it is taken if it is ahead.
        int storedActive = Math.max(0, loaded.getInt(ACTIVE_CENTRE_KEY, 0));
        storedCounters.merge(storedActive, loaded.getInt(COUNTER_KEY, 0), Math::max);

        List<String> warnings = new ArrayList<>();
        // Under the lock reservations take, so a reservation lands either before the
        // restore, and is counted in the high-water mark, or after it, on the new counter.
        synchronized (yamlLock) {
            for (SpiralCentre centre : fileCentres.values()) {
                CentreState state = state(centre.id());
                if (state.centre == null) {
                    state.centre = centre;
                } else if (!state.centre.equals(centre)) {
                    warnings.add("data.yml records centre " + centre.id() + " at ("
                            + centre.originX() + ", " + centre.originZ() + ") with cell-size "
                            + centre.cellSize() + ", but this server already has it at ("
                            + state.centre.originX() + ", " + state.centre.originZ()
                            + ") with cell-size " + state.centre.cellSize()
                            + "; keeping the one already in use.");
                }
            }
            for (Integer id : storedCounters.keySet()) {
                state(id);
            }
            for (Integer id : highestAssigned.keySet()) {
                state(id);
            }
            CentreState first = centres.get(0);
            legacyCentrePending = centresSec == null && (first == null || first.centre == null);

            for (Map.Entry<Integer, CentreState> entry : centres.entrySet()) {
                int id = entry.getKey();
                CentreState state = entry.getValue();
                int reserved = state.highWater;
                while (reserved > state.highestRecorded + 1
                        && state.refused.contains(reserved - 1)) {
                    reserved--;
                }
                state.refused.clear();
                int assigned = highestAssigned.getOrDefault(id, -1);
                state.highestRecorded = Math.max(state.highestRecorded, assigned);
                int restored = Math.max(Math.max(storedCounters.getOrDefault(id, 0),
                        assigned + 1), reserved);
                state.next = restored;
                state.highWater = restored;
                if (state.centre == null && assigned >= 0 && !(id == 0 && legacyCentrePending)) {
                    warnings.add("data.yml records plots on centre " + id + ", which is not in"
                            + " its " + CENTRES_KEY + " table; new plots are not tested against"
                            + " them.");
                }
            }
            activeCentre = storedActive;
            reindexPlots();
            this.yaml = loaded;
        }
        for (String warning : warnings) {
            plugin.getLogger().warning(warning);
        }
        return earliestAssigned;
    }

    /** The centre table of a loaded file, skipping and reporting any entry it cannot use. */
    private Map<Integer, SpiralCentre> readCentres(ConfigurationSection centresSec) {
        Map<Integer, SpiralCentre> read = new HashMap<>();
        if (centresSec == null) {
            return read;
        }
        for (String key : centresSec.getKeys(false)) {
            ConfigurationSection sec = centresSec.getConfigurationSection(key);
            int id;
            try {
                id = Integer.parseInt(key.strip());
            } catch (NumberFormatException e) {
                id = -1;
            }
            if (id < 0 || sec == null || !sec.isInt("x") || !sec.isInt("z")
                    || sec.getInt("cell-size") <= 0) {
                plugin.getLogger().warning("Skipping malformed centre '" + key + "' in data.yml;"
                        + " a centre needs a non-negative id, an integer x and z, and a"
                        + " positive cell-size.");
                continue;
            }
            read.put(id, new SpiralCentre(id, sec.getInt("x"), sec.getInt("z"),
                    sec.getInt("cell-size")));
        }
        return read;
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
            installedAt = null;
            spawnCache.clear();
            nameIndex.clear();
            plotsByCell.clear();
            manualPoints.clear();
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
                writeCentres();
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
    public boolean setSpawn(UUID uuid, Location location, int centre, int index, int gridU,
                            int gridV, String playerName, String clientType,
                            boolean placementOwed) {
        StoredSpawn record = StoredSpawn.of(location, centre, index, gridU, gridV, playerName,
                clientType, placementOwed);
        // Checked and applied under the lock enterFailedState clears under, so the check
        // and the write cannot straddle the clear, and the answer returned is the one that
        // decided the write.
        synchronized (yamlLock) {
            // Recorded or refused, the scan that held the cell is done with it.
            inFlight.remove(new CellKey(centre, index));
            if (failure != null) {
                if (record.onSpiral()) {
                    state(centre).refused.add(index);
                }
                return false;
            }
            unindexPlot(uuid, spawnCache.put(uuid, record));
            indexPlot(uuid, record);
            if (record.onSpiral()) {
                CentreState state = state(centre);
                state.highestRecorded = Math.max(state.highestRecorded, index);
            }
            if (playerName != null && !playerName.isEmpty()) {
                nameIndex.put(playerName.toLowerCase(Locale.ROOT), uuid);
            }
            if (yaml == null) {
                return true;
            }
            String path = "players." + uuid;
            yaml.set(path + ".name", record.playerName());
            yaml.set(path + ".client", record.clientType());
            yaml.set(path + "." + CENTRE_KEY, record.onSpiral() ? record.centre() : null);
            yaml.set(path + ".assigned-index", record.index());
            yaml.set(path + ".grid-u", record.gridU());
            yaml.set(path + ".grid-v", record.gridV());
            yaml.set(path + ".x", record.x());
            yaml.set(path + ".y", record.y());
            yaml.set(path + ".z", record.z());
            yaml.set(path + ".world", record.worldName());
            yaml.set(path + ".assigned-date", Instant.now().toString());
            // Removed rather than written false, so a record that was never owed anything
            // looks exactly as it did before the key existed.
            yaml.set(path + "." + PLACEMENT_OWED_KEY, placementOwed ? Boolean.TRUE : null);
        }
        dirty.set(true);
        return true;
    }

    @Override
    public boolean clearPlacementOwed(UUID uuid) {
        // Under the lock and against the failure, as setSpawn is, for the same reason.
        synchronized (yamlLock) {
            if (failure != null) {
                return false;
            }
            StoredSpawn record = spawnCache.get(uuid);
            if (record == null || !record.placementOwed()) {
                return true;
            }
            StoredSpawn cleared = record.withPlacementOwed(false);
            spawnCache.put(uuid, cleared);
            unindexPlot(uuid, record);
            indexPlot(uuid, cleared);
            if (yaml == null) {
                return true;
            }
            yaml.set("players." + uuid + "." + PLACEMENT_OWED_KEY, null);
        }
        dirty.set(true);
        return true;
    }

    @Override
    public void removeSpawn(UUID uuid) {
        synchronized (yamlLock) {
            if (failure != null) {
                return;
            }
            StoredSpawn removed = spawnCache.remove(uuid);
            unindexPlot(uuid, removed);
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
    public Instant getInstalledAt() {
        return installedAt;
    }

    @Override
    public int getCurrentIndex() {
        synchronized (yamlLock) {
            if (failure != null) {
                return 0;
            }
            CentreState state = centres.get(activeCentre);
            return state == null ? 0 : state.next;
        }
    }

    @Override
    public SpiralCentre getCentre(int id) {
        synchronized (yamlLock) {
            CentreState state = centres.get(id);
            return state == null ? null : state.centre;
        }
    }

    @Override
    public SpiralCentre centreFor(int originX, int originZ, int cellSize) {
        synchronized (yamlLock) {
            throwIfFailed();
            return resolveCentre(originX, originZ, cellSize);
        }
    }

    @Override
    public SpiralCell reserveCell(int originX, int originZ, int cellSize) {
        List<String> skipped = new ArrayList<>();
        SpiralCell cell;
        synchronized (yamlLock) {
            throwIfFailed();
            SpiralCentre centre = resolveCentre(originX, originZ, cellSize);
            cell = centre.cell(reserveLocked(centre.id(), skipped));
        }
        dirty.set(true);
        logSkipped(skipped);
        return cell;
    }

    @Override
    public void releaseCell(int centre, int index) {
        synchronized (yamlLock) {
            inFlight.remove(new CellKey(centre, index));
        }
    }

    @Override
    public int reserveNextIndex() {
        // Under the lock the failure is published under and a load restores the counters
        // under. A reservation cannot then take an index from the state of a failed load,
        // which a scan could write after a later load succeeds, and every index it does take
        // is in the high-water mark that load restores the counter past.
        List<String> skipped = new ArrayList<>();
        int reserved;
        synchronized (yamlLock) {
            throwIfFailed();
            reserved = reserveLocked(activeCentre, skipped);
        }
        dirty.set(true);
        logSkipped(skipped);
        return reserved;
    }

    /** Refuses a reservation while storage is failed. Called under {@link #yamlLock}. */
    private void throwIfFailed() {
        StorageFailure failed = failure;
        if (failed != null) {
            throw new IllegalStateException("data.yml could not be read: " + failed.error());
        }
    }

    /**
     * The centre with this geometry, recording it if there is none, and makes it the active
     * one. Called under {@link #yamlLock}.
     */
    private SpiralCentre resolveCentre(int originX, int originZ, int cellSize) {
        if (legacyCentrePending) {
            // Every plot of a file written before centres had ids is on centre 0, and the
            // geometry it was allocated at is taken to be the configured one.
            legacyCentrePending = false;
            state(0).centre = new SpiralCentre(0, originX, originZ, cellSize);
            dirty.set(true);
            activeCentre = 0;
            return state(0).centre;
        }
        for (Map.Entry<Integer, CentreState> entry : centres.entrySet()) {
            SpiralCentre known = entry.getValue().centre;
            if (known != null && known.hasGeometry(originX, originZ, cellSize)) {
                activeCentre = entry.getKey();
                return known;
            }
        }
        int id = centres.isEmpty() ? 0 : centres.lastKey() + 1;
        SpiralCentre created = new SpiralCentre(id, originX, originZ, cellSize);
        state(id).centre = created;
        plugin.getLogger().info("Recorded spiral centre " + id + " at (" + originX + ", "
                + originZ + ") with cell-size " + cellSize + ". New cells that overlap a plot"
                + " of another centre are skipped.");
        dirty.set(true);
        activeCentre = id;
        return created;
    }

    /**
     * Claims the next index of centre {@code id} whose cell overlaps nothing it must not,
     * and registers that cell as in flight. Called under {@link #yamlLock}.
     *
     * @param skipped collects a line for each index passed over
     */
    private int reserveLocked(int id, List<String> skipped) {
        CentreState state = state(id);
        while (true) {
            int index = state.next++;
            state.highWater = Math.max(state.highWater, index + 1);
            if (state.centre == null) {
                // Centre 0 of a file written before centres had ids, before anything has
                // said where it is: there is no cell to test.
                return index;
            }
            SpiralCell cell = state.centre.cell(index);
            String blocker = overlapping(cell);
            if (blocker == null) {
                inFlight.put(new CellKey(id, index), cell);
                return index;
            }
            skipped.add("Skipped plot " + cell.label() + ": its cell overlaps " + blocker + ".");
        }
    }

    /**
     * What a candidate cell overlaps, or {@code null} if nothing. Called under
     * {@link #yamlLock}.
     *
     * <p>A plot is tested by its whole cell, and a point set by hand by its column. Plots
     * of the candidate's own centre are not tested: two indices of one spiral never share a
     * cell, and two cells side by side share no column (see {@link CellArea}).
     */
    private String overlapping(SpiralCell candidate) {
        CellArea area = candidate.area();
        int id = candidate.centre().id();
        for (Map.Entry<Integer, Map<Long, Map<UUID, StoredSpawn>>> byCentre
                : plotsByCell.entrySet()) {
            if (byCentre.getKey() == id) {
                continue;
            }
            CentreState state = centres.get(byCentre.getKey());
            if (state == null || state.centre == null) {
                continue;
            }
            StoredSpawn blocker = overlappingPlot(area, state.centre, byCentre.getValue());
            if (blocker != null) {
                return "plot " + blocker.plotLabel() + " of " + blocker.playerName();
            }
        }
        for (StoredSpawn point : manualPoints.values()) {
            overlapProbes++;
            if (area.contains((int) Math.floor(point.x()), (int) Math.floor(point.z()))) {
                return "plot " + point.plotLabel() + " of " + point.playerName();
            }
        }
        for (SpiralCell held : inFlight.values()) {
            overlapProbes++;
            if (held.centre().id() != id && area.overlaps(held.area())) {
                return "plot " + held.label() + ", which is still being allocated";
            }
        }
        return null;
    }

    /**
     * The plot of {@code centre} whose cell overlaps {@code area}, or {@code null}. Called
     * under {@link #yamlLock}.
     *
     * <p>The cells of one centre are a grid, so the ones {@code area} can touch are a block
     * of grid positions found by arithmetic, and each is looked up rather than every plot
     * of the centre tested. When that block has more positions than the centre has plots,
     * as for a large candidate over a centre of small cells, the occupied positions are
     * tested instead. Either way the work is bounded by the smaller of the two.
     */
    private StoredSpawn overlappingPlot(CellArea area, SpiralCentre centre,
                                        Map<Long, Map<UUID, StoredSpawn>> plots) {
        long size = centre.cellSize();
        long base = (long) centre.originX() - size / 2;
        long baseZ = (long) centre.originZ() - size / 2;
        long uMin = Math.floorDiv(area.minX() - base, size);
        long uMax = Math.floorDiv(area.maxX() - 1L - base, size);
        long vMin = Math.floorDiv(area.minZ() - baseZ, size);
        long vMax = Math.floorDiv(area.maxZ() - 1L - baseZ, size);
        long positions = (uMax - uMin + 1) * (vMax - vMin + 1);
        if (positions > plots.size()) {
            for (Map<UUID, StoredSpawn> position : plots.values()) {
                overlapProbes++;
                StoredSpawn plot = position.values().iterator().next();
                if (area.overlaps(centre.cell(plot.index()).area())) {
                    return plot;
                }
            }
            return null;
        }
        for (long u = uMin; u <= uMax; u++) {
            for (long v = vMin; v <= vMax; v++) {
                overlapProbes++;
                Map<UUID, StoredSpawn> position = plots.get(gridKey((int) u, (int) v));
                if (position != null) {
                    return position.values().iterator().next();
                }
            }
        }
        return null;
    }

    /** Adds a record to the overlap index. Called under {@link #yamlLock}. */
    private void indexPlot(UUID uuid, StoredSpawn record) {
        if (!record.onSpiral()) {
            manualPoints.put(uuid, record);
            return;
        }
        int[] grid = SpiralMath.indexToGrid(record.index());
        plotsByCell.computeIfAbsent(record.centre(), id -> new HashMap<>())
                .computeIfAbsent(gridKey(grid[0], grid[1]), key -> new HashMap<>())
                .put(uuid, record);
    }

    /** Removes a record from the overlap index, if it is there. Under {@link #yamlLock}. */
    private void unindexPlot(UUID uuid, StoredSpawn record) {
        if (record == null) {
            return;
        }
        if (!record.onSpiral()) {
            manualPoints.remove(uuid, record);
            return;
        }
        Map<Long, Map<UUID, StoredSpawn>> plots = plotsByCell.get(record.centre());
        if (plots == null) {
            return;
        }
        int[] grid = SpiralMath.indexToGrid(record.index());
        long key = gridKey(grid[0], grid[1]);
        Map<UUID, StoredSpawn> position = plots.get(key);
        // Only this player's record leaves; anyone else on the position keeps it occupied.
        if (position != null && position.remove(uuid) != null && position.isEmpty()) {
            plots.remove(key);
        }
    }

    /** Rebuilds the overlap index from every record. Called under {@link #yamlLock}. */
    private void reindexPlots() {
        plotsByCell.clear();
        manualPoints.clear();
        for (Map.Entry<UUID, StoredSpawn> entry : spawnCache.entrySet()) {
            indexPlot(entry.getKey(), entry.getValue());
        }
    }

    private static long gridKey(int u, int v) {
        return ((long) u << 32) | (v & 0xFFFFFFFFL);
    }

    /**
     * Grid positions looked up and areas tested by reservations so far. A test seam: it
     * shows the overlap test does work bounded by the ground a candidate covers, not by the
     * number of plots recorded.
     */
    long overlapProbes() {
        synchronized (yamlLock) {
            return overlapProbes;
        }
    }

    private void logSkipped(List<String> skipped) {
        for (String line : skipped) {
            plugin.getLogger().info(line);
        }
    }

    /** The state of centre {@code id}, created empty if absent. Under {@link #yamlLock}. */
    private CentreState state(int id) {
        return centres.computeIfAbsent(id, key -> new CentreState());
    }

    /**
     * Writes the centre table and the active centre's counter into {@link #yaml}. Called
     * under {@link #yamlLock}.
     *
     * <p>A file with no centre placed yet is left as a version that predates centres wrote
     * it, so it still reads as one.
     */
    private void writeCentres() {
        CentreState active = centres.get(activeCentre);
        yaml.set(COUNTER_KEY, active == null ? 0 : active.next);
        yaml.set(CENTRES_KEY, null);
        boolean anyPlaced = false;
        for (Map.Entry<Integer, CentreState> entry : centres.entrySet()) {
            SpiralCentre centre = entry.getValue().centre;
            if (centre == null) {
                continue;
            }
            anyPlaced = true;
            String path = CENTRES_KEY + "." + entry.getKey();
            yaml.set(path + ".x", centre.originX());
            yaml.set(path + ".z", centre.originZ());
            yaml.set(path + ".cell-size", centre.cellSize());
            yaml.set(path + ".next-index", entry.getValue().next);
        }
        yaml.set(ACTIVE_CENTRE_KEY, anyPlaced ? activeCentre : null);
    }

    /**
     * The counter of one centre, guarded by {@link #yamlLock}.
     *
     * <p>Kept per centre so that returning to a centre resumes its own spiral, and so that
     * the restore rules below never carry one centre's maximum over to another.
     */
    private static final class CentreState {

        /**
         * Where this spiral is, or {@code null} for centre 0 of a file written before
         * centres had ids, until something says where it is.
         */
        private SpiralCentre centre;

        /** The next index this centre hands out. */
        private int next;

        /**
         * The highest value {@link #next} has held in this process.
         *
         * <p>Every index below it has been handed to a scan or recorded, and a scan that
         * reserved one may still write it after the load that follows. So a load never
         * restores the counter below this, whatever the file says: the file can be older
         * than the reservation, because the reservation was not flushed before the file
         * became unreadable, or because it was made between the save and the load of a
         * reload. Not cleared by a failed load, which is exactly the case it exists for.
         */
        private int highWater;

        /**
         * The highest index recorded in this process, from a file or a write. Like
         * {@link #highWater}, not cleared by a failed load.
         */
        private int highestRecorded = -1;

        /**
         * Indices whose write was refused since the last successful load.
         *
         * <p>A refused index is never recorded and its scan is over, so it is the one
         * reserved index nothing can still write. The next load lowers the high-water mark
         * past those at its top, which hands them out again rather than burning them - but
         * never past {@link #highestRecorded}, since a refused rewrite of a plot already
         * recorded, such as an in-cell repair, names an index that is not free.
         */
        private final Set<Integer> refused = new HashSet<>();
    }

    /** A cell by centre id and index, as the in-flight table keys it. */
    private record CellKey(int centre, int index) {}
}
