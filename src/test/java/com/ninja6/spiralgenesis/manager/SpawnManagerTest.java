package com.ninja6.spiralgenesis.manager;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation and terrain-filtering behaviour, exercised against a real {@link World}.
 *
 * <p>MockBukkit implements the terrain reads this engine depends on ({@code getBiome},
 * {@code getBlockAt}, heightmap lookups) but not Paper's {@code getChunkAtAsync} or the
 * region schedulers. {@link InlineSpawnManager} substitutes those two seams with immediate
 * execution, leaving the allocation logic itself untouched.
 *
 * <p>The mock world's default surface sits at y=4, so these fixtures lower
 * {@code min-surface-y} rather than raising terrain, except where the threshold is the
 * behaviour under test.
 */
class SpawnManagerTest {

    /** Default surface height of a MockBukkit world. */
    private static final int MOCK_SURFACE_Y = 4;
    private static final int CELL = 64;

    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Runs chunk loading and region hops inline so allocation resolves synchronously.
     */
    private static class InlineSpawnManager extends SpawnManager {
        InlineSpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
        }

        @Override
        CompletableFuture<?> loadChunk(int chunkX, int chunkZ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        void runOnRegion(CompletableFuture<LocationResult> result,
                         int chunkX, int chunkZ, Runnable action) {
            action.run();
        }

        @Override
        void runGlobally(CompletableFuture<LocationResult> result, Runnable action) {
            action.run();
        }
    }

    private PluginConfig config(int minSurfaceY, int maxScanAttempts) {
        String yaml = """
                origin:
                  world: "world"
                  x: 0
                  z: 0
                cell-size: %d
                safety:
                  min-surface-y: %d
                  max-scan-attempts: %d
                """.formatted(CELL, minSurfaceY, maxScanAttempts);
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    private SpawnManager managerWith(PluginConfig config) {
        return new InlineSpawnManager(plugin, world, config);
    }

    private SpawnManager.LocationResult allocate(SpawnManager manager, IntSupplier supplier)
            throws InterruptedException, ExecutionException, TimeoutException {
        return manager.allocateNextSafeSpawn(supplier).get(10, TimeUnit.SECONDS);
    }

    private static IntSupplier sequentialIndices(AtomicInteger counter) {
        return counter::getAndIncrement;
    }

    /** Marks the cell at the given grid coordinates as ocean. */
    private void makeOcean(int gridU, int gridV) {
        world.setBiome(gridU * CELL, gridV * CELL, Biome.OCEAN);
    }

    /** Puts a hazard block on the surface of the cell at the given grid coordinates. */
    private void makeHazard(int gridU, int gridV, Material hazard) {
        world.getBlockAt(gridU * CELL, MOCK_SURFACE_Y, gridV * CELL).setType(hazard);
    }

    @Test
    @DisplayName("The first player lands on the origin cell when it is already safe")
    void firstAllocationTakesOriginCell() throws Exception {
        SpawnManager manager = managerWith(config(0, 50));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(0, res.index());
        assertEquals(0, res.gridU());
        assertEquals(0, res.gridV());

        Location loc = res.location();
        // Centred within the block, standing one block above the surface.
        assertEquals(0.5, loc.getX(), 1e-9);
        assertEquals(0.5, loc.getZ(), 1e-9);
        assertEquals(MOCK_SURFACE_Y + 1.0, loc.getY(), 1e-9);
        assertEquals(1, indices.get(), "exactly one index should have been consumed");
    }

    @Test
    @DisplayName("An ocean cell is skipped and the spiral advances to the next index")
    void oceanCellIsSkipped() throws Exception {
        makeOcean(0, 0);

        SpawnManager manager = managerWith(config(0, 50));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(1, res.index(), "index 0 was ocean, so allocation should settle on index 1");
        assertEquals(1, res.gridU());
        assertEquals(0, res.gridV());
        assertEquals(CELL + 0.5, res.location().getX(), 1e-9);
        assertEquals(2, indices.get(), "one index per probe, including the rejected one");
    }

    @Test
    @DisplayName("A water block on the surface is rejected even outside an ocean biome")
    void inlandWaterIsRejected() throws Exception {
        // Biome stays PLAINS: this is the gate the biome check alone would miss.
        makeHazard(0, 0, Material.WATER);

        SpawnManager manager = managerWith(config(0, 50));

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(new AtomicInteger()));

        assertEquals(1, res.index());
    }

    @Test
    @DisplayName("Lava on the surface is rejected")
    void lavaIsRejected() throws Exception {
        makeHazard(0, 0, Material.LAVA);

        SpawnManager manager = managerWith(config(0, 50));

        assertEquals(1, allocate(manager, sequentialIndices(new AtomicInteger())).index());
    }

    @Test
    @DisplayName("A surface below min-surface-y is rejected")
    void surfaceBelowThresholdIsRejected() throws Exception {
        // Default surface is y=4; require y>=10 so the origin cell fails on height alone.
        // Raise the next cell above the threshold so allocation has somewhere to land.
        world.getBlockAt(CELL, 12, 0).setType(Material.STONE);

        SpawnManager manager = managerWith(config(10, 50));

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(new AtomicInteger()));

        assertEquals(1, res.index());
        assertEquals(13.0, res.location().getY(), 1e-9, "should stand on the raised surface");
    }

    @Test
    @DisplayName("Exhausting max-scan-attempts falls back to the last cell's real surface")
    void fallbackAfterExhaustingAttempts() throws Exception {
        // Every candidate the spiral can reach within the budget is ocean.
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                makeOcean(u, v);
            }
        }

        int budget = 4;
        SpawnManager manager = managerWith(config(0, budget));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(budget, indices.get(), "should consume exactly the configured budget");
        assertEquals(budget - 1, res.index(), "settles on the final candidate rather than failing");
        // Falls back to the cell's actual surface, never a hardcoded altitude.
        assertEquals(MOCK_SURFACE_Y + 1.0, res.location().getY(), 1e-9);
    }

    @Test
    @DisplayName("Every probe claims a fresh index so concurrent allocations cannot collide")
    void eachProbeClaimsItsOwnIndex() throws Exception {
        makeOcean(0, 0);
        makeOcean(1, 0);
        makeOcean(1, 1);

        SpawnManager manager = managerWith(config(0, 50));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(3, res.index(), "first three cells were ocean");
        assertEquals(4, indices.get(), "four probes, four indices claimed");
        assertTrue(res.index() < indices.get(), "the claimed index is never handed out twice");
    }

    @Test
    @DisplayName("A non-zero origin offsets allocated coordinates")
    void originOffsetIsApplied() throws Exception {
        String yaml = """
                origin:
                  world: "world"
                  x: 1000
                  z: -500
                cell-size: %d
                safety:
                  min-surface-y: 0
                  max-scan-attempts: 50
                """.formatted(CELL);
        PluginConfig config = new PluginConfig(
                YamlConfiguration.loadConfiguration(new StringReader(yaml)));

        SpawnManager manager = managerWith(config);

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertEquals(1000.5, loc.getX(), 1e-9);
        assertEquals(-499.5, loc.getZ(), 1e-9);
    }
}
