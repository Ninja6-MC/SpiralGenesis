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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code structures} table of {@code data.yml}, and its compatibility with the format
 * frozen at 1.0.0-beta.1: that version's loader neither reads the table nor drops it, since
 * it saves the whole file it loaded, which is the behaviour pinned here for unknown keys.
 */
class StructureStorageTest {

    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";

    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;
    private Path dataFile;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
        dataFile = plugin.getDataFolder().toPath().resolve("data.yml");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private YamlDataStorage load() {
        YamlDataStorage storage = new YamlDataStorage(plugin);
        storage.load();
        return storage;
    }

    private void write(String text) throws IOException {
        Files.createDirectories(dataFile.getParent());
        Files.writeString(dataFile, text);
    }

    @Test
    @DisplayName("a 1.0.0-beta.1 file loads with no structures")
    void beta1File() throws IOException {
        write("""
                installed-at: '2026-10-10T00:00:00Z'
                current-spiral-index: 1
                players:
                  %s:
                    name: Old
                    client: JAVA
                    centre: 0
                    assigned-index: 0
                    grid-u: 0
                    grid-v: 0
                    x: 0.5
                    y: 64.0
                    z: 0.5
                    world: world
                    assigned-date: '2026-10-10T00:00:00Z'
                """.formatted(UUID_A));
        YamlDataStorage storage = load();

        assertTrue(storage.hasSpawn(UUID.fromString(UUID_A)));
        assertNull(storage.getStructure(0, 0));
    }

    @Test
    @DisplayName("structures and keys this version does not know survive every write")
    void tableAndUnknownKeysSurvive() throws IOException {
        write("""
                installed-at: '2026-10-10T00:00:00Z'
                some-later-key:
                  kept: true
                structures:
                  '0':
                    '3':
                      file: spawn-hut.nbt
                      world: world
                      x: -3
                      y: 63
                      z: 7
                      rotation: NONE
                      placed-at: '2026-10-20T00:00:00Z'
                      player: Old
                      some-later-field: 9
                    'bad':
                      file: x
                players: {}
                """);
        YamlDataStorage storage = load();
        PlacedStructure placed = storage.getStructure(0, 3);
        assertEquals(new PlacedStructure("spawn-hut.nbt", "world", -3, 63, 7, "NONE",
                "2026-10-20T00:00:00Z", "Old"), placed);

        UUID player = UUID.randomUUID();
        storage.setSpawn(player, new Location(world, 0.5, 64, 0.5), 0, 4, 0, 0, "New", "JAVA", false);
        storage.removeSpawn(player);
        assertTrue(storage.recordStructure(1, 0, new PlacedStructure("spawn-tower.nbt", "world",
                1, 2, 3, "CLOCKWISE_90", "2026-10-20T01:00:00Z", "New")));
        storage.save();

        YamlConfiguration saved = YamlConfiguration.loadConfiguration(dataFile.toFile());
        assertTrue(saved.getBoolean("some-later-key.kept"));
        assertEquals(9, saved.getInt("structures.0.3.some-later-field"));
        assertEquals("x", saved.getString("structures.0.bad.file"), "an unreadable entry is kept");
        assertEquals("spawn-tower.nbt", saved.getString("structures.1.0.file"));
        assertEquals("CLOCKWISE_90", saved.getString("structures.1.0.rotation"));

        YamlDataStorage restarted = load();
        assertEquals(placed, restarted.getStructure(0, 3));
        assertEquals("spawn-tower.nbt", restarted.getStructure(1, 0).file());
        assertNull(restarted.getStructure(0, 4), "a plot's structure is its own, not its player's");
    }

    @Test
    @DisplayName("a structure is refused while storage is failed")
    void refusedWhileFailed() throws IOException {
        write("players: [unclosed");
        YamlDataStorage storage = load();

        assertFalse(storage.recordStructure(0, 0, new PlacedStructure("a.nbt", "world", 0, 0, 0,
                "NONE", "t", "p")));
        assertNull(storage.getStructure(0, 0));
    }
}
