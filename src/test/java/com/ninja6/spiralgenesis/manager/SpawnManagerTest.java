package com.ninja6.spiralgenesis.manager;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.math.SpiralMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation and terrain-filtering behaviour, exercised against a real {@link World}.
 *
 * <p>MockBukkit implements the terrain reads this engine depends on ({@code getBiome},
 * {@code getBlockAt}, heightmap lookups) but not Paper's {@code getChunkAtAsync}, chunk
 * residency, or the region schedulers. {@link InlineSpawnManager} substitutes those seams
 * with immediate execution, leaving the allocation logic itself untouched.
 *
 * <p>The mock world's default surface sits at y=4, so these fixtures lower
 * {@code min-surface-y} rather than raising terrain, except where the threshold is the
 * behaviour under test.
 */
class SpawnManagerTest {

    /** Default surface height of a MockBukkit world. */
    private static final int MOCK_SURFACE_Y = 4;
    private static final int CELL = 64;
    private static final int STRIDE = 16;

    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;
    /** Vanilla collision for what a test places; see {@link BlockShapes}. */
    private final BlockShapes shapes = new BlockShapes();

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
     * Runs chunk loading and region hops inline so allocation resolves synchronously, and
     * treats all terrain as readable so the shape checks are always exercised.
     *
     * <p>Failures are pushed into the pending result rather than left to unwind: an
     * exception escaping an inline hop would otherwise be swallowed by the surrounding
     * {@code whenComplete}, turning any bug into an unexplained timeout.
     */
    private static class InlineSpawnManager extends SpawnManager {

        /** Counts what a real server would have generated, for the tests that care. */
        private final AtomicInteger chunkLoads = new AtomicInteger();

        /** Collision for each block, since MockBukkit models none. */
        private final BlockShapes shapes;

        InlineSpawnManager(JavaPlugin plugin, World world, PluginConfig config,
                           BlockShapes shapes) {
            super(plugin, world, config);
            this.shapes = shapes;
        }

        @Override
        CompletableFuture<?> loadChunk(int chunkX, int chunkZ) {
            chunkLoads.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        /** MockBukkit's {@code Block.getCollisionShape()} throws, so answer from the table. */
        @Override
        VoxelShape collisionShape(Block block) {
            return shapes.shapeOf(block);
        }

        /** MockBukkit's {@code Block.isPassable()} throws, so answer from the table. */
        @Override
        boolean isPassable(Block block) {
            return shapes.isPassable(block);
        }

        /** MockBukkit does not answer {@code isBuildable()} from block state either. */
        @Override
        boolean admitsRespawn(Block block) {
            return shapes.admitsRespawn(block);
        }

        @Override
        void runOnRegion(CompletableFuture<?> result,
                         int chunkX, int chunkZ, Runnable action) {
            runInline(result, action);
        }

        @Override
        void runGlobally(CompletableFuture<?> result, Runnable action) {
            runInline(result, action);
        }

        private static void runInline(CompletableFuture<?> result, Runnable action) {
            try {
                action.run();
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }
    }

    /**
     * Reports one column as blocked overhead.
     *
     * <p>The headroom rule fires on leaves above a {@code MOTION_BLOCKING_NO_LEAVES}
     * surface, which the mock's heightmap cannot express — any block placed above the
     * surface simply becomes the new surface. Overriding the seam tests that a blocked
     * column is actually rejected, which is the part of the rule this suite can reach.
     */
    private static class BlockedHeadroomManager extends InlineSpawnManager {
        private final int blockedX;
        private final int blockedZ;

        BlockedHeadroomManager(JavaPlugin plugin, World world, PluginConfig config,
                               BlockShapes shapes, int blockedX, int blockedZ) {
            super(plugin, world, config, shapes);
            this.blockedX = blockedX;
            this.blockedZ = blockedZ;
        }

        @Override
        boolean isPassable(Block block) {
            if (block.getX() == blockedX && block.getZ() == blockedZ) {
                return false;
            }
            return super.isPassable(block);
        }
    }

    private PluginConfig config(int minSurfaceY, int maxScanAttempts) {
        return config(minSurfaceY, maxScanAttempts, "FIRST_SAFE");
    }

    private PluginConfig config(int minSurfaceY, int maxScanAttempts, String strategy) {
        String yaml = """
                origin:
                  world: "world"
                  x: 0
                  z: 0
                cell-size: %d
                placement:
                  strategy: %s
                  stride: %d
                  max-candidates: 12
                  height-ceiling: 110
                safety:
                  min-surface-y: %d
                  max-scan-attempts: %d
                """.formatted(CELL, strategy, STRIDE, minSurfaceY, maxScanAttempts);
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    private SpawnManager managerWith(PluginConfig config) {
        return new InlineSpawnManager(plugin, world, config, shapes);
    }

    private SpawnManager.LocationResult allocate(SpawnManager manager, IntSupplier supplier)
            throws InterruptedException, ExecutionException, TimeoutException {
        return assertInstanceOf(SpawnManager.LocationResult.class,
                manager.allocateNextSafeSpawn(supplier).get(10, TimeUnit.SECONDS));
    }

    private static IntSupplier sequentialIndices(AtomicInteger counter) {
        return counter::getAndIncrement;
    }

    /**
     * Marks every candidate column of a cell as ocean, so the whole cell is unusable
     * rather than just its centre.
     *
     * <p>With {@code CELL}=64 and {@code STRIDE}=16 the in-cell search reaches two rings
     * out, so the candidates are exactly the 5x5 stride grid around the cell centre.
     */
    private void makeCellOcean(int gridU, int gridV) {
        for (int dx = -2 * STRIDE; dx <= 2 * STRIDE; dx += STRIDE) {
            for (int dz = -2 * STRIDE; dz <= 2 * STRIDE; dz += STRIDE) {
                world.setBiome(gridU * CELL + dx, gridV * CELL + dz, Biome.OCEAN);
            }
        }
    }

    /** Puts a hazard block on the surface at the centre of the given cell. */
    private void makeHazard(int gridU, int gridV, Material hazard) {
        world.getBlockAt(gridU * CELL, MOCK_SURFACE_Y, gridV * CELL).setType(hazard);
    }

    @Test
    @DisplayName("The first player lands on the origin cell's centre when it is already safe")
    void firstAllocationTakesOriginCell() throws Exception {
        SpawnManager manager = managerWith(config(0, 8));
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
    @DisplayName("A hazard at the cell centre is answered from within the same cell")
    void hazardAtCentreIsSolvedWithoutBurningTheCell() throws Exception {
        makeHazard(0, 0, Material.LAVA);

        SpawnManager manager = managerWith(config(0, 8));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(0, res.index(), "one bad block must not discard the whole cell");
        assertEquals(1, indices.get(), "no spiral index should be burned");
        assertNotEquals(0.5, res.location().getX(), "should have moved off the centre");
    }

    @Test
    @DisplayName("A fully unusable cell is skipped and the spiral advances to the next index")
    void oceanCellIsSkipped() throws Exception {
        makeCellOcean(0, 0);

        SpawnManager manager = managerWith(config(0, 8));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(1, res.index(), "cell 0 was all ocean, so allocation should settle on index 1");
        assertEquals(1, res.gridU());
        assertEquals(0, res.gridV());
        assertEquals(CELL + 0.5, res.location().getX(), 1e-9);
        assertEquals(2, indices.get(), "one index per cell, including the rejected one");
    }

    @Test
    @DisplayName("A water block on the surface is rejected even outside an ocean biome")
    void inlandWaterIsRejected() throws Exception {
        // Biome stays PLAINS: this is the gate the biome check alone would miss.
        makeHazard(0, 0, Material.WATER);

        SpawnManager manager = managerWith(config(0, 8));

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertNotEquals(0.5, loc.getX(), "the water column itself must not be chosen");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {"ICE", "PACKED_ICE", "BLUE_ICE"})
    @DisplayName("An ice surface is rejected for a new plot")
    void iceSurfaceIsRejected(Material ice) throws Exception {
        // Re-checking a stored plot accepts ice, so this is the only rule that still keys
        // on it: allocation wants dry land, and a frozen lake is not that.
        makeHazard(0, 0, ice);

        SpawnManager manager = managerWith(config(0, 8));

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertNotEquals(0.5, loc.getX(), "the ice column itself must not be chosen");
    }

    @Test
    @DisplayName("A candidate sitting below the surrounding terrain is rejected as a ravine")
    void pitCandidateIsRejected() throws Exception {
        // Surroundings 9 blocks above the candidate: deeper than max-pit-depth (8), but a
        // spread of 9 stays inside max-roughness (12), so the pit rule alone is what fires.
        raiseTerrainAround(0, 0, MOCK_SURFACE_Y + 9);

        SpawnManager manager = managerWith(config(0, 8));

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertNotEquals(0.5, loc.getX(), "a point in a hole must not be chosen");
    }

    @Test
    @DisplayName("Lava beside a candidate disqualifies it even though the column itself is clear")
    void adjacentLavaIsRejected() throws Exception {
        world.getBlockAt(2, MOCK_SURFACE_Y, 2).setType(Material.LAVA);

        SpawnManager manager = managerWith(config(0, 8));

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertNotEquals(0.5, loc.getX(), "should not spawn next to a lava pool");
    }

    @Test
    @DisplayName("A column without headroom is rejected even though its surface is safe")
    void missingHeadroomIsRejected() throws Exception {
        SpawnManager manager = new BlockedHeadroomManager(plugin, world, config(0, 8), shapes, 0, 0);

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertNotEquals(0.5, loc.getX(), "a player must not be placed where they do not fit");
    }

    @Test
    @DisplayName("A surface below min-surface-y is rejected")
    void surfaceBelowThresholdIsRejected() throws Exception {
        // Default surface is y=4; require y>=10 so the origin cell fails on height alone.
        // Raise the next cell's centre above the threshold so allocation has somewhere to land.
        world.getBlockAt(CELL, 12, 0).setType(Material.STONE);

        SpawnManager manager = managerWith(config(10, 8));

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(new AtomicInteger()));

        assertEquals(1, res.index());
        assertEquals(13.0, res.location().getY(), 1e-9, "should stand on the raised surface");
    }

    @Test
    @DisplayName("HIGHEST prefers a raised candidate over the cell centre")
    void highestStrategyPrefersElevatedGround() throws Exception {
        // A gentle rise one stride east: 6 blocks up keeps roughness within budget.
        world.getBlockAt(STRIDE, MOCK_SURFACE_Y + 6, 0).setType(Material.STONE);

        SpawnManager manager = managerWith(config(0, 8, "HIGHEST"));

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertEquals(STRIDE + 0.5, loc.getX(), 1e-9);
        assertEquals(MOCK_SURFACE_Y + 7.0, loc.getY(), 1e-9);
    }

    @Test
    @DisplayName("HIGHEST ignores candidates above the height ceiling")
    void highestStrategyRespectsTheCeiling() throws Exception {
        String yaml = """
                origin:
                  world: "world"
                  x: 0
                  z: 0
                cell-size: %d
                placement:
                  strategy: HIGHEST
                  stride: %d
                  max-candidates: 12
                  height-ceiling: 8
                safety:
                  min-surface-y: 0
                  max-scan-attempts: 8
                  max-roughness: 128
                """.formatted(CELL, STRIDE);
        PluginConfig config = new PluginConfig(
                YamlConfiguration.loadConfiguration(new StringReader(yaml)));

        // A spike well above the ceiling, and a modest rise below it.
        world.getBlockAt(STRIDE, 40, 0).setType(Material.STONE);
        world.getBlockAt(0, 7, STRIDE).setType(Material.STONE);

        Location loc = allocate(managerWith(config), sequentialIndices(new AtomicInteger())).location();

        assertEquals(8.0, loc.getY(), 1e-9, "should take the rise under the ceiling, not the spike");
        assertEquals(STRIDE + 0.5, loc.getZ(), 1e-9);
    }

    @Test
    @DisplayName("Exhausting max-scan-attempts falls back to the best candidate seen, not the last")
    void fallbackAfterExhaustingAttempts() throws Exception {
        // Every cell the spiral can reach within the budget is ocean throughout.
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                makeCellOcean(u, v);
            }
        }

        int budget = 4;
        SpawnManager manager = managerWith(config(0, budget));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(budget, indices.get(), "should consume exactly one index per cell");
        assertEquals(0, res.index(),
                "all candidates score alike, so the earliest and most central one wins");
        // Falls back to a real surface, never a hardcoded altitude.
        assertEquals(MOCK_SURFACE_Y + 1.0, res.location().getY(), 1e-9);
    }

