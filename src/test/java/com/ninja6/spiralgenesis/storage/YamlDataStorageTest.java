package com.ninja6.spiralgenesis.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durability of the {@code data.yml} write path.
 *
 * <p>The write is the whole subject here: every plot assignment and the global spiral
 * counter live in that one file, so a save that can leave it truncated is a save that can
 * lose the lot. The failure is forced rather than simulated - the scratch path is made
 * unwritable by putting a directory where the file belongs, which fails the write at the
 * same point a full disk would, before anything has touched the live file.
 */
class YamlDataStorageTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;
    private Path dataFile;
    private Path tempFile;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
        dataFile = plugin.getDataFolder().toPath().resolve("data.yml");
        tempFile = plugin.getDataFolder().toPath().resolve("data.yml.tmp");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private YamlDataStorage loaded() {
        YamlDataStorage storage = new YamlDataStorage(plugin);
        storage.load();
        return storage;
    }

    private void record(YamlDataStorage storage, UUID uuid, String name, int index) {
        storage.setSpawn(uuid, new Location(world, index * 64, 64, 0), index, index, 0, name, "TEST");
    }

    @Test
    @DisplayName("a completed save publishes the snapshot and leaves no scratch file")
    void savePublishesAndCleansUp() throws IOException {
        YamlDataStorage storage = loaded();
        UUID uuid = UUID.randomUUID();
        record(storage, uuid, "Alice", 7);
        storage.save();

        YamlConfiguration written = YamlConfiguration.loadConfiguration(dataFile.toFile());
        assertEquals("Alice", written.getString("players." + uuid + ".name"));
        assertEquals(7, written.getInt("players." + uuid + ".assigned-index"));
        assertFalse(Files.exists(tempFile), "the scratch file should not outlive the save");
    }

    @Test
    @DisplayName("a failed write leaves the previous data.yml complete")
    void failedWriteLeavesPreviousFileIntact() throws IOException {
        YamlDataStorage storage = loaded();
        UUID first = UUID.randomUUID();
        record(storage, first, "Alice", 1);
        storage.save();

        String before = Files.readString(dataFile, StandardCharsets.UTF_8);
        assertTrue(before.contains("Alice"), "fixture should have been written");

        // A directory cannot be opened as a file, so the write fails before the live file
        // is touched - which is precisely the guarantee under test.
        Files.createDirectory(tempFile);
        Files.createFile(tempFile.resolve("occupant"));

        UUID second = UUID.randomUUID();
        record(storage, second, "Bob", 2);
        storage.save();

        assertEquals(before, Files.readString(dataFile, StandardCharsets.UTF_8),
                "a failed save must not truncate or alter the last good snapshot");

        // And the change is not dropped: once the obstruction is gone the next save lands.
        Files.delete(tempFile.resolve("occupant"));
        Files.delete(tempFile);
        storage.save();

        YamlConfiguration written = YamlConfiguration.loadConfiguration(dataFile.toFile());
        assertEquals("Alice", written.getString("players." + first + ".name"));
        assertEquals("Bob", written.getString("players." + second + ".name"));
    }

    @Test
    @DisplayName("load discards a scratch file left behind by a crash")
    void loadRemovesStaleScratchFile() throws IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.writeString(dataFile, "current-spiral-index: 3\n", StandardCharsets.UTF_8);
        Files.writeString(tempFile, "current-spiral-index: 99\ntruncated", StandardCharsets.UTF_8);

        YamlDataStorage storage = loaded();

        assertFalse(Files.exists(tempFile), "a stale scratch file is never a recovery candidate");
        assertEquals(3, storage.getCurrentIndex());
    }

    @Test
    @DisplayName("load waits for an in-flight save before removing the scratch file")
    void loadDeletesScratchFileUnderWriteLock() throws Exception {
        YamlDataStorage storage = loaded();
        Files.writeString(tempFile, "being written by a concurrent save", StandardCharsets.UTF_8);

        // Stand in for a save that holds the write lock while it fills the scratch file.
        Field lockField = YamlDataStorage.class.getDeclaredField("writeLock");
        lockField.setAccessible(true);
        Object writeLock = lockField.get(storage);

        Thread loader = new Thread(storage::load, "loader");
        synchronized (writeLock) {
            loader.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (loader.getState() != Thread.State.BLOCKED) {
                assertTrue(loader.isAlive() && System.nanoTime() < deadline,
                        "load should block on the write lock, not finish without it");
                Thread.onSpinWait();
            }
            assertTrue(Files.exists(tempFile),
                    "the scratch file must survive while the save holding the lock is running");
        }
        loader.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(loader.isAlive(), "load should finish once the lock is released");
        assertFalse(Files.exists(tempFile), "the stale scratch file is removed after the save");
    }

    @Test
    @DisplayName("a save that keeps failing logs one stack trace, then once on recovery")
    void repeatedSaveFailureIsNotRelogged() throws IOException {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = plugin.getLogger();
        logger.addHandler(capture);
        try {
            YamlDataStorage storage = loaded();
            Files.createDirectory(tempFile);
            Files.createFile(tempFile.resolve("occupant"));
            record(storage, UUID.randomUUID(), "Alice", 1);

            storage.save();
            List<LogRecord> severe = records.stream()
                    .filter(r -> r.getLevel() == Level.SEVERE).toList();
            assertEquals(1, severe.size(), "the first failure is reported at SEVERE");
            assertNotNull(severe.get(0).getThrown(), "the first failure carries its stack trace");

            records.clear();
            storage.save();
            storage.save();
            assertTrue(records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
                    "repeat failures must not reach the console at WARNING or above");

            Files.delete(tempFile.resolve("occupant"));
            Files.delete(tempFile);
            records.clear();
            storage.save();
            List<LogRecord> recovered = records.stream()
                    .filter(r -> r.getLevel() == Level.INFO).toList();
            assertEquals(1, recovered.size(), "recovery is reported exactly once");
            assertTrue(recovered.get(0).getMessage().contains("3 failed attempt"),
                    "the recovery message counts the failed attempts");

            records.clear();
            record(storage, UUID.randomUUID(), "Bob", 2);
            storage.save();
            assertTrue(records.stream().noneMatch(r -> r.getLevel() == Level.INFO),
                    "an ordinary save after recovery logs nothing further");
        } finally {
            logger.removeHandler(capture);
        }
    }

    /** Not YAML at all: an unclosed flow mapping, as a hand edit or a torn restore leaves. */
    private static final String UNPARSEABLE = "current-spiral-index: 12\nplayers: {\n  broken: [\n";

    /** Writes {@code content} as data.yml, bypassing the storage entirely. */
    private void writeDataFile(String content) throws IOException {
        Files.createDirectories(dataFile.getParent());
        Files.writeString(dataFile, content, StandardCharsets.UTF_8);
    }

    /** The copies of an unreadable file set aside so far, by name. */
    private List<String> brokenCopies() throws IOException {
        try (Stream<Path> files = Files.list(dataFile.getParent())) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("data.yml.broken-"))
                    .sorted()
                    .toList();
        }
    }

    /** Runs {@code body} and returns everything the plugin logged at SEVERE meanwhile. */
    private List<LogRecord> severeDuring(ThrowingRunnable body) throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(capture);
        try {
            body.run();
        } finally {
            plugin.getLogger().removeHandler(capture);
        }
        return records;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** A storage that loaded a record, and then found the file unreadable on its next load. */
    private YamlDataStorage failedAfterRecords(UUID uuid) throws IOException {
        YamlDataStorage storage = loaded();
        record(storage, uuid, "Alice", 4);
        storage.save();
        writeDataFile(UNPARSEABLE);
        assertEquals(DataStorage.LoadOutcome.UNREADABLE, storage.load());
        return storage;
    }

    @Test
    @DisplayName("no file loads as a fresh install and creates an empty one")
    void missingFileIsNoFile() {
        YamlDataStorage storage = new YamlDataStorage(plugin);

        assertEquals(DataStorage.LoadOutcome.NO_FILE, storage.load());
        assertTrue(Files.exists(dataFile));
        assertFalse(storage.isFailed());
        assertEquals(0, storage.getCurrentIndex());
    }

    @Test
    @DisplayName("an empty but parseable file is a fresh install, not a failure")
    void emptyFileIsNoFile() throws IOException {
        writeDataFile("");

        YamlDataStorage storage = new YamlDataStorage(plugin);

        assertEquals(DataStorage.LoadOutcome.NO_FILE, storage.load());
        assertFalse(storage.isFailed());
        assertEquals(List.of(), brokenCopies(), "nothing is copied aside for a fresh install");
    }

    @Test
    @DisplayName("a file with records loads them")
    void recordsAreLoaded() throws IOException {
        UUID uuid = UUID.randomUUID();
        YamlDataStorage first = loaded();
        record(first, uuid, "Alice", 4);
        first.save();

        YamlDataStorage storage = new YamlDataStorage(plugin);

        assertEquals(DataStorage.LoadOutcome.LOADED, storage.load());
        assertTrue(storage.hasSpawn(uuid));
        assertEquals(5, storage.getCurrentIndex());
    }

    @Test
    @DisplayName("an unparseable file fails storage, logs one SEVERE and is copied aside")
    void unparseableFileFailsStorage() throws Exception {
        writeDataFile(UNPARSEABLE);
        YamlDataStorage storage = new YamlDataStorage(plugin);

        DataStorage.LoadOutcome[] outcome = new DataStorage.LoadOutcome[1];
        List<LogRecord> severe = severeDuring(() -> outcome[0] = storage.load());

        assertEquals(DataStorage.LoadOutcome.UNREADABLE, outcome[0],
                "an unreadable file must not be taken for an empty one");
        assertTrue(storage.isFailed());
        assertEquals(1, severe.size(), "exactly one SEVERE: " + severe);
        assertNotNull(severe.get(0).getThrown(), "the parse error is attached");

        List<String> copies = brokenCopies();
        assertEquals(1, copies.size(), "one copy set aside: " + copies);
        assertEquals(copies.get(0), storage.getFailure().brokenCopy(),
                "the failure names the copy it made");
        assertTrue(severe.get(0).getMessage().contains(copies.get(0)),
                "the SEVERE names the copy: " + severe.get(0).getMessage());
        assertEquals(UNPARSEABLE, Files.readString(dataFile.resolveSibling(copies.get(0)),
                StandardCharsets.UTF_8), "the copy is the file that failed, byte for byte");
    }

    @Test
    @DisplayName("while failed, no record is readable and every write is refused")
    void failedStorageRefusesWrites() throws IOException {
        UUID uuid = UUID.randomUUID();
        YamlDataStorage storage = failedAfterRecords(uuid);

        assertFalse(storage.hasSpawn(uuid), "records from before the failure are dropped");
        assertEquals(Map.of(), storage.getAllRecords());

        UUID other = UUID.randomUUID();
        record(storage, other, "Bob", 9);
        storage.removeSpawn(uuid);
        assertFalse(storage.hasSpawn(other), "a spawn recorded while failed is ignored");
        assertThrows(IllegalStateException.class, storage::reserveNextIndex,
                "the counter that could not be read is never advanced");
    }

    /**
     * Pins the order inside the failure, which no end-state check can see: a record written
     * between the clear and the failure being published survives the whole failed state.
     *
     * <p>The test holds the lock the records are cleared under, so the loader stops at the
     * clear. By then the failure must already be visible, and a write attempted there - on
     * this thread, which holds the lock, as a region thread's setSpawn would be at that
     * moment - must be refused rather than accepted into records about to be abandoned.
     */
    @Test
    @DisplayName("writes are refused from the moment the failure starts dropping records")
    void mutatorsRefuseOnceFailureBegins() throws Exception {
        YamlDataStorage storage = loaded();
        writeDataFile(UNPARSEABLE);

        Field lockField = YamlDataStorage.class.getDeclaredField("yamlLock");
        lockField.setAccessible(true);
        Object yamlLock = lockField.get(storage);

        UUID late = UUID.randomUUID();
        Thread loader = new Thread(storage::load, "loader");
        synchronized (yamlLock) {
            loader.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (loader.getState() != Thread.State.BLOCKED) {
                assertTrue(loader.isAlive() && System.nanoTime() < deadline,
                        "the loader should stop at the clear, which needs this lock");
                Thread.onSpinWait();
            }
            assertTrue(storage.isFailed(),
                    "the failure must be published before any record is dropped");

            record(storage, late, "Late", 9);
            assertFalse(storage.hasSpawn(late), "a write landing at the clear must be refused");
            assertThrows(IllegalStateException.class, storage::reserveNextIndex);
        }
        loader.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(loader.isAlive());
        assertFalse(storage.hasSpawn(late), "nothing written during the failure survives it");
        assertNotNull(storage.getFailure().brokenCopy(), "the copy is named once it exists");
    }

    @Test
    @DisplayName("while failed, save writes nothing and leaves no scratch file")
    void failedSaveWritesNothing() throws IOException {
        YamlDataStorage storage = failedAfterRecords(UUID.randomUUID());

        storage.save();

        assertEquals(UNPARSEABLE, Files.readString(dataFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempFile), "not even the scratch file is written");
    }

    @Test
    @DisplayName("while failed, the flush task writes nothing even when marked dirty")
    void failedFlushWritesNothing() throws Exception {
        YamlDataStorage storage = failedAfterRecords(UUID.randomUUID());
        // Forced, because every mutator is refused and nothing else can mark it. A change
        // pending from before the failure is the case this stands for.
        Field dirtyField = YamlDataStorage.class.getDeclaredField("dirty");
        dirtyField.setAccessible(true);
        ((AtomicBoolean) dirtyField.get(storage)).set(true);

        storage.flushIfDirty();

        assertEquals(UNPARSEABLE, Files.readString(dataFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempFile));
    }

    @Test
    @DisplayName("while failed, shutdown writes nothing")
    void failedShutdownWritesNothing() throws Exception {
        YamlDataStorage storage = failedAfterRecords(UUID.randomUUID());
        // MockBukkit schedules the flush task but cannot cancel it, and its exception would
        // leave shutdown before the part under test. Dropping the handle skips the cancel.
        Field taskField = YamlDataStorage.class.getDeclaredField("flushTask");
        taskField.setAccessible(true);
        taskField.set(storage, null);

        storage.shutdown();

        assertEquals(UNPARSEABLE, Files.readString(dataFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempFile));
    }

    @Test
    @DisplayName("a load that reads a repaired file clears the failure and saves again")
    void repairedFileRecovers() throws IOException {
        UUID uuid = UUID.randomUUID();
        YamlDataStorage storage = failedAfterRecords(uuid);
        String copy = storage.getFailure().brokenCopy();

        // The operator restores the good file from the copy they took, or from a backup.
        writeDataFile("current-spiral-index: 7\nplayers:\n  " + uuid + ":\n    name: Alice\n"
                + "    assigned-index: 4\n    world: world\n");

        assertEquals(DataStorage.LoadOutcome.LOADED, storage.load());
        assertFalse(storage.isFailed());
        assertTrue(storage.hasSpawn(uuid));
        assertEquals(7, storage.reserveNextIndex(), "the counter resumes from the file");

        UUID other = UUID.randomUUID();
        record(storage, other, "Bob", 7);
        storage.save();
        YamlConfiguration written = YamlConfiguration.loadConfiguration(dataFile.toFile());
        assertEquals("Bob", written.getString("players." + other + ".name"),
                "saving works again once the failure is cleared");
        assertTrue(Files.exists(dataFile.resolveSibling(copy)), "the copy is left in place");
    }

    @Test
    @DisplayName("a failed retry on the same file reports again but makes no second copy")
    void failedRetryDoesNotCopyAgain() throws Exception {
        writeDataFile(UNPARSEABLE);
        YamlDataStorage storage = new YamlDataStorage(plugin);
        storage.load();
        String first = storage.getFailure().brokenCopy();

        List<LogRecord> severe = severeDuring(storage::load);

        assertEquals(1, severe.size(), "the retry reports again: " + severe);
        assertTrue(storage.isFailed());
        assertEquals(List.of(first), brokenCopies(), "the same file is not copied twice");
        assertEquals(first, storage.getFailure().brokenCopy(),
                "the retry names the copy already made");
    }

    @Test
    @DisplayName("a different unreadable file is copied aside as well")
    void differentBrokenFileIsCopied() throws IOException {
        writeDataFile(UNPARSEABLE);
        YamlDataStorage storage = new YamlDataStorage(plugin);
        storage.load();

        writeDataFile(UNPARSEABLE + "  another: [\n");
        storage.load();

        assertEquals(2, brokenCopies().size(),
                "an edit that is still broken is a different file, and is kept too");
    }
}
