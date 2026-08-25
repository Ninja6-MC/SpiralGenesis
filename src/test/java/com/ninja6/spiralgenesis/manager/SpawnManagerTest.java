package com.ninja6.spiralgenesis.manager;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        InlineSpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
        }

        @Override
        CompletableFuture<?> loadChunk(int chunkX, int chunkZ) {
            return CompletableFuture.completedFuture(null);
        }

        /** MockBukkit's {@code Block.isPassable()} throws, so judge by material instead. */
        @Override
        boolean isPassable(Block block) {
            return block.getType().isAir();
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
                               int blockedX, int blockedZ) {
            super(plugin, world, config);
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
        return new InlineSpawnManager(plugin, world, config);
    }

    private SpawnManager.LocationResult allocate(SpawnManager manager, IntSupplier supplier)
            throws InterruptedException, ExecutionException, TimeoutException {
        return manager.allocateNextSafeSpawn(supplier).get(10, TimeUnit.SECONDS);
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
        SpawnManager manager = new BlockedHeadroomManager(plugin, world, config(0, 8), 0, 0);

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
                "candidates=", "fallbacks=", "minY=", "maxY="}) {
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
        world.getBlockAt(0, MOCK_SURFACE_Y, 0).setType(Material.AIR);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
    }

    @Test
    @DisplayName("A hazard underfoot is caught even when the column itself is clear")
    void hazardousGroundIsUnsafe() {
        // Solid, so the passability checks all pass; it is the material rule that has to
        // fire here, exactly as it did when the point was first scored.
        world.getBlockAt(0, MOCK_SURFACE_Y, 0).setType(Material.PACKED_ICE);

        SpawnManager manager = managerWith(config(0, 8));

        assertEquals(SpawnManager.SpawnVerdict.UNSAFE,
                manager.verifyStoredSpawn(originCentreSpawn()));
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