    @Test
    @DisplayName("The fallback prefers a merely imperfect cell over a wholly unsafe one")
    void fallbackRanksCandidatesByBadness() throws Exception {
        // Cell 0 is unusable outright; cell 1 is safe underfoot but sits in a deep pit,
        // so it is rejected yet still scores better than open ocean.
        makeCellOcean(0, 0);
        for (int dx = -2 * STRIDE; dx <= 2 * STRIDE; dx += STRIDE) {
            for (int dz = -2 * STRIDE; dz <= 2 * STRIDE; dz += STRIDE) {
                raiseTerrainAround(CELL + dx, dz, MOCK_SURFACE_Y + 9);
            }
        }

        SpawnManager manager = managerWith(config(0, 2));

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(new AtomicInteger()));

        assertEquals(1, res.index(), "the pit cell outranks the ocean cell as a fallback");
    }

    @Test
    @DisplayName("Every cell claims a fresh index so concurrent allocations cannot collide")
    void eachCellClaimsItsOwnIndex() throws Exception {
        makeCellOcean(0, 0);
        makeCellOcean(1, 0);
        makeCellOcean(1, 1);

        SpawnManager manager = managerWith(config(0, 8));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(3, res.index(), "first three cells were ocean");
        assertEquals(4, indices.get(), "four cells, four indices claimed");
        assertTrue(res.index() < indices.get(), "the claimed index is never handed out twice");
    }

    @Test
    @DisplayName("The in-cell search never leaves the cell it was allocated")
    void candidatesStayWithinTheCell() throws Exception {
        makeHazard(0, 0, Material.LAVA);

        SpawnManager manager = managerWith(config(0, 8));

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        // The furthest candidate sits on the cell edge, and locations are block-centred.
        double bound = CELL / 2.0 + 0.5;
        assertTrue(Math.abs(loc.getX()) <= bound, "x drifted outside the cell: " + loc.getX());
        assertTrue(Math.abs(loc.getZ()) <= bound, "z drifted outside the cell: " + loc.getZ());
    }

    @Test
    @DisplayName("Pit detection fires for a candidate in the far corner of its chunk")
    void pitDetectionIsIndependentOfChunkAlignment() throws Exception {
        // Regression guard. Sampling a fixed radius around the candidate put most samples
        // in neighbouring chunks for a candidate this close to a chunk edge; those chunks
        // are not resident, so the terrain-shape rules silently did nothing and a player
        // could be dropped into a ravine. Only the candidate's own chunk is guaranteed.
        String yaml = """
                origin:
                  world: "world"
                  x: 15
                  z: 15
                cell-size: %d
                safety:
                  min-surface-y: 0
                  max-scan-attempts: 8
                """.formatted(CELL);
        PluginConfig config = new PluginConfig(
                YamlConfiguration.loadConfiguration(new StringReader(yaml)));

        raiseTerrainAround(15, 15, MOCK_SURFACE_Y + 9);

        Location loc = allocate(managerWith(config), sequentialIndices(new AtomicInteger())).location();

        assertNotEquals(15.5, loc.getX(), "a point in a hole must not be chosen");
    }

    @Test
    @DisplayName("Simulation reports one index per spawn on unobstructed terrain")
    void simulationReportsCleanRun() throws Exception {
        SpawnManager manager = managerWith(config(0, 8));

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 5).get(10, TimeUnit.SECONDS);

