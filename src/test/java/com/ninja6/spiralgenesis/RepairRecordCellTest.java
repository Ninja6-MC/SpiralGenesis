package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which cell a revalidation repair searches.
 *
 * <p>Always the record's own: its index on the centre it was allocated on, at the origin and
 * cell size recorded for that centre. The configured geometry describes whatever spiral new
 * plots grow on, which after {@code /sgen setcenter} or a reload with a new origin or cell
 * size is a different one, whose cell at the same index is usually another player's. A
 * point set by {@code /sgen setspawn} is on no spiral and is never searched around.
 */
class RepairRecordCellTest {

    /** The shipped {@code cell-size}. */
    private static final int CELL = 500;

    private ServerMock server;
    private WorldMock world;
    private final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        try {
            MockBukkit.unmock();
        } catch (UnimplementedOperationException e) {
            // Disabling the plugin cancels the storage flush task, which MockBukkit cannot
            // cancel; see RepairRespawnPointTest.
            MockBukkit.unmock();
        }
    }

    /** Records every cell a repair asks it to search, and finds nothing in any of them. */
    private static final class RecordingManager extends SpawnManager {

        private final List<SpiralCell> searched = new CopyOnWriteArrayList<>();

        private RecordingManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
        }

        @Override
        public CompletableFuture<LocationResult> findSafeSpawnInCell(SpiralCell cell) {
            searched.add(cell);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Replaces the bound manager; see RepairRespawnPointTest. */
    private static RecordingManager bindRecorder(SpiralGenesisPlugin plugin) {
        RecordingManager manager = new RecordingManager(plugin, plugin.getServer()
                .getWorld("world"), plugin.getPluginConfig());
        try {
            Field field = SpiralGenesisPlugin.class.getDeclaredField("spawnManager");
            field.setAccessible(true);
            field.set(plugin, manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return manager;
    }

    private SpiralGenesisPlugin load() {
        SpiralGenesisPlugin plugin = MockBukkit.load(SpiralGenesisPlugin.class);
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return plugin;
    }

    private InlinePlayerMock player() {
        InlinePlayerMock player = new InlinePlayerMock(server, "Bob");
        server.addPlayer(player);
        return player;
    }

    /** Runs the repair branch the respawn handler takes for a plot it judged unsafe. */
    private static void repair(SpiralGenesisPlugin plugin, InlinePlayerMock player) {
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record);
        plugin.repairSpawn(player, record, false);
    }

    /** What {@code /sgen setcenter x z} does to the configuration, and the next allocation. */
    private static void moveCentre(SpiralGenesisPlugin plugin, int x, int z) {
        plugin.getPluginConfig().setOriginX(x);
        plugin.getPluginConfig().setOriginZ(z);
        plugin.getDataStorage().centreFor(x, z, plugin.getPluginConfig().getCellSize());
    }

    @Test
    @DisplayName("after the centre moves, a repair searches the plot's own cell on its old centre")
    void repairAfterACentreMoveSearchesTheRecordedCell() {
        SpiralGenesisPlugin plugin = load();
        SpiralCentre original = plugin.getDataStorage().getCentre(0);
        assertEquals(new SpiralCentre(0, 0, 0, CELL), original,
                "enable records the configured centre");
        InlinePlayerMock player = player();
        SpiralCell owned = original.cell(3);
        Location plot = new Location(world, owned.centreX() + 0.5, 64, owned.centreZ() + 0.5);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), plot, 0, 3, owned.grid()[0],
                owned.grid()[1], "Bob", "JAVA", false);

        moveCentre(plugin, -1000, 0);
        RecordingManager manager = bindRecorder(plugin);
        repair(plugin, player);

        assertEquals(List.of(owned), manager.searched,
                "the search must be the record's cell, not index 3 of the moved spiral");
        assertEquals(owned.centreX(), manager.searched.get(0).centreX());
    }

    @Test
    @DisplayName("after a reload with a new cell size, a repair searches the plot's cell at its old size")
    void repairAfterACellSizeChangeSearchesTheRecordedCell() throws IOException {
        SpiralGenesisPlugin plugin = load();
        InlinePlayerMock player = player();
        SpiralCell owned = plugin.getDataStorage().getCentre(0).cell(2);
        Location plot = new Location(world, owned.centreX() + 0.5, 64, owned.centreZ() + 0.5);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), plot, 0, 2, owned.grid()[0],
                owned.grid()[1], "Bob", "JAVA", false);

        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("cell-size", 300);
        yaml.save(file);
        SpawnManager before = plugin.getSpawnManager();
        plugin.reload();
        assertEquals(300, plugin.getPluginConfig().getCellSize());
        // A reload binds a new manager, so no border exhaustion recorded by the old one
        // outlives a change of origin or cell size made through the file.
        assertNotSame(before, plugin.getSpawnManager());

        RecordingManager manager = bindRecorder(plugin);
        repair(plugin, player);

        assertEquals(List.of(owned), manager.searched);
        assertEquals(CELL, manager.searched.get(0).centre().cellSize(),
                "the cell is searched at the size it was allocated at");
    }

    @Test
    @DisplayName("a point set by setspawn is never searched around; it is kept and the player held")
    void setspawnPointIsNotSearched() {
        SpiralGenesisPlugin plugin = load();
        InlinePlayerMock player = player();
        Location point = new Location(world, 123.5, 70, -45.5);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), point, -1, 0, 0, "Bob", "JAVA");
        RecordingManager manager = bindRecorder(plugin);

        repair(plugin, player);

        assertTrue(manager.searched.isEmpty(),
                "a point on no spiral has no cell; searching one would pick another plot");
        StoredSpawn kept = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertEquals(-1, kept.index());
        assertEquals(123.5, kept.x(), 1e-9);
        assertEquals(-45.5, kept.z(), 1e-9);
        Location spawn = world.getSpawnLocation();
        assertEquals(spawn.getBlockX(), player.getLocation().getBlockX());
        assertEquals(spawn.getBlockZ(), player.getLocation().getBlockZ());
        assertTrue(logged.stream().anyMatch(line -> line.contains("/sgen setspawn")
                        && line.contains("no cell to search")),
                "the log must say why nothing was searched: " + logged);
    }

    @Test
    @DisplayName("a plot on a centre data.yml does not record is not searched; it is kept and the player held")
    void plotOnAnUnrecordedCentreIsNotSearched() {
        SpiralGenesisPlugin plugin = load();
        InlinePlayerMock player = player();
        Location plot = new Location(world, 800.5, 64, 300.5);
        // Centre 7 is in no centres table, as in a hand-edited file.
        plugin.getDataStorage().setSpawn(player.getUniqueId(), plot, 7, 2, 1, 1, "Bob", "JAVA",
                false);
        assertNull(plugin.getDataStorage().getCentre(7));
        RecordingManager manager = bindRecorder(plugin);

        repair(plugin, player);

        assertTrue(manager.searched.isEmpty(),
                "with no recorded geometry any cell searched would be a guess");
        StoredSpawn kept = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertEquals(7, kept.centre());
        assertEquals(2, kept.index());
        assertEquals(800.5, kept.x(), 1e-9);
        assertEquals(300.5, kept.z(), 1e-9);
        Location spawn = world.getSpawnLocation();
        assertEquals(spawn.getBlockX(), player.getLocation().getBlockX());
        assertEquals(spawn.getBlockZ(), player.getLocation().getBlockZ());
        assertTrue(logged.stream().anyMatch(line -> line.contains("spiral centre 7")
                        && line.contains("does not record")),
                "the log must say why nothing was searched: " + logged);
    }

    @Test
    @DisplayName("a plot from a file written before centres had ids is searched on centre 0 as first loaded")
    void legacyPlotIsSearchedOnCentreZero() throws IOException {
        SpiralGenesisPlugin plugin = load();
        InlinePlayerMock player = player();
        UUID uuid = player.getUniqueId();
        // Written straight to disk and read back as an older version left it: no centres
        // table and no centre key on the record, with the plot in cell 1 of the spiral at
        // the configured origin. save() is not called in between, so nothing overwrites it.
        Files.writeString(new File(plugin.getDataFolder(), "data.yml").toPath(),
                "current-spiral-index: 2\nplayers:\n"
                        + "  " + uuid + ":\n    name: Bob\n    assigned-index: 1\n"
                        + "    x: 500.5\n    y: 64.0\n    z: 0.5\n    world: world\n",
                StandardCharsets.UTF_8);
        plugin.getDataStorage().load();
        // As on enable: binding records centre 0 at the configured geometry.
        plugin.initSpawnManager();
        assertEquals(new SpiralCentre(0, 0, 0, CELL), plugin.getDataStorage().getCentre(0));

        moveCentre(plugin, 4000, 0);
        RecordingManager manager = bindRecorder(plugin);
        repair(plugin, player);

        SpiralCell expected = new SpiralCentre(0, 0, 0, CELL).cell(1);
        assertEquals(List.of(expected), manager.searched);
    }
}
