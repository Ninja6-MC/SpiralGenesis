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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