        assertEquals(5, report.samples());
        assertEquals(5, report.completed());
        assertEquals(5, report.cellsProbed(), "a viable centre should cost exactly one index each");
        assertEquals(1.0, report.indicesPerSpawn(), 1e-9);
        assertEquals(0, report.fallbacks());
        assertTrue(report.rejections().isEmpty(), "nothing should have been rejected");
    }

    @Test
    @DisplayName("The simulation summary keeps the field names CI asserts on")
    void simulationSummaryFormatIsStable() throws Exception {
        SpawnManager manager = managerWith(config(0, 8));

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 2).get(10, TimeUnit.SECONDS);
        String line = report.toSummaryLine();

        // .github/scripts/smoke-test.sh greps these keys out of the server log and asserts
        // on them. Renaming one here without updating that script would leave CI silently
        // asserting nothing, so the contract is pinned from this side.
        assertTrue(line.startsWith("SIMULATE samples="), line);
        for (String key : new String[]{"samples=", "completed=", "indices=", "ratio=",
                "candidates=", "fallbacks=", "minY=", "maxY=", "exhausted="}) {
            assertTrue(line.contains(" " + key) || line.startsWith(key),
                    "summary line lost the '" + key + "' field that CI parses: " + line);
        }
        assertTrue(report.toRejectionLine().startsWith("SIMULATE rejections"),
                "the smoke test waits on this prefix to know the run finished");
    }

    @Test
    @DisplayName("The summary ratio uses a decimal point regardless of the server's locale")
    void simulationSummaryIsLocaleIndependent() throws Exception {
        Locale original = Locale.getDefault();
        try {
            // A comma-decimal locale would render the ratio as "1,00". CI parses that field
            // with awk, which coerces "3,50" to 3 and would wave a real regression through.
            Locale.setDefault(Locale.GERMANY);

            SpawnManager manager = managerWith(config(0, 8));
            SpawnSimulator.Report report = SpawnSimulator.run(manager, 2).get(10, TimeUnit.SECONDS);

            assertTrue(report.toSummaryLine().contains("ratio=1.00"),
                    "ratio must be machine-parseable under any locale: " + report.toSummaryLine());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("Simulation attributes rejections to the rule that caused them")
    void simulationAttributesRejections() throws Exception {
        makeCellOcean(0, 0);
        SpawnManager manager = managerWith(config(0, 8));

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 1).get(10, TimeUnit.SECONDS);

        assertEquals(1, report.completed());
        assertTrue(report.cellsProbed() > 1, "the unusable cell should have cost an extra index");
        assertTrue(report.rejections().containsKey(RejectionReason.OCEAN),
                "the ocean rule should be named as the cause, got " + report.rejections());
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
                  max-scan-attempts: 8
                """.formatted(CELL);
        PluginConfig config = new PluginConfig(
                YamlConfiguration.loadConfiguration(new StringReader(yaml)));

        SpawnManager manager = managerWith(config);

        Location loc = allocate(manager, sequentialIndices(new AtomicInteger())).location();

        assertEquals(1000.5, loc.getX(), 1e-9);
        assertEquals(-499.5, loc.getZ(), 1e-9);
    }

    // --- Revalidation of a plot that was safe when it was allocated -------------------

    /**
     * The point a fresh allocation of the origin cell's centre produces, with its chunk
     * resident.
     *
     * <p>Loading it explicitly is the whole point: MockBukkit answers block reads from a
     * lazily built store without ever marking a chunk loaded, so an unloaded plot is the
     * default here and residency has to be arranged rather than assumed. Verified by
     * decompiling {@code WorldMock}, and by these tests reporting UNVERIFIED before the
     * load call was added.
     */
    private Location originCentreSpawn() {
        world.loadChunk(0, 0);
        return new Location(world, 0.5, MOCK_SURFACE_Y + 1.0, 0.5);
    }

    @Test
    @DisplayName("An untouched plot still verifies as usable")
    void untouchedPlotIsStillUsable() {
        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.USABLE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @Test
    @DisplayName("A plot flooded after allocation no longer verifies")
    void floodedPlotIsUnsafe() {
        // Lava poured where the owner stands: the case the whole revalidation exists for.
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.LAVA);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @Test
    @DisplayName("A plot whose ground was dug out no longer verifies")
    void hollowedPlotIsUnsafe() {
        // Two deep: one block down is a step, and still has a floor.
        world.getBlockAt(0, MOCK_SURFACE_Y, 0).setType(Material.AIR);
        world.getBlockAt(0, MOCK_SURFACE_Y - 1, 0).setType(Material.AIR);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {
            "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE"})
    @DisplayName("A floor that hurts is caught even when the column itself is clear")
    void hazardousGroundIsUnsafe(Material floor) {
        // Each collides over the column centre, so it is the floor on a real server as well
        // and it is the material rule that has to fire. Water or lava replacing the floor
        // has no collision and reads as a step down; floodedStepIsUnsafe covers that path.
        world.getBlockAt(0, MOCK_SURFACE_Y, 0).setType(floor);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @Test
    @DisplayName("Water at head height still fails the re-check")
    void floodedHeadIsUnsafe() {
        world.getBlockAt(0, MOCK_SURFACE_Y + 2, 0).setType(Material.WATER);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    /*
     * What a player plausibly builds on the point they were given. Doors, trapdoors, slabs
     * and beds are here on purpose: Block.isPassable() reports them impassable although a
     * player stands on or walks through them, which is what the old check keyed on. The
     * fixture gives each its vanilla collision (BlockShapes), so that is what is tested.
     */

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {
            "CHEST", "CRAFTING_TABLE", "WHITE_BED", "OAK_DOOR", "OAK_TRAPDOOR", "OAK_SLAB",
            "COBBLESTONE", "PACKED_ICE"})
    @DisplayName("A block the owner placed at their feet does not fail the re-check")
    void ownBlockAtFeetKeepsThePlot(Material placed) {
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(placed);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.USABLE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {
            "CHEST", "CRAFTING_TABLE", "WHITE_BED", "OAK_DOOR", "OAK_TRAPDOOR", "OAK_SLAB",
            "COBBLESTONE", "PACKED_ICE"})
    @DisplayName("A block the owner placed at head height does not fail the re-check")
    void ownBlockAtHeadKeepsThePlot(Material placed) {
        world.getBlockAt(0, MOCK_SURFACE_Y + 2, 0).setType(placed);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.USABLE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {
            "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE"})
    @DisplayName("A block that hurts, placed at the feet, fails the re-check")
    void harmfulBlockAtFeetIsUnsafe(Material placed) {
        // Each is solid, so a respawn lifts the player onto it: a griefer's way to hurt
        // the owner on every death if the re-check let it through.
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(placed);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {
            "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE"})
    @DisplayName("A block that hurts, placed at head height, fails the re-check")
    void harmfulBlockAtHeadIsUnsafe(Material placed) {
        world.getBlockAt(0, MOCK_SURFACE_Y + 2, 0).setType(placed);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @Test
    @DisplayName("Pointed dripstone on the spawn does not fail the re-check")
    void pointedDripstoneKeepsThePlot() {
        // It only hurts through a fall, and a respawn does not drop the player onto it.
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.POINTED_DRIPSTONE);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.USABLE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {"PACKED_ICE", "BLUE_ICE", "ICE"})
    @DisplayName("An ice floor laid over the spawn does not fail the re-check")
    void iceFloorKeepsThePlot(Material floor) {
        // Allocation rejects ice as a surface because it wants dry land. Nothing about it
        // hurts a player standing on it, so a floor the owner lays later is no reason to
        // move them.
        world.getBlockAt(0, MOCK_SURFACE_Y, 0).setType(floor);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.USABLE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @Test
    @DisplayName("The asynchronous re-check the death repair uses keeps a built-over plot")
    void asyncRevalidateKeepsABuiltOverPlot() throws Exception {
        // repairSpawn rewrites the stored point exactly when this answers false, so this is
        // the answer that decides whether the owner is moved off their build.
        Location stored = new Location(world, 0.5, MOCK_SURFACE_Y + 1.0, 0.5);
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.CHEST);
        world.getBlockAt(0, MOCK_SURFACE_Y + 2, 0).setType(Material.OAK_SLAB);
        SpawnManager manager = managerWith(config(0, 8));

        assertTrue(manager.revalidate(stored).get(10, TimeUnit.SECONDS));

        world.getBlockAt(0, MOCK_SURFACE_Y, 0).setType(Material.AIR);
        world.getBlockAt(0, MOCK_SURFACE_Y - 1, 0).setType(Material.AIR);
        assertFalse(manager.revalidate(stored).get(10, TimeUnit.SECONDS),
                "a missing floor must still fail under a built-over point");
    }

    // --- What counts as a floor on re-check ------------------------------------------

    /*
     * The stored spawn is at MOCK_SURFACE_Y + 1, so its floor is at MOCK_SURFACE_Y, the
     * step-down floor one below that, and the mock world is solid underneath down to y=0.
     */

    private Block atFloor() {
        return world.getBlockAt(0, MOCK_SURFACE_Y, 0);
    }

    private Block belowFloor(int depth) {
        return world.getBlockAt(0, MOCK_SURFACE_Y - depth, 0);
    }

    private SpawnManager.SpawnVerdict verdictOnOrigin() {
        Location stored = originCentreSpawn();
        return managerWith(config(0, 8)).verifyStoredSpawn(stored);
    }

    static Stream<Arguments> standableFloors() {
        return Stream.of(
                Arguments.of(Material.OAK_SLAB, BlockShapes.BOTTOM_SLAB),
                Arguments.of(Material.OAK_SLAB, BlockShapes.TOP_SLAB),
                Arguments.of(Material.OAK_STAIRS, BlockShapes.STAIRS),
                Arguments.of(Material.OAK_TRAPDOOR, BlockShapes.TRAPDOOR_CLOSED_BOTTOM),
                Arguments.of(Material.OAK_TRAPDOOR, BlockShapes.TRAPDOOR_CLOSED_TOP),
                Arguments.of(Material.WHITE_CARPET, BlockShapes.CARPET),
                Arguments.of(Material.SNOW, BlockShapes.snow(2)),
                Arguments.of(Material.SNOW, BlockShapes.snow(8)),
                Arguments.of(Material.OAK_FENCE_GATE, BlockShapes.FENCE_GATE_CLOSED));
    }

    /** Nothing collides at the centre of these, whatever they have at the edges. */
    static Stream<Arguments> clearAtTheCentre() {
        return Stream.of(
                Arguments.of(Material.AIR, BlockShapes.EMPTY),
                Arguments.of(Material.POPPY, BlockShapes.EMPTY),
                Arguments.of(Material.SNOW, BlockShapes.snow(1)),
                Arguments.of(Material.OAK_TRAPDOOR, BlockShapes.TRAPDOOR_OPEN),
                Arguments.of(Material.OAK_DOOR, BlockShapes.DOOR_CLOSED),
                Arguments.of(Material.OAK_DOOR, BlockShapes.DOOR_OPEN),
                Arguments.of(Material.OAK_FENCE_GATE, BlockShapes.FENCE_GATE_OPEN));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("standableFloors")
    @DisplayName("A floor that is not a full block but has something at the centre keeps the plot")
    void partialFloorKeepsThePlot(Material type, BlockShapes.Shape shape) {
        // Nothing beneath it, so it is this block that has to count as the floor and not a
        // step down to the one below.
        shapes.place(atFloor(), type, shape);
        belowFloor(1).setType(Material.AIR);

        assertEquals(SpawnManager.SpawnVerdict.USABLE, verdictOnOrigin());
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("clearAtTheCentre")
    @DisplayName("A floor with nothing at the centre fails when there is no step below it")
    void floorClearAtTheCentreOverAHoleIsUnsafe(Material type, BlockShapes.Shape shape) {
        shapes.place(atFloor(), type, shape);
        belowFloor(1).setType(Material.AIR);

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("clearAtTheCentre")
    @DisplayName("A one-block step down to a floor keeps the plot")
    void stepDownKeepsThePlot(Material type, BlockShapes.Shape shape) {
        // A staircase dug down from the spawn point, or an open trapdoor over a one-deep
        // hole: the ground under it is the mock world's own.
        shapes.place(atFloor(), type, shape);

        assertEquals(SpawnManager.SpawnVerdict.USABLE, verdictOnOrigin());
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("standableFloors")
    @DisplayName("A step down onto a floor that is not a full block keeps the plot")
    void stepDownOntoAPartialFloorKeepsThePlot(Material type, BlockShapes.Shape shape) {
        atFloor().setType(Material.AIR);
        shapes.place(belowFloor(1), type, shape);
        belowFloor(2).setType(Material.AIR);

        assertEquals(SpawnManager.SpawnVerdict.USABLE, verdictOnOrigin());
    }

    @Test
    @DisplayName("A hole two blocks deep fails even with ground at its bottom")
    void twoDeepHoleIsUnsafe() {
        atFloor().setType(Material.AIR);
        belowFloor(1).setType(Material.AIR);

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @Test
    @DisplayName("An open trapdoor over a hole two blocks deep fails")
    void openTrapdoorOverATwoDeepHoleIsUnsafe() {
        shapes.place(atFloor(), Material.OAK_TRAPDOOR, BlockShapes.TRAPDOOR_OPEN);
        belowFloor(1).setType(Material.AIR);
        belowFloor(2).setType(Material.AIR);

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @Test
    @DisplayName("A spawn over a real drop fails")
    void realDropIsUnsafe() {
        for (int y = MOCK_SURFACE_Y; y > 0; y--) {
            world.getBlockAt(0, y, 0).setType(Material.AIR);
        }

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {
            "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE"})
    @DisplayName("A step down onto a floor that hurts fails")
    void stepDownOntoAHazardIsUnsafe(Material floor) {
        atFloor().setType(Material.AIR);
        belowFloor(1).setType(floor);

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @Test
    @DisplayName("An open trapdoor over a floor that hurts fails")
    void openTrapdoorOverAHazardIsUnsafe() {
        shapes.place(atFloor(), Material.OAK_TRAPDOOR, BlockShapes.TRAPDOOR_OPEN);
        belowFloor(1).setType(Material.MAGMA_BLOCK);

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Material.class, names = {"WATER", "LAVA", "POWDER_SNOW"})
    @DisplayName("A step down the feet would drop into something that hurts fails")
    void floodedStepIsUnsafe(Material fill) {
        // No collision, so each reads as a step down onto the ground below it; the
        // feet-level hazard check on the step cell is what has to fire.
        atFloor().setType(fill);

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @Test
    @DisplayName("A step-down plot outside the border still fails")
    void stepDownOutsideTheBorderIsUnsafe() {
        atFloor().setType(Material.AIR);
        borderAround(CELL, 0, 20);

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, verdictOnOrigin());
    }

    @Test
    @DisplayName("A step-down plot is its own standing point")
    void stepDownPlotStandsWhereStored() throws Exception {
        // The lift only moves a player out of what they collide with; a step leaves the
        // feet and head clear, so the player stands at the point and drops the one block.
        atFloor().setType(Material.AIR);
        SpawnManager manager = managerWith(config(0, 8));
        Location stored = storedOrigin();

        assertEquals(stored, manager.standingPoint(stored).get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("The lift lands on an open trapdoor rather than refusing the plot")
    void liftStopsAboveAnOpenTrapdoor() throws Exception {
        // Vanilla calls an open trapdoor solid, so the first clear position is above it.
        // The floor rule is not applied there: the player drops through onto the chest,
        // part of the owner's build and above the accepted floor, which beats world spawn.
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.CHEST);
        shapes.place(world.getBlockAt(0, MOCK_SURFACE_Y + 2, 0),
                Material.OAK_TRAPDOOR, BlockShapes.TRAPDOOR_OPEN);
        SpawnManager manager = managerWith(config(0, 8));

        Location standing = manager.standingPoint(storedOrigin()).get(10, TimeUnit.SECONDS);

        assertEquals(MOCK_SURFACE_Y + 3.0, standing.getY(), 1e-9);
    }

    // --- Where a player stands on a plot that has been built over --------------------

    private Location storedOrigin() {
        return new Location(world, 0.5, MOCK_SURFACE_Y + 1.0, 0.5, 90f, 10f);
    }

    @Test
    @DisplayName("A clear plot is its own standing point")
    void clearPlotStandsWhereStored() throws Exception {
        SpawnManager manager = managerWith(config(0, 8));
        Location stored = storedOrigin();

        assertEquals(stored, manager.standingPoint(stored).get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("A built-over plot lifts the player to the first clear position above it")
    void builtOverPlotLiftsToFirstClearPosition() throws Exception {
        // Chest at the feet, slab at the head: the first position with both clear is two
        // blocks up, standing on the slab. Folia's respawn would send them to world spawn.
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.CHEST);
        world.getBlockAt(0, MOCK_SURFACE_Y + 2, 0).setType(Material.OAK_SLAB);
        SpawnManager manager = managerWith(config(0, 8));
        Location stored = storedOrigin();

        Location standing = manager.standingPoint(stored).get(10, TimeUnit.SECONDS);

        assertEquals(stored.getX(), standing.getX(), 1e-9);
        assertEquals(stored.getZ(), standing.getZ(), 1e-9);
        assertEquals(MOCK_SURFACE_Y + 3.0, standing.getY(), 1e-9);
        assertEquals(stored.getYaw(), standing.getYaw(), 1e-6);
        assertEquals(MOCK_SURFACE_Y + 1.0, stored.getY(), 1e-9,
                "the stored point itself must not be moved");
    }

    @Test
    @DisplayName("A gap too short for a player is skipped on the way up")
    void liftSkipsAOneBlockGap() throws Exception {
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.CHEST);
        world.getBlockAt(0, MOCK_SURFACE_Y + 3, 0).setType(Material.OAK_PLANKS);
        SpawnManager manager = managerWith(config(0, 8));

        Location standing = manager.standingPoint(storedOrigin()).get(10, TimeUnit.SECONDS);

        assertEquals(MOCK_SURFACE_Y + 4.0, standing.getY(), 1e-9);
    }

    @Test
    @DisplayName("A lift that would land on something harmful finds no standing point")
    void liftOntoAHazardIsRefused() throws Exception {
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.CHEST);
        world.getBlockAt(0, MOCK_SURFACE_Y + 2, 0).setType(Material.MAGMA_BLOCK);
        SpawnManager manager = managerWith(config(0, 8));

        assertNull(manager.standingPoint(storedOrigin()).get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("A column built up to the build limit has no standing point")
    void columnWithoutClearPositionHasNoStandingPoint() throws Exception {
        for (int y = MOCK_SURFACE_Y + 1; y < world.getMaxHeight(); y++) {
            world.getBlockAt(0, y, 0).setType(Material.STONE);
        }
        SpawnManager manager = managerWith(config(0, 8));

        assertNull(manager.standingPoint(storedOrigin()).get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("The last clear position below the build limit is still found")
    void liftReachesTheBuildLimit() throws Exception {
        int top = world.getMaxHeight() - 2;
        for (int y = MOCK_SURFACE_Y + 1; y < top; y++) {
            world.getBlockAt(0, y, 0).setType(Material.STONE);
        }
        SpawnManager manager = managerWith(config(0, 8));

        Location standing = manager.standingPoint(storedOrigin()).get(10, TimeUnit.SECONDS);

        assertEquals(top, standing.getBlockY());
    }

    @Test
    @DisplayName("An unloaded plot reports as unverified rather than guessing")
    void unloadedPlotIsUnverified() {
        // Nothing is wrong with this plot; the point is that a synchronous caller cannot
        // find that out without loading a chunk, and must not pretend otherwise. No chunk
        // is loaded here, which is the state a plot nobody is standing on is normally in.
        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNVERIFIED,
                manager.verifyStoredSpawn(new Location(world, 0.5, MOCK_SURFACE_Y + 1.0, 0.5)));
    }

    @Test
    @DisplayName("The asynchronous re-check loads the chunk before judging the point")
    void asyncRevalidateAnswersForAnUnloadedPlot() throws Exception {
        // The same point verifyStoredSpawn declines to judge, because its chunk is not
        // resident. Loading it first is what turns UNVERIFIED into an actual answer.
        Location stored = new Location(world, 0.5, MOCK_SURFACE_Y + 1.0, 0.5);
        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNVERIFIED, manager.verifyStoredSpawn(stored));
        assertTrue(manager.revalidate(stored).get(10, TimeUnit.SECONDS));

        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.LAVA);
        assertFalse(manager.revalidate(stored).get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Repairing a plot searches its own cell and claims no new index")
    void inCellRepairKeepsTheCell() throws Exception {
        // Cell 1 is the owner's. Its centre is ruined, so the repair has to find another
        // point inside it rather than moving the owner off their land.
        makeHazard(1, 0, Material.LAVA);

        SpawnManager manager = managerWith(config(0, 8));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res =
                manager.findSafeSpawnInCell(1).get(10, TimeUnit.SECONDS);

        assertEquals(1, res.index(), "the spiral index must not advance");
        assertEquals(1, res.gridU());
        assertEquals(0, res.gridV());
        assertEquals(0, indices.get(), "an in-cell repair claims no index at all");
        assertNotEquals(CELL + 0.5, res.location().getX(), "should have moved off the ruined centre");

        double bound = CELL / 2.0 + 0.5;
        assertTrue(Math.abs(res.location().getX() - CELL) <= bound,
                "repair left the owner's cell: " + res.location().getX());
        assertTrue(Math.abs(res.location().getZ()) <= bound,
                "repair left the owner's cell: " + res.location().getZ());
    }

    @Test
    @DisplayName("A cell where every sampled candidate fails resolves to nothing, not to a bad point")
    void inCellRepairGivesUpRatherThanReturningAnUnsafePoint() throws Exception {
        makeCellOcean(1, 0);

        SpawnManager manager = managerWith(config(0, 8));

        assertNull(manager.findSafeSpawnInCell(1).get(10, TimeUnit.SECONDS),
                "allocation's least-bad fallback must not apply to a repair");
    }

    // --- World border ------------------------------------------------------------------

    /**
     * Confines the world border to a box around the given centre.
     *
     * <p>MockBukkit's border spans {@code centre +/- size} rather than the half-size vanilla
     * uses, so these fixtures state the reach they want and do not convert. What is under
     * test is which side of the border a candidate falls on, not how the size is measured.
     */
    private void borderAround(double centreX, double centreZ, double reach) {
        world.getWorldBorder().setCenter(centreX, centreZ);
        world.getWorldBorder().setSize(reach);
    }

    @Test
    @DisplayName("A cell outside the world border is skipped even though its terrain is safe")
    void candidatesOutsideTheBorderAreRejected() throws Exception {
        // The border reaches x in (44, 84): the whole of cell 0 is outside it, and cell 1's
        // centre is inside. Terrain everywhere is the mock's default flat, safe surface, so
        // the border is the only thing that can reject anything here.
        borderAround(CELL, 0, 20);

        SpawnManager manager = managerWith(config(0, 8));
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(1, res.index(), "cell 0 lies outside the border, so it must be skipped");
        assertEquals(CELL + 0.5, res.location().getX(), 1e-9);
        assertFalse(res.fallback(), "a real point was found; this is not a fallback");
        assertEquals(12, res.rejections().get(RejectionReason.OUTSIDE_BORDER),
                "every candidate of the skipped cell should be attributed to the border");
        assertTrue(world.getWorldBorder().isInside(res.location()),
                "allocated outside the border: " + res.location());
    }

    @Test
    @DisplayName("A scan that never reaches inside the border gives up instead of stranding the player")
    void scanEntirelyOutsideTheBorderGivesUp() {
        // The border is nowhere near the spiral, so no cell the scan can reach is inside it.
        // Advancing cannot help: the spiral only grows, so each later cell is further out.
        borderAround(100_000, 100_000, 16);

        int budget = 4;
        InlineSpawnManager manager =
                new InlineSpawnManager(plugin, world, config(0, budget), shapes);
        AtomicInteger indices = new AtomicInteger();

        SpawnManager.BorderExhausted exhausted = exhaustion(manager, indices);

        assertTrue(exhausted.message().contains("world border"),
                "the outcome must name the border: " + exhausted.message());
        assertEquals(budget, exhausted.cellsProbed(), "every index the scan claimed is counted");
        assertEquals(budget, indices.get(),
                "the scan must stop at max-scan-attempts rather than walking outward forever");
        assertEquals(0, manager.chunkLoads.get(),
                "the border test must come before the chunk request, or the scan generates "
                        + "terrain outside the border that no player may stand on");
    }

    @Test
    @DisplayName("Border exhaustion completes the future normally, never exceptionally")
    void borderExhaustionIsAnOutcomeNotAFailure() throws Exception {
        // An exceptional completion is what put a stack trace in the console for every join.
        borderAround(100_000, 100_000, 16);

        SpawnManager manager = managerWith(config(0, 4));
        AtomicInteger indices = new AtomicInteger();

        CompletableFuture<SpawnManager.AllocationOutcome> scanned =
                manager.allocateNextSafeSpawn(sequentialIndices(indices));
        assertFalse(scanned.isCompletedExceptionally(), "the scan that gives up");
        assertInstanceOf(SpawnManager.BorderExhausted.class, scanned.get(10, TimeUnit.SECONDS));

        CompletableFuture<SpawnManager.AllocationOutcome> refused =
                manager.allocateNextSafeSpawn(sequentialIndices(indices));
        assertFalse(refused.isCompletedExceptionally(), "the refusal that follows it");
        SpawnManager.BorderExhausted outcome = assertInstanceOf(
                SpawnManager.BorderExhausted.class, refused.get(10, TimeUnit.SECONDS));
        assertEquals(0, outcome.cellsProbed(), "a refusal claims no index");
    }

    @Test
    @DisplayName("Border exhaustion is logged once, in plain text, however many joins follow")
    void borderExhaustionIsLoggedOnce() {
        borderAround(100_000, 100_000, 16);

        SpawnManager manager = managerWith(config(0, 4));
        AtomicInteger indices = new AtomicInteger();
        List<LogRecord> logged = recordLogs();

        exhaustion(manager, indices);
        exhaustion(manager, indices);
        exhaustion(manager, indices);

        List<LogRecord> border = logged.stream()
                .filter(record -> record.getMessage().contains("world border"))
                .toList();
        assertEquals(1, border.size(), "one line for the condition, not one per join");
        assertEquals(Level.SEVERE, border.get(0).getLevel());
        assertNull(border.get(0).getThrown(), "the line carries no stack trace");
    }

    @Test
    @DisplayName("Scans already in flight that give up against the same border add no log line")
    void inFlightScansReportTheBorderOnce() {
        borderAround(100_000, 100_000, 16);

        SpawnManager manager = managerWith(config(0, 4));
        AtomicInteger other = new AtomicInteger(1000);
        AtomicInteger first = new AtomicInteger();
        List<LogRecord> logged = recordLogs();

        // A second join arrives while the first scan is running, before anything has been
        // recorded, so it scans too. Both give up; the one that finishes second is the one
        // that must stay quiet.
        IntSupplier racing = () -> {
            if (first.get() == 0) {
                exhaustion(manager, other);
            }
            return first.getAndIncrement();
        };
        assertInstanceOf(SpawnManager.BorderExhausted.class,
                assertDoesNotThrow(() -> manager.allocateNextSafeSpawn(racing)
                        .get(10, TimeUnit.SECONDS)));

        assertEquals(1004, other.get(), "the second join scanned rather than being refused");
        assertEquals(1, logged.stream()
                .filter(record -> record.getMessage().contains("world border"))
                .count(), "two scans gave up against one border, and it is reported once");
    }

    @Test
    @DisplayName("A border exhausted again after it changed is reported again")
    void borderExhaustionIsReportedAgainForANewBorder() {
        borderAround(100_000, 100_000, 16);

        SpawnManager manager = managerWith(config(0, 4));
        AtomicInteger indices = new AtomicInteger();
        List<LogRecord> logged = recordLogs();

        exhaustion(manager, indices);
        // Moved, and still out of reach: a new condition the operator has not been told of.
        borderAround(-100_000, 100_000, 16);
        exhaustion(manager, indices);

        assertEquals(2, logged.stream()
                .filter(record -> record.getMessage().contains("world border"))
                .count());
    }

    @Test
    @DisplayName("A border put back where a scan gave up is reported once on its return")
    void borderReturningToAnExhaustedPlaceIsReportedOnce() throws Exception {
        borderAround(100_000, 100_000, 16);

        SpawnManager manager = managerWith(config(0, 4));
        AtomicInteger indices = new AtomicInteger();
        List<LogRecord> logged = recordLogs();

        exhaustion(manager, indices);

        // Moved somewhere a join succeeds, which leaves the old record where it was.
        borderAround(0, 0, 1000);
        allocate(manager, sequentialIndices(indices));
        int claimed = indices.get();

        // Put back: refused against the old record without scanning, and the operator has
        // heard nothing about it since the border first moved.
        borderAround(100_000, 100_000, 16);
        exhaustion(manager, indices);
        exhaustion(manager, indices);
        exhaustion(manager, indices);

        assertEquals(claimed, indices.get(), "the refusals on its return claim no index");
        List<LogRecord> border = logged.stream()
                .filter(record -> record.getMessage().contains("world border"))
                .toList();
        assertEquals(2, border.size(), "the first report, and one on the border's return");
        assertEquals(Level.WARNING, border.get(1).getLevel());
        assertNull(border.get(1).getThrown(), "the line carries no stack trace");
        assertTrue(border.get(1).getMessage().contains("refused"), border.get(1).getMessage());
    }

    @Test
    @DisplayName("A repeat join after border exhaustion claims no further indices")
    void repeatedAllocationAfterBorderExhaustionBurnsNoIndices() {
        // The scan cannot succeed and nothing is written for the player, so the next join
        // arrives unallocated and asks again. Rescanning would advance the spiral by another
        // max-scan-attempts indices that hold no plot, every join, for every player.
        borderAround(100_000, 100_000, 16);

        int budget = 4;
        SpawnManager manager = managerWith(config(0, budget));
        AtomicInteger indices = new AtomicInteger();

        exhaustion(manager, indices);
        assertEquals(budget, indices.get(), "the first scan pays for itself, once");

        // Each repeat must give up the same way rather than placing the player.
        exhaustion(manager, indices);
        exhaustion(manager, indices);
        assertEquals(budget, indices.get(),
                "a refusal must not claim an index: the spiral stood still across two retries");
    }

    @Test
    @DisplayName("Widening the border lets allocation run again without an operator reset")
    void wideningTheBorderResumesAllocation() throws Exception {
        borderAround(100_000, 100_000, 16);

        SpawnManager manager = managerWith(config(0, 4));
        AtomicInteger indices = new AtomicInteger();

        exhaustion(manager, indices);

        // The refusal is held against the border's geometry, not as a latch: the operator
        // fixes the border and the next join works, with nothing to clear by hand.
        borderAround(0, 0, 1000);

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertTrue(world.getWorldBorder().isInside(res.location()),
                "allocated outside the border: " + res.location());
    }

    @Test
    @DisplayName("A simulation is not refused by an exhaustion the live spiral ran into")
    void simulationIsNotRefusedByALiveExhaustion() throws Exception {
        // The border covers the origin cell's centre and nothing a live spiral this far out
        // can reach, which is the state an operator runs /sgen simulate to understand.
        borderAround(0, 0, 20);

        SpawnManager manager = managerWith(config(0, 4));

        exhaustion(manager, new AtomicInteger(100));

        // Refusing here would answer the one diagnostic for this failure with a line claiming
        // nothing is inside the border, while the origin plainly is.
        SpawnSimulator.Report report = SpawnSimulator.run(manager, 1).get(10, TimeUnit.SECONDS);

        assertEquals(1, report.completed(), "the simulation must still run and report");
    }

    @Test
    @DisplayName("A simulation cannot refuse a player allocation the live spiral could still fill")
    void simulationCannotRefuseALaterAllocation() throws Exception {
        // The border sits over cell (2,0) and misses the origin, so a simulation counting
        // from zero exhausts while the cells the live spiral has reached are inside.
        borderAround(2 * CELL, 0, 20);

        SpawnManager manager = managerWith(config(0, 4));
        List<LogRecord> logged = recordLogs();

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 1).get(10, TimeUnit.SECONDS);
        assertEquals(1, report.borderExhausted(), "the simulation should exhaust near the origin");
        assertTrue(logged.isEmpty(), "a simulated sample is the run's to report, not the manager's");

        // A read-only diagnostic must not be able to lock allocation out.
        int insideIndex = indexOfGrid(2, 0);
        AtomicInteger indices = new AtomicInteger(insideIndex);

        SpawnManager.LocationResult res = allocate(manager, sequentialIndices(indices));

        assertEquals(insideIndex, res.index(), "the joining player's own cell was usable");
        assertTrue(world.getWorldBorder().isInside(res.location()),
                "allocated outside the border: " + res.location());
    }

    @Test
    @DisplayName("A simulation that runs into the border keeps and reports the samples before it")
    void simulationReportsAMidRunExhaustion() throws Exception {
        // The border covers the origin cell's centre and nothing else, so the first sample
        // is placed and every later one walks its whole budget outside the border.
        borderAround(0, 0, 20);

        SpawnManager manager = managerWith(config(0, 4));

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 3).get(10, TimeUnit.SECONDS);

        assertEquals(3, report.samples());
        assertEquals(1, report.completed(), "the sample placed before the exhaustion is kept");
        assertEquals(1, report.cellsProbed(), "exhausted scans do not count as placement cost");
        assertEquals(2, report.borderExhausted(), "the run keeps sampling after an exhaustion");
        assertEquals(2, report.firstExhaustedSample());
        assertEquals(1, report.firstExhaustedIndex(), "the second sample scanned on from index 1");
        assertNull(report.failure(), "an exhaustion is an outcome, not a failure");
        assertTrue(report.toSummaryLine().contains(" exhausted=2"), report.toSummaryLine());
    }

    @Test
    @DisplayName("A sample that fails unexpectedly ends the run without discarding its report")
    void simulationKeepsTheReportWhenASampleFails() throws Exception {
        IllegalStateException unavailable = new IllegalStateException("scheduler unavailable");
        AtomicInteger calls = new AtomicInteger();
        SpawnManager manager = new InlineSpawnManager(plugin, world, config(0, 8), shapes) {
            @Override
            public CompletableFuture<AllocationOutcome> simulateNextSafeSpawn(IntSupplier indexSupplier) {
                return calls.incrementAndGet() == 3
                        ? CompletableFuture.failedFuture(new CompletionException(unavailable))
                        : super.simulateNextSafeSpawn(indexSupplier);
            }
        };

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 5).get(10, TimeUnit.SECONDS);

        assertEquals(2, report.completed(), "the samples before the failure are kept");
        assertEquals(3, report.failedSample());
        assertEquals(unavailable, report.failure(), "the cause is reported unwrapped");
        assertEquals(3, calls.get(), "the run stops at the failed sample");
        assertTrue(report.toRejectionLine().startsWith("SIMULATE rejections"),
                "the smoke test still sees the run finish");
    }

    @Test
    @DisplayName("A sample that completes with neither a result nor an error keeps the report")
    void simulationKeepsTheReportWhenASampleCompletesEmpty() throws Exception {
        SpawnManager manager = simulatingManager(3, () -> CompletableFuture.completedFuture(null));

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 5).get(10, TimeUnit.SECONDS);

        assertEquals(2, report.completed(), "the samples before the empty one are kept");
        assertEquals(3, report.failedSample());
        assertInstanceOf(IllegalStateException.class, report.failure());
        assertKeepsSmokeLines(report);
    }

    @Test
    @DisplayName("A sample whose result cannot be recorded keeps the report")
    void simulationKeepsTheReportWhenRecordingASampleThrows() throws Exception {
        // No rejection map: recording it throws inside the handler that reads the outcome.
        SpawnManager manager = simulatingManager(3, () -> CompletableFuture.completedFuture(
                new SpawnManager.LocationResult(new Location(world, 0, 64, 0), 0, 0, 0, 64,
                        1, 1, false, null)));

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 5).get(10, TimeUnit.SECONDS);

        assertEquals(3, report.failedSample());
        assertInstanceOf(NullPointerException.class, report.failure());
        assertKeepsSmokeLines(report);
    }

    @Test
    @DisplayName("A sample that throws an Error rather than an exception keeps the report")
    void simulationKeepsTheReportWhenASampleThrowsAnError() throws Exception {
        StackOverflowError overflow = new StackOverflowError();
        SpawnManager manager = simulatingManager(3, () -> {
            throw overflow;
        });

        SpawnSimulator.Report report = SpawnSimulator.run(manager, 5).get(10, TimeUnit.SECONDS);

        assertEquals(2, report.completed(), "the samples before the failure are kept");
        assertEquals(3, report.failedSample());
        assertEquals(overflow, report.failure());
        assertKeepsSmokeLines(report);
    }

    @Test
    @DisplayName("A failure with no message is named by its class, never as null")
    void simulationFailureSummaryNamesAMessagelessFailure() {
        SpawnSimulator.Report report = new SpawnSimulator.Report(5);
        assertNull(report.failureSummary(), "nothing to summarise before a failure");

        report.fail(3, new IllegalStateException());
        assertEquals("IllegalStateException", report.failureSummary());

        report.fail(3, new IllegalStateException("  "));
        assertEquals("IllegalStateException", report.failureSummary());

        report.fail(3, new IllegalStateException("scheduler unavailable"));
        assertEquals("scheduler unavailable", report.failureSummary());
    }

    /** A manager whose {@code failingCall}th simulated sample is {@code failing}'s instead. */
    private SpawnManager simulatingManager(int failingCall,
            Supplier<CompletableFuture<SpawnManager.AllocationOutcome>> failing) {
        AtomicInteger calls = new AtomicInteger();
        return new InlineSpawnManager(plugin, world, config(0, 8), shapes) {
            @Override
            public CompletableFuture<AllocationOutcome> simulateNextSafeSpawn(IntSupplier indexSupplier) {
                return calls.incrementAndGet() == failingCall
                        ? failing.get()
                        : super.simulateNextSafeSpawn(indexSupplier);
            }
        };
    }

    /** The lines the CI smoke test greps for, still printed and in their order. */
    private static void assertKeepsSmokeLines(SpawnSimulator.Report report) {
        assertTrue(report.toRejectionLine().startsWith("SIMULATE rejections"),
                report.toRejectionLine());
        assertTrue(report.toSummaryLine().startsWith("SIMULATE samples=5 "), report.toSummaryLine());
        assertTrue(report.toSummaryLine().endsWith(" exhausted=0"), report.toSummaryLine());
    }

    @Test
    @DisplayName("A simulation that gives up is not refused by its own exhaustion either")
    void simulationDoesNotRefuseItself() throws Exception {
        borderAround(100_000, 100_000, 16);

        int budget = 4;
        SpawnManager manager = managerWith(config(0, budget));
        AtomicInteger indices = new AtomicInteger();

        for (int run = 1; run <= 2; run++) {
            SpawnManager.BorderExhausted exhausted = assertInstanceOf(
                    SpawnManager.BorderExhausted.class,
                    manager.simulateNextSafeSpawn(sequentialIndices(indices))
                            .get(10, TimeUnit.SECONDS));
            assertEquals(budget, exhausted.cellsProbed());
            assertEquals(run * budget, indices.get(),
                    "each simulated scan walks its full budget, since none is recorded");
        }
    }

    @Test
    @DisplayName("Only a repair stays in its cell, and only a simulation records no exhaustion")
    void scanPurposesDifferOnlyWhereTheyShould() {
        // What each public entry point passes is pinned by the behaviour tests around this
        // one; this pins what each purpose means, so a new constant has to decide both.
        assertFalse(SpawnManager.ScanPurpose.PLAYER_ALLOCATION.staysInCell());
        assertTrue(SpawnManager.ScanPurpose.PLAYER_ALLOCATION.recordsExhaustion());

        assertFalse(SpawnManager.ScanPurpose.SIMULATION.staysInCell());
        assertFalse(SpawnManager.ScanPurpose.SIMULATION.recordsExhaustion());

        assertTrue(SpawnManager.ScanPurpose.REPAIR.staysInCell());
        assertTrue(SpawnManager.ScanPurpose.REPAIR.recordsExhaustion());
    }

    /** The spiral index that lands on a given grid cell. */
    private static int indexOfGrid(int gridU, int gridV) {
        for (int index = 0; index < 10_000; index++) {
            int[] grid = SpiralMath.indexToGrid(index);
            if (grid[0] == gridU && grid[1] == gridV) {
                return index;
            }
        }
        throw new AssertionError("no spiral index maps to (" + gridU + ", " + gridV + ")");
    }

    /** Runs a player allocation that is expected to find no plot, and hands back why. */
    private SpawnManager.BorderExhausted exhaustion(SpawnManager manager, AtomicInteger indices) {
        CompletableFuture<SpawnManager.AllocationOutcome> pending =
                manager.allocateNextSafeSpawn(sequentialIndices(indices));
        return assertInstanceOf(SpawnManager.BorderExhausted.class,
                assertDoesNotThrow(() -> pending.get(10, TimeUnit.SECONDS)));
    }

    /** Collects what the plugin logs from here on, at any level. */
    private List<LogRecord> recordLogs() {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        plugin.getLogger().addHandler(new Handler() {
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
        });
        return records;
    }

    @Test
    @DisplayName("An in-cell repair of a cell outside the border finds nothing rather than failing")
    void inCellRepairOutsideTheBorderResolvesToNothing() throws Exception {
        // Cell 1 sits outside a border drawn around the origin. A repair owns its cell and
        // cannot leave it, so the honest answer is the same one an unusable cell already
        // gives: nothing found, assignment untouched, caller sends them to world spawn.
        borderAround(0, 0, 20);

        SpawnManager manager = managerWith(config(0, 8));

        assertNull(manager.findSafeSpawnInCell(1).get(10, TimeUnit.SECONDS),
                "a cell outside the border holds no usable point");
    }

    // --- Revalidation against a border shrunk after allocation -------------------------

    @Test
    @DisplayName("A plot left outside a shrunken border no longer verifies, though its terrain is untouched")
    void plotOutsideAShrunkenBorderIsUnsafe() throws Exception {
        // Allocated legally, then the border was drawn in around cell 1 alone. Nothing on
        // the plot changed, so the border is the only thing that can fail it.
        Location stored = originCentreSpawn();
        borderAround(CELL, 0, 20);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, manager.verifyStoredSpawn(stored));
        assertFalse(manager.revalidate(stored).get(10, TimeUnit.SECONDS),
                "the asynchronous re-check the repair and the respawn lift use must agree");
    }

    @Test
    @DisplayName("A plot outside the border is unsafe even when its chunk is not resident")
    void unloadedPlotOutsideTheBorderIsUnsafe() {
        // The border needs no chunk, so the synchronous respawn path can answer it rather
        // than respawning the player onto the plot and correcting afterwards.
        Location stored = new Location(world, 0.5, MOCK_SURFACE_Y + 1.0, 0.5);
        borderAround(CELL, 0, 20);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, manager.verifyStoredSpawn(stored));
        assertFalse(manager.isInsideBorder(stored));
    }

    @Test
    @DisplayName("A plot still inside the border is judged on its terrain as before")
    void plotInsideTheBorderIsJudgedAsBefore() throws Exception {
        Location stored = originCentreSpawn();
        borderAround(0, 0, 20);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.USABLE, manager.verifyStoredSpawn(stored));
        assertTrue(manager.revalidate(stored).get(10, TimeUnit.SECONDS));

        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.LAVA);
        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, manager.verifyStoredSpawn(stored),
                "being inside the border must not excuse a hazard");
    }

    @Test
    @DisplayName("A built-over plot outside the border is not rescued by the lift")
    void builtOverPlotOutsideTheBorderIsUnsafe() throws Exception {
        // A chest at the feet is kept, and the respawn handlers would lift the owner on top
        // of it. The lift only moves along Y, so the column test has to fail the plot here,
        // before any lift is asked for.
        Location stored = originCentreSpawn();
        world.getBlockAt(0, MOCK_SURFACE_Y + 1, 0).setType(Material.CHEST);
        SpawnManager manager = managerWith(config(0, 8));

        borderAround(0, 0, 20);
        assertEquals(SpawnManager.SpawnVerdict.USABLE, manager.verifyStoredSpawn(stored));
        Location standing = manager.standingPoint(stored).get(10, TimeUnit.SECONDS);
        assertEquals(MOCK_SURFACE_Y + 2.0, standing.getY(), 1e-9);
        assertTrue(world.getWorldBorder().isInside(standing),
                "a lift must stay in the column the border was checked for: " + standing);

        borderAround(CELL, 0, 20);
        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, manager.verifyStoredSpawn(stored));
        assertFalse(manager.revalidate(stored).get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("A plot whose whole cell is outside the border fails and its repair finds nothing")
    void wholeCellOutsideTheBorderHoldsThePlayer() {
        // Nothing to move the owner to without taking another cell, which the repair never
        // does: it resolves to null, and the caller holds them at world spawn and leaves the
        // record as it is, so the plot comes back if the border is widened again.
        Location stored = originCentreSpawn();
        borderAround(3 * CELL, 0, 20);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, manager.verifyStoredSpawn(stored));
        assertNull(manager.findSafeSpawnInCell(0).join(),
                "the repair must not return a point outside the border or outside the cell");

        borderAround(0, 0, 1000);
        assertEquals(SpawnManager.SpawnVerdict.USABLE, manager.verifyStoredSpawn(stored),
                "the untouched record must be usable again once the border grows back");
    }

    @Test
    @DisplayName("A plot whose cell is only partly outside the border is repaired inside it")
    void partlyOutsideCellIsRepairedInsideTheBorder() throws Exception {
        // The border reaches x in (76, 116): cell 1's centre at x=64 is outside it, and the
        // candidates at x=80 and x=96 are inside. The repair has to land on one of those.
        world.loadChunk(CELL >> 4, 0);
        Location stored = new Location(world, CELL + 0.5, MOCK_SURFACE_Y + 1.0, 0.5);
        borderAround(96, 0, 20);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE, manager.verifyStoredSpawn(stored));

        SpawnManager.LocationResult res =
                manager.findSafeSpawnInCell(1).get(10, TimeUnit.SECONDS);

        assertEquals(1, res.index(), "the repair must stay in the owner's cell");
        assertTrue(world.getWorldBorder().isInside(res.location()),
                "repaired outside the border: " + res.location());
        double bound = CELL / 2.0 + 0.5;
        assertTrue(Math.abs(res.location().getX() - CELL) <= bound,
                "repair left the owner's cell: " + res.location().getX());
        assertTrue(res.rejections().get(RejectionReason.OUTSIDE_BORDER) > 0,
                "the centre and its neighbours should have been rejected for the border");
        assertTrue(manager.revalidate(res.location()).get(10, TimeUnit.SECONDS),
                "the replacement point must pass the same re-check the old one failed");
    }

    /**
     * Raises the terrain-shape sample columns of the chunk containing the given point,
     * leaving the point itself low — a hole in otherwise higher ground.
     */
    private void raiseTerrainAround(int x, int z, int y) {
        for (int[] local : profileLocalsOf()) {
            world.getBlockAt(((x >> 4) << 4) + local[0], y, ((z >> 4) << 4) + local[1])
                    .setType(Material.STONE);
        }
    }

    /** Mirrors {@code SpawnManager.PROFILE_LOCALS}; kept here so fixtures stay explicit. */
    private static int[][] profileLocalsOf() {
        return new int[][]{{2, 2}, {8, 2}, {14, 2}, {2, 8}, {14, 8}, {2, 14}, {8, 14}, {14, 14}};
    }
}
