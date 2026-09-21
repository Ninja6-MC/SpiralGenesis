package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Whose respawn point a revalidation repair may move.
 *
 * <p>The repaired plot is always recorded, but the player's respawn point follows it only
 * when it is unset or is the plot being repaired. A bed, an anchor or a point forced
 * elsewhere is left alone, and so is the player: they respawn there, not at the plot.
 * Driven through {@code repairSpawn} rather than the predicate alone, so the ordering
 * (the point is read before it is written) is covered as well.
 */
class RepairRespawnPointTest {

    private ServerMock server;
    private WorldMock world;

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
            // cancel. Swallowed for the reason AllocationOwnershipTest documents: the
            // exception extends TestAbortedException, so letting it out of teardown reports
            // every test in the class as skipped rather than run.
            MockBukkit.unmock();
        }
    }

    /** A manager whose in-cell search answers at once with a fixed replacement point. */
    private static final class RepairingManager extends SpawnManager {

        private final Location replacement;

        private RepairingManager(JavaPlugin plugin, World world, PluginConfig config,
                                 Location replacement) {
            super(plugin, world, config);
            this.replacement = replacement;
        }

        @Override
        public CompletableFuture<LocationResult> findSafeSpawnInCell(int index) {
            return CompletableFuture.completedFuture(new LocationResult(
                    replacement, index, 0, 0, 63, 1, 1, false, Map.of()));
        }
    }

    /**
     * Replaces the bound manager. The field has no setter, and adding one to production
     * code for a test would widen the class every reload path has to reason about.
     */
    private static void bind(SpiralGenesisPlugin plugin, SpawnManager manager) {
        try {
            Field field = SpiralGenesisPlugin.class.getDeclaredField("spawnManager");
            field.setAccessible(true);
            field.set(plugin, manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The plot as recorded before the repair. */
    private Location plot() {
        return new Location(world, 10.5, 64, 10.5);
    }

    /** Where the in-cell search puts the repaired plot. */
    private Location repaired() {
        return new Location(world, 44.5, 66, 44.5);
    }

    private SpiralGenesisPlugin load() {
        SpiralGenesisPlugin plugin = MockBukkit.load(SpiralGenesisPlugin.class);
        bind(plugin, new RepairingManager(plugin, world, plugin.getPluginConfig(), repaired()));
        return plugin;
    }

    private InlinePlayerMock join(SpiralGenesisPlugin plugin, Location respawnPoint) {
        InlinePlayerMock player = new InlinePlayerMock(server, "Bob");
        server.addPlayer(player);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), plot(), 3, 0, 0, "Bob", "JAVA");
        player.setRespawnLocation(respawnPoint, true);
        return player;
    }

    /** Runs the repair branch the respawn handler takes for a plot it judged unsafe. */
    private void repair(SpiralGenesisPlugin plugin, InlinePlayerMock player) {
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record);
        plugin.repairSpawn(player, record, false);
    }

    private static void assertSameBlock(Location expected, Location actual) {
        assertNotNull(actual, "expected a location at " + expected);
        assertEquals(expected.getWorld(), actual.getWorld());
        assertEquals(expected.getBlockX(), actual.getBlockX());
        assertEquals(expected.getBlockY(), actual.getBlockY());
        assertEquals(expected.getBlockZ(), actual.getBlockZ());
    }

    private void assertRecordedAtRepairedPlot(SpiralGenesisPlugin plugin, InlinePlayerMock player) {
        assertSameBlock(repaired(),
                plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation());
    }

    @Test
    @DisplayName("a player with no respawn point is given the repaired plot")
    void unsetPointIsMoved() {
        SpiralGenesisPlugin plugin = load();
        InlinePlayerMock player = join(plugin, null);

        repair(plugin, player);

        assertRecordedAtRepairedPlot(plugin, player);
        assertSameBlock(repaired(), player.respawnPoint);
        assertSameBlock(repaired(), player.getLocation());
    }

    @Test
    @DisplayName("a respawn point on the old plot moves to the repaired plot")
    void oldPlotIsMoved() {
        SpiralGenesisPlugin plugin = load();
        InlinePlayerMock player = join(plugin, plot());

        repair(plugin, player);

        assertRecordedAtRepairedPlot(plugin, player);
        assertSameBlock(repaired(), player.respawnPoint);
        assertSameBlock(repaired(), player.getLocation());
    }

    @Test
    @DisplayName("a respawn point above the old plot, in the same column, moves to the repaired plot")
    void oldPlotColumnIsMoved() {
        SpiralGenesisPlugin plugin = load();
        // A plot that was built over places the player on top of the build.
        InlinePlayerMock player = join(plugin, plot().add(0, 3, 0));

        repair(plugin, player);

        assertRecordedAtRepairedPlot(plugin, player);
        assertSameBlock(repaired(), player.respawnPoint);
        assertSameBlock(repaired(), player.getLocation());
    }

    @Test
    @DisplayName("a bed elsewhere is kept, and the player is not moved, while the plot is still recorded")
    void bedElsewhereIsKept() {
        SpiralGenesisPlugin plugin = load();
        Location bed = new Location(world, 200, 70, 200);
        InlinePlayerMock player = join(plugin, bed);
        Location standing = player.getLocation();

        repair(plugin, player);

        assertRecordedAtRepairedPlot(plugin, player);
        assertSameBlock(bed, player.respawnPoint);
        assertSameBlock(standing, player.getLocation());
    }

    @Test
    @DisplayName("a respawn point at the plot's coordinates in another world is kept")
    void otherWorldIsKept() {
        SpiralGenesisPlugin plugin = load();
        WorldMock nether = server.addSimpleWorld("world_nether");
        Location anchor = new Location(nether, 10.5, 64, 10.5);
        InlinePlayerMock player = join(plugin, anchor);
        Location standing = player.getLocation();

        repair(plugin, player);

        assertRecordedAtRepairedPlot(plugin, player);
        assertSameBlock(anchor, player.respawnPoint);
        assertSameBlock(standing, player.getLocation());
    }
}
