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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
