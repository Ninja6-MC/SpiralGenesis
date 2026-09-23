package com.ninja6.spiralgenesis.manager;

import com.ninja6.spiralgenesis.config.PlacementStrategy;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.math.SpiralMath;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.logging.Level;

/**
 * Asynchronous territory allocation and terrain validation engine.
 *
 * <p>Allocation runs as two nested searches. The outer one claims spiral indices to pick a
 * cell; the inner one probes candidate points spiralling outward from that cell's centre.
 * A cell is only abandoned once every candidate inside it fails, which keeps a single bad
 * block from discarding an entire {@code cell-size} plot and burning a spiral index.
 */
public class SpawnManager {

    private final JavaPlugin plugin;
    private final World world;
    private final PluginConfig config;

    /**
     * The border and spiral a scan has already given up against, or {@code null} if none
     * has.
     *
     * <p>Atomic because allocations resolve on whichever region thread owned the last
     * candidate, and the next one may start on another. Swapped rather than set, so that of
     * several scans in flight that exhaust against the same border, exactly one reports it.
     */
    private final AtomicReference<ExhaustionKey> exhaustedAgainst = new AtomicReference<>();

    /**
     * The exhausted border the console was last told is the current one, or {@code null}
     * once a join has found the border somewhere else.
     *
     * <p>Kept apart from {@link #exhaustedAgainst} because that record outlives the border
     * moving away: a border moved elsewhere and later put back where a scan gave up is
     * refused against the old record without scanning, and without this the operator would
     * have been told about it only once, before the border first moved.
     */
    private final AtomicReference<ExhaustionKey> announcedAgainst = new AtomicReference<>();

    private static final Set<Material> HAZARD_MATERIALS = EnumSet.of(
            Material.WATER, Material.LAVA, Material.ICE, Material.PACKED_ICE,
            Material.BLUE_ICE, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.POWDER_SNOW
    );

    /**
     * Materials that fail a stored point on re-check, at the feet, head or underfoot.
     *
     * <p>{@link #HAZARD_MATERIALS} without the ice. Ice belongs there because allocation
     * wants dry land, and a frozen lake is not that; but nothing about ice hurts a player
     * standing on or beside it, and packed and blue ice are ordinary building blocks. Kept,
     * the owner of an ice road or an ice floor through their spawn would be relocated for
     * it. The underwater plants stay: they only exist in water, so finding one at the feet
     * or head is the same flooding as finding the water itself.
     *
     * <p>Cactus, magma, and both campfires are added: each hurts a player standing on it,
     * and each is solid by vanilla's respawn test, so one placed at the feet is exactly
     * what a respawn lift puts the player on top of. Left out, a griefer could place one on a
     * plot to damage its owner on every respawn. Underfoot they are caught by the same
     * material check, since each collides over the centre of its column and so is the
     * floor.
     *
     * <p>Pointed dripstone is left out on purpose. A stalagmite only hurts through
     * {@code fallOn}, which adds 2.5 blocks to the fall and so stays under the 3-block safe
     * fall distance for a player placed on it rather than dropped; a stalactite only hurts
     * when it falls, which a respawn does not cause. Checked against folia-1.21.11. Failing
     * a plot for it would move the owner off a decoration, which is the defect this set was
     * narrowed to fix.
     */
    private static final Set<Material> REVALIDATION_HAZARDS = EnumSet.of(
            Material.WATER, Material.LAVA, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.POWDER_SNOW,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE
    );

    /**
     * Materials that disqualify a candidate merely by being <em>near</em> it.
     *
     * <p>Deliberately narrower than {@link #HAZARD_MATERIALS}: water or ice a few blocks
     * away is a lake shore or river bank, which makes for a good spawn rather than a bad
     * one. Only what can kill a player standing next to it belongs here.
     */
    private static final Set<Material> PROXIMITY_HAZARDS = EnumSet.of(
            Material.LAVA, Material.POWDER_SNOW
    );

    /**
     * Ocean biomes, matched by namespaced key rather than enum identity.
     *
     * <p>{@code Biome} is registry-backed rather than an enum on current Paper releases, so
     * an {@code EnumSet} of biome constants fails at class-initialisation there even when it
     * compiles cleanly against an older API. Key matching is stable across API versions.
     */
    private static final Set<String> OCEAN_BIOME_KEYS = Set.of(
            "ocean", "deep_ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean",
            "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean"
    );

    /**
     * Chunk-local positions of the terrain-shape samples.
     *
     * <p>Sampling the candidate's own chunk rather than a fixed radius around the candidate
     * is what makes the shape checks reliable. A ring around the point lands in neighbouring
     * chunks whenever the candidate sits near a chunk edge — which is the common case, since
     * candidates fall wherever {@code origin}, {@code cell-size} and {@code stride} put them
     * — and those chunks are not resident. Reading them would force generation; skipping
     * them would silently disable the very checks that keep players out of ravines.
     *
     * <p>Allocation has already awaited the candidate's chunk, so every one of these reads
     * is guaranteed cheap and available regardless of alignment.
     */
    private static final int[][] PROFILE_LOCALS = {
            {2, 2}, {8, 2}, {14, 2}, {2, 8}, {14, 8}, {2, 14}, {8, 14}, {14, 14}
    };

    /** Below this many samples the terrain shape cannot be judged, so it is not. */
    private static final int MIN_PROFILE_SAMPLES = 4;

    /** What {@link #floorUnder} answers for a column with nothing to stand on within a step. */
    private static final int NO_FLOOR = Integer.MIN_VALUE;

    /**
     * The longest drop, in blocks, a lifted player may take onto the floor below: vanilla's
     * no-damage fall. Fall damage is {@code ceil(fallDistance - 3)} with the default
     * {@code safe_fall_distance} of 3, so a fall of exactly 3 blocks does no damage and any
     * longer one does. See {@link #dropLandsSafely}.
     */
    private static final double MAX_SAFE_DROP = 3.0;

    /** Penalty for failing a hard check, large enough to dominate any terrain-shape penalty. */
    private static final int PENALTY_UNSAFE = 1000;

    /** Per-block penalty for sitting below the surrounding terrain, weighted above roughness. */
    private static final int PENALTY_PER_PIT_BLOCK = 10;

    public SpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
    }

    /**
     * Allocates the next safe dry land spawn location asynchronously.
     *
     * <p>Each cell consumes a fresh index from {@code indexSupplier}, so two concurrent
     * allocations can never converge on the same cell. Probes are spread one-per-tick: this
     * keeps chunk generation off any single tick's budget and bounds the callback stack.
     *
     * <p>Resolves to {@link BorderExhausted} when the whole scan stayed outside the world
     * border, which is the one case where there is no point to fall back to. Once that has
     * happened, later calls resolve to it on the spot, without claiming an index, until the
     * border changes. It is an outcome rather than a failure: the future completes
     * exceptionally only for an error nobody expected.
     *
     * <p>The console is told once per stretch of the border sitting where a scan gave up:
     * by that scan, or, when the border has since moved away and back, by the first join
     * refused after it returned. The refusals in between add nothing.
     *
     * <p>Every cell the scan reserves and does not settle on is handed back to
     * {@code cells} once the outcome is known, so it stops blocking other centres' cells.
     * The one it settles on stays reserved until the caller records it.
     *
     * <p>The geometry is read from the configuration once, for the first cell. Every later
     * cell of the scan is reserved on the centre that first cell is on, so an origin or
     * cell size changed while the scan runs applies from the next scan, not from the next
     * cell.
     *
     * @param cells atomic source of candidate cells
     * @return CompletableFuture resolving to a {@link LocationResult} or a
     *         {@link BorderExhausted}
     */
    public CompletableFuture<AllocationOutcome> allocateNextSafeSpawn(CellReserver cells) {
        // Refused before a single index is claimed. A scan that has already walked its whole
        // budget outside this exact border will do it again for the same reason, and every
        // repeat would advance the spiral by another max-scan-attempts indices that no
        // player is ever recorded against - once per join, for every affected player, for
        // as long as the border stays where it is.
        //
        // Held against the border's geometry and the spiral's rather than as a flag, so
        // widening or moving the border, or moving the spiral's centre or changing its cell
        // size, makes the next join scan again with nothing for an operator to reset.
        //
        // Read-then-act, and deliberately not made atomic: allocations already in flight when
        // the first exhaustion is recorded each finish their own scan, so a join surge costs
        // one scan per joiner in flight and one only. Serialising them would buy nothing but
        // a lock on the join path.
        //
        // Logged once and no more: the scan that gave up already reported it, and repeating
        // it for every join would bury the rest of the console under one line about the
        // border. The exception is a border that went elsewhere and came back to where a
        // scan gave up, which nothing has reported since it moved: the first join refused
        // on its return says so, and swapping the announcement means only one of several
        // joins arriving together does.
        ExhaustionKey current = ExhaustionKey.of(world, config);
        ExhaustionKey gaveUpAgainst = exhaustedAgainst.get();
        if (gaveUpAgainst != null && gaveUpAgainst.equals(current)) {
            String message = "Spawn allocation refused: an earlier scan found nothing inside "
                    + "the world border of world '" + world.getName() + "' at its current "
                    + "centre and size. Widen the border, move its centre, or change origin.x, "
                    + "origin.z or cell-size, and the next join scans again on its own.";
            if (!current.equals(announcedAgainst.getAndSet(current))) {
                plugin.getLogger().warning(message);
            }
            return CompletableFuture.completedFuture(new BorderExhausted(message, 0));
        }
        // The border is somewhere a scan has not given up against, so whatever was said
        // about the old record no longer describes it.
        announcedAgainst.set(null);

        CompletableFuture<AllocationOutcome> result = new CompletableFuture<>();
        Scan scan = new Scan(cells, ScanPurpose.PLAYER_ALLOCATION, result);
        // Attached before the first cell is reserved, so no outcome, exceptional ones
        // included, can leave a cell registered as in flight behind it. A reservation that
        // throws has reserved nothing.
        result.whenComplete((outcome, error) -> scan.releaseAllBut(
                outcome instanceof LocationResult found ? found : null));
        nextCell(scan);
        return result;
    }

    /**
     * {@link #allocateNextSafeSpawn(CellReserver)} on cells numbered by
     * {@code indexSupplier} at the configured geometry, recorded nowhere and tested against
     * nothing.
     */
    public CompletableFuture<AllocationOutcome> allocateNextSafeSpawn(IntSupplier indexSupplier) {
        return allocateNextSafeSpawn(CellReserver.counting(indexSupplier));
    }

    /**
     * Allocates a spawn for a diagnostic run rather than for a player.
     *
     * <p>Identical to {@link #allocateNextSafeSpawn} except that it neither consults nor
     * records the border exhaustion above, because a simulation scans an index range of its
     * own: {@code SpawnSimulator} counts from zero so that a run cannot advance the live
     * spiral, which means its cells are near the origin no matter how far the live spiral
     * has grown, and a conclusion drawn about one range says nothing about the other.
     *
     * <p>Both directions of that would be wrong. A real exhaustion far out must not refuse
     * the one diagnostic an operator has for it, which would answer with a failure claiming
     * nothing is inside the border while the origin plainly is. And a simulation that
     * exhausts near an off-centre origin must not be able to refuse joining players at
     * indices that are inside the border: a read-only diagnostic cannot be allowed to lock
     * allocation out.
     *
     * <p>A simulation that exhausts is not logged here either: it is the caller's sample,
     * and the caller decides how to report it.
     *
     * @param indexSupplier throwaway source of indices, never the live reservation
     * @return CompletableFuture resolving to a {@link LocationResult} or a
     *         {@link BorderExhausted}
     */
    public CompletableFuture<AllocationOutcome> simulateNextSafeSpawn(IntSupplier indexSupplier) {
        CompletableFuture<AllocationOutcome> result = new CompletableFuture<>();
        nextCell(new Scan(CellReserver.counting(indexSupplier), ScanPurpose.SIMULATION, result));
        return result;
    }

    /**
     * Searches one already-owned cell for a safe point, without ever leaving it.
     *
     * <p>For repairing a plot that was safe when it was allocated and is not any more.
     * The cell is supplied rather than claimed, and the scan cannot advance to another cell
     * no matter how the search goes: the owner's builds are inside this cell, and moving
     * them out of it would turn griefing a spawn into a way to evict its owner.
     *
     * <p>The cell is the record's own, on the centre it was allocated on, so the configured
     * origin and cell size play no part: after the centre has moved they describe a
     * different spiral, whose cell at the same index is usually somebody else's. Nothing is
     * reserved and nothing is registered in storage; the record already stands for the
     * cell.
     *
     * <p>Unlike allocation there is no least-bad fallback. A cell where every candidate
     * fails resolves to {@code null}, because putting the player back on a point already
     * known to be lethal is worse than sending them somewhere unremarkable.
     *
     * @param cell the cell the player already holds
     * @return a future resolving to a safe point in that cell, or {@code null} if the
     *         sampled candidates all failed
     */
    public CompletableFuture<LocationResult> findSafeSpawnInCell(SpiralCell cell) {
        CompletableFuture<AllocationOutcome> search = new CompletableFuture<>();
        nextCell(new Scan((originX, originZ, cellSize) -> cell, ScanPurpose.REPAIR, search));
        // A repair never leaves its cell, so it resolves to null in finishCell before the
        // exhaustion branch is reached; a BorderExhausted here is a defect, not an outcome.
        return search.thenApply(outcome -> switch (outcome) {
            case null -> null;
            case LocationResult found -> found;
            case BorderExhausted exhausted -> throw new IllegalStateException(
                    "An in-cell repair reached the exhaustion branch: " + exhausted.message());
        });
    }

    /**
     * Cheap, synchronous re-check of a point that was validated once, at allocation.
     *
     * <p>Every read is in the column the player would stand in, so this touches exactly one
     * chunk and no heightmap. That is what makes it callable from
     * {@code PlayerRespawnEvent}, which is synchronous and cannot await anything.
     *
     * <p>It repeats the lethal subset of {@link #score}: whether the point is still inside
     * the world border, whether there is still something to stand on (see
     * {@link #floorUnder}), and whether anything that kills is at their feet, head or
     * underfoot. The quality checks (ocean biome, pit,
     * roughness) are deliberately left out - terrain shape is not what a griefer changes,
     * and re-running them would relocate players over a plot that merely scores worse than
     * it did.
     *
     * <p>Whether the player still fits is left out too, and that is not an oversight. A
     * block at the feet or head is almost always the owner's own: a chest, a door, a slab,
     * the house they built around the point they were given. Failing on it would move their
     * stored spawn somewhere else in the cell, away from exactly that build. The same goes
     * for a tree that grew or sand that fell there. Only what hurts is a reason to move a
     * plot.
     *
     * <p>Where the player then stands is {@link #standingPoint}: the first clear position
     * above the stored point, which the respawn handlers apply on every platform. The
     * server cannot be relied on for it. paper-1.20.4 lifts a respawning player out of
     * what they collide with ({@code PlayerList.respawn} with {@code avoidSuffocation}),
     * but paper-1.21.11 and paper-26.2 do not: {@code PlayerList.respawn} there snaps the
     * player to the {@code PlayerRespawnEvent} location as given, with no collision loop
     * in {@code PlayerList} or {@code ServerPlayer} (javap of both server jars). Folia
     * declines a forced point whose feet or head block is solid and places the player at
     * world spawn. Either way it is a question of how the point is applied on respawn, not
     * of whether it still belongs to the player, so it is not answered by rewriting the
     * point here.
     *
     * <p>Vanilla's own check on a forced respawn point still runs first on Paper and still
     * declines a point whose feet or head block is solid. So on every such death the server
     * clears the respawn point and the client shows the vanilla "no respawn block
     * available" message, before the plugin's respawn handler overrides the location. The
     * placement is unaffected; the message is cosmetic, and the cleared point is put back
     * by the plugin's {@code PlayerSetSpawnEvent} handler.
     *
     * <p>The caller must already own the chunk this location is in.
     */
    public boolean isSafeNow(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        // Left outside by a border shrunk after allocation. Whatever the terrain, a player
        // there takes border damage until they die and is respawned onto the same point.
        // Unusable rather than rewritten: the in-cell repair looks for a point inside the
        // border and, when the whole cell is outside, finds none and leaves the record
        // alone, so the plot comes back if the border grows again. Keeping the player off
        // it until then is the callers' part, and Folia's differs from Paper's: see
        // PlayerSpawnListener.onPlayerDeath. The border is not region data and its getters
        // carry no thread check (folia-1.21.11), so this read is legal on any thread. The
        // lift in standingPointNow moves only along Y, so a point that passes here cannot
        // be lifted outside the border.
        if (!isInsideBorder(x, z)) {
            return false;
        }

        // Dug out from under: nothing to stand on within a step, and a fall of unknown
        // depth. A slab, stair, carpet or closed trapdoor is a floor, and so is a one-block
        // step down, which keeps a staircase dug down from the spawn point.
        int floorY = floorUnder(x, y, z);
        if (floorY == NO_FLOOR) {
            return false;
        }

        // Flooding shows up at the feet and head, and at y - 1 when that is the step down
        // the feet drop into: water or lava has no collision, so a flooded floor reads as a
        // step and is caught here instead. Underfoot, what the material check catches is a
        // floor that hurts: magma, cactus or a campfire.
        return !isRevalidationHazard(x, y, z)
                && !isRevalidationHazard(x, y + 1, z)
                && !isRevalidationHazard(x, y - 1, z)
                && (floorY == y - 1 || !isRevalidationHazard(x, floorY, z));
    }

    /**
     * The Y of the block a player placed at {@code y} comes to rest on, or {@link #NO_FLOOR}.
     *
     * <p>The block below is the floor when its collision shape covers the centre of the
     * column (see {@link #supportsCentre}). When it does not, nothing collides at its
     * centre, which is exactly what a step down needs, and the block below that is the
     * floor if it passes the same test. Nothing deeper is accepted: a point over a real
     * drop has no floor.
     *
     * <p>Revalidation only. Allocation keeps its own surface rules for a new plot. Both
     * reads are in the point's own column, so this needs no chunk {@link #isSafeNow} does
     * not already own.
     */
    private int floorUnder(int x, int y, int z) {
        if (supportsCentre(world.getBlockAt(x, y - 1, z))) {
            return y - 1;
        }
        if (supportsCentre(world.getBlockAt(x, y - 2, z))) {
            return y - 2;
        }
        return NO_FLOOR;
    }

    /**
     * Whether a player standing over the centre of this block lands on it.
     *
     * <p>True when one of its collision boxes spans {@code x = 0.5} and {@code z = 0.5}.
     * That accepts slabs, stairs, closed trapdoors, carpet, two or more layers of snow and a
     * closed fence gate, and rejects air, fluids, plants, a single layer of snow, and a
     * door, an open trapdoor or an open fence gate, which leave the centre clear.
     *
     * <p>The boxes are block-local: {@code CraftVoxelShape.getBoundingBoxes} copies
     * {@code VoxelShape.toAabbs()} without offsetting them, and
     * {@code CraftBlock.getCollisionShape} asks the block state for its shape with no
     * entity in context. Checked with javap against paper-1.20.4, paper-1.21.11 and
     * folia-1.21.11, and in the Paper source at the 26.2 tag.
     */
    private boolean supportsCentre(Block block) {
        return centreTop(block) >= 0;
    }

    /**
     * The height, above the bottom of the block, of the top of its collision over the
     * column centre, or {@code -1} when nothing there collides (see
     * {@link #supportsCentre}). A player landing on the block comes to rest at this height.
     */
    private double centreTop(Block block) {
        double top = -1;
        for (BoundingBox box : collisionShape(block).getBoundingBoxes()) {
            if (box.getMinX() <= 0.5 && box.getMaxX() >= 0.5
                    && box.getMinZ() <= 0.5 && box.getMaxZ() >= 0.5) {
                top = Math.max(top, box.getMaxY());
            }
        }
        return top;
    }

    /**
     * The block's collision shape.
     *
     * <p>Package-private for the same reason as {@link #isPassable}: MockBukkit does not
     * model collision shapes. {@code Block.isPassable()} is exactly "this shape is empty"
     * in CraftBlock, so the two agree on what has no collision at all.
     */
    VoxelShape collisionShape(Block block) {
        return block.getCollisionShape();
    }

    private boolean isRevalidationHazard(int x, int y, int z) {
        return REVALIDATION_HAZARDS.contains(world.getBlockAt(x, y, z).getType());
    }

    /**
     * Where a player sent to a stored point can actually stand: the point itself when its
     * feet and head blocks are clear, otherwise the first position straight above it where
     * both are.
     *
     * <p>A plot the owner has built over is kept by {@link #isSafeNow}, and neither Folia
     * nor Paper 1.21.11 and later places a respawning player clear of the build on its own:
     * Paper puts them inside it, Folia sends them to world spawn. paper-1.20.4 does lift
     * them, as {@link #isSafeNow} records, but by its own collision test rather than this
     * one. The respawn handlers place or move them here instead, on every platform, so
     * that the same hazard rule applies everywhere. The stored point itself is not
     * changed.
     *
     * <p>"Clear" is vanilla's own test for a forced respawn point,
     * {@code Block.isPossibleToRespawnInThis}: neither solid nor liquid. The search stops
     * below the world's build limit, and the position found is then held to the same
     * hazard rule as the plot: nobody is lifted onto magma or into a campfire at the top of
     * a build.
     *
     * <p>What the player then stands on is not decided here. At the stored point it is the
     * floor {@link #isSafeNow} accepted, which may be a step down. Above it, the block
     * under the position found is one vanilla calls solid, since the position below was
     * not clear. When that block leaves the column centre clear, as a door or an open
     * trapdoor does, the player drops through it onto the build beneath, and at the lowest
     * onto that same accepted floor. {@link #floorUnder} is not applied to lifted positions
     * on purpose: the drop ends on the owner's own build, never below the plot, and
     * refusing it would send them to world spawn instead. The drop is followed instead (see
     * {@link #dropLandsSafely}), and the cells it passes and the floor it ends on are held
     * to the same hazard rule, so an open trapdoor over lava inside the build is refused
     * like magma on top of it.
     *
     * <p>Loads the chunk first and runs on the thread that owns it, as {@link #revalidate}
     * does, so it is safe to call from any thread.
     *
     * @return a future resolving to the position to stand at, or {@code null} when the
     *         column has no clear position below the build limit, the first one found is
     *         hazardous, or the drop from it passes or ends on a hazard, finds no floor,
     *         or is longer than a fall that does no damage
     */
    public CompletableFuture<Location> standingPoint(Location stored) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        int chunkX = stored.getBlockX() >> 4;
        int chunkZ = stored.getBlockZ() >> 4;
        loadChunk(chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            runOnRegion(result, chunkX, chunkZ, () -> result.complete(standingPointNow(stored)));
        });
        return result;
    }

    /**
     * {@link #standingPoint} for a caller that already owns the chunk, answered inline.
     *
     * <p>For {@code PlayerRespawnEvent}, which cannot await anything, once
     * {@link #verifyStoredSpawn} has found the chunk resident.
     */
    public Location standingPointNow(Location stored) {
        int x = stored.getBlockX();
        int z = stored.getBlockZ();
        int from = stored.getBlockY();
        // The head block has to be inside the world too.
        int top = world.getMaxHeight() - 2;
        for (int y = from; y <= top; y++) {
            if (!admitsRespawn(world.getBlockAt(x, y, z))
                    || !admitsRespawn(world.getBlockAt(x, y + 1, z))) {
                continue;
            }
            if (isRevalidationHazard(x, y - 1, z)
                    || isRevalidationHazard(x, y, z)
                    || isRevalidationHazard(x, y + 1, z)) {
                return null;
            }
            double feet = stored.getY() + (y - from);
            if (y > from && !dropLandsSafely(x, y, z, from, feet)) {
                return null;
            }
            Location standing = stored.clone();
            standing.setY(feet);
            return standing;
        }
        return null;
    }

    /**
     * Whether a player placed at a lifted {@code y} comes down on a floor, without passing
     * through or landing on anything that hurts.
     *
     * <p>The block under a lifted position is one vanilla calls solid, but a door or an
     * open trapdoor leaves the column centre clear and the player falls through it. The
     * fall is followed down the column to the first block that supports the centre (see
     * {@link #supportsCentre}), and every cell passed and that floor are held to the same
     * hazard rule as {@link #isSafeNow}. When the lifted position has a floor directly
     * under it, that block is the only one checked, as before.
     *
     * <p>The drop may not go below the lowest floor {@link #isSafeNow} accepts for the
     * stored point, a step down from it. Falling past that is falling out of the build,
     * which is a drop of unknown depth, and is refused like a plot with no floor. Every
     * read is in the point's own column, so the chunk the caller already owns covers it.
     *
     * <p>Nor may the drop be longer than {@link #MAX_SAFE_DROP}, measured as vanilla
     * measures a fall: from the feet at the lifted position, {@code feet}, down to the top
     * of the floor's collision over the column centre (see {@link #centreTop}), which is
     * where the player comes to rest. A fall of exactly that height does no damage; a
     * longer one is refused like a hazard, and the player is held at world spawn.
     */
    private boolean dropLandsSafely(int x, int y, int z, int from, double feet) {
        int lowest = from - 2;
        for (int cell = y - 1; cell >= lowest; cell--) {
            if (isRevalidationHazard(x, cell, z)) {
                return false;
            }
            double top = centreTop(world.getBlockAt(x, cell, z));
            if (top >= 0) {
                return feet - (cell + top) <= MAX_SAFE_DROP;
            }
        }
        return false;
    }

    /**
     * Whether vanilla would respawn a player with this block at their feet or head.
     *
     * <p>{@code Block.isBuildable()} and {@code Block.isLiquid()} are exactly
     * {@code BlockState.isSolid()} and {@code BlockState.liquid()} in CraftBlock, the two
     * halves of {@code isPossibleToRespawnInThis}; verified against folia-1.21.11.
     * {@code Block.isSolid()} is not, as it answers {@code blocksMotion()} instead.
     *
     * <p>Package-private for the same reason as {@link #isPassable}: MockBukkit does not
     * answer these from block state.
     */
    boolean admitsRespawn(Block block) {
        return !block.isBuildable() && !block.isLiquid();
    }

    /**
     * How an allocation scan ended, when nothing unexpected went wrong.
     *
     * <p>Sealed so a caller can switch over it exhaustively and the compiler names every
     * caller that has to decide what to do with a new outcome. Errors nobody expected - a
     * chunk that failed to load, a scheduler that refused work - are not outcomes and still
     * fail the future.
     */
    public sealed interface AllocationOutcome permits LocationResult, BorderExhausted {

        /** Spiral indices the scan claimed, all of them for nothing when it found no plot. */
        int cellsProbed();
    }

    /**
     * The scan spent its whole budget without finding one point inside the world border, or
     * was refused because an earlier scan already had.
     *
     * <p>Distinct from an ordinary allocation failure because it is neither transient nor a
     * defect: the spiral has grown past the border, and no retry helps until an operator
     * widens the border or moves the origin. That is also why it is a value and not an
     * exception: the caller expects it and branches on it, and a stack trace under a line
     * about a misconfigured border is noise, not evidence.
     *
     * @param message     what happened and what an operator can do about it, in plain text
     * @param cellsProbed spiral indices the scan claimed; 0 for a refusal, which claims none
     */
    public record BorderExhausted(String message, int cellsProbed) implements AllocationOutcome {}

    /**
     * What a stored point can still be used for, as far as a synchronous caller can tell.
     */
    public enum SpawnVerdict {
        /** Re-checked against live blocks and still safe. */
        USABLE,
        /**
         * Re-checked and no longer safe: something lethal is there, the floor is gone, or
         * the point is outside the world border.
         */
        UNSAFE,
        /** Not resident, so not checkable without loading a chunk the caller cannot await. */
        UNVERIFIED
    }

    /**
     * Re-checks a stored point, without ever loading a chunk to do it.
     *
     * <p>The three-way answer exists because {@code PlayerRespawnEvent} is synchronous.
     * Loading a chunk to reach a verdict is not on the table there, so the honest options
     * are to answer from what is already resident or to say so and let the caller correct
     * afterwards.
     *
     * <p>The world border is the exception: it needs no chunk, so a point outside it is
     * answered {@link SpawnVerdict#UNSAFE} whether or not its chunk is resident.
     */
    public SpawnVerdict verifyStoredSpawn(Location stored) {
        if (!isInsideBorder(stored)) {
            return SpawnVerdict.UNSAFE;
        }
        if (!isChunkResident(stored)) {
            return SpawnVerdict.UNVERIFIED;
        }
        return isSafeNow(stored) ? SpawnVerdict.USABLE : SpawnVerdict.UNSAFE;
    }

    /**
     * Re-checks a stored point whose chunk is not resident, loading it first.
     *
     * <p>The asynchronous counterpart to {@link #verifyStoredSpawn}, for a caller that can
     * wait. It loads the chunk and then hops onto the thread that owns it, exactly as the
     * candidate probe does - which is the only ordering Folia permits, and the reason this
     * is here rather than in the caller: submitting to the region scheduler for a chunk
     * nobody has loaded queues a task that may never run.
     *
     * @return a future resolving to whether the point is still safe
     */
    public CompletableFuture<Boolean> revalidate(Location stored) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        int chunkX = stored.getBlockX() >> 4;
        int chunkZ = stored.getBlockZ() >> 4;
        loadChunk(chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            runOnRegion(result, chunkX, chunkZ, () -> result.complete(isSafeNow(stored)));
        });
        return result;
    }

    /**
     * Whether the chunk holding this point is resident right now.
     *
     * <p>Package-private seam, for the same reason as {@link #loadChunk}: what tests need
     * to control is whether the caller takes the cheap inline path, not chunk residency
     * itself.
     */
    boolean isChunkResident(Location location) {
        return world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /**
     * Whether a stored point's column is inside the world border.
     *
     * <p>Reads no block and loads no chunk, so it is safe on any thread, including a
     * player's own thread at death, where the plot's region is not owned.
     */
    public boolean isInsideBorder(Location location) {
        return isInsideBorder(location.getBlockX(), location.getBlockZ());
    }

    /**
     * Whether a candidate column stands inside the world border.
     *
     * <p>Y is irrelevant to the border, which is a column test, so the cheapest available
     * value is used rather than a heightmap read: this runs before the candidate's chunk
     * has been requested, and reading the surface here would defeat the point of asking
     * early.
     */
    private boolean isInsideBorder(int x, int z) {
        return world.getWorldBorder().isInside(new Location(world, x + 0.5, 0, z + 0.5));
    }

    /**
     * The part of a world border that decides whether a point is inside it.
     *
     * <p>Compared by value, never by identity: {@code getWorldBorder()} is free to hand
     * back a different object each call, and a border that is being moved changes its
     * centre and size without changing anything else.
     */
    private record BorderGeometry(double centreX, double centreZ, double size) {

        private static BorderGeometry of(World world) {
            var border = world.getWorldBorder();
            Location centre = border.getCenter();
            return new BorderGeometry(centre.getX(), centre.getZ(), border.getSize());
        }
    }

    /**
     * What a scan that gave up against the border gave up against: the border, and the
     * spiral it was walking, since a different centre or cell size walks different cells.
     * The spiral is keyed by its geometry, which is what its centre id stands for.
     *
     * <p>A scan records the spiral it walked, which it pinned at its first cell, and not
     * the one configured when it gives up: after a {@code setcenter} during the scan the
     * two differ, and the configured spiral has not been scanned at all.
     */
    private record ExhaustionKey(BorderGeometry border, int originX, int originZ,
                                 int cellSize) {

        /** The border now, and the spiral a join would walk now. */
        private static ExhaustionKey of(World world, PluginConfig config) {
            return new ExhaustionKey(BorderGeometry.of(world), config.getOriginX(),
                    config.getOriginZ(), config.getCellSize());
        }

        /** The border now, and the spiral a scan walked. */
        private static ExhaustionKey of(World world, SpiralCentre walked) {
            return new ExhaustionKey(BorderGeometry.of(world), walked.originX(),
                    walked.originZ(), walked.cellSize());
        }
    }

    /**
     * Claims the next cell and restarts the candidate search inside it.
     *
     * <p>A cell skipped because it overlaps another centre's plot is skipped inside the
     * reservation, so it is not an attempt and does not count toward
     * {@code max-scan-attempts}.
     *
     * <p>Only the first cell reads the configured geometry. Later cells are reserved on the
     * centre the first one is on, so a scan never mixes two spirals.
     */
    private void nextCell(Scan scan) {
        scan.attempt++;
        SpiralCell cell = scan.centre == null
                ? scan.cells.reserve(config)
                : scan.cells.reserve(scan.centre);
        scan.reserved.add(cell);
        scan.centre = cell.centre();
        scan.index = cell.index();
        int[] grid = cell.grid();
        scan.gridU = grid[0];
        scan.gridV = grid[1];
        scan.centreX = cell.centreX();
        scan.centreZ = cell.centreZ();
        scan.candidate = -1;
        scan.bestInCell = null;
        nextCandidate(scan);
    }

    /**
     * Probes the next candidate point inside the current cell, or closes the cell out when
     * the budget is spent.
     */
    private void nextCandidate(Scan scan) {
        scan.candidate++;
        if (scan.candidate >= candidateBudget(scan.centre.cellSize())) {
            finishCell(scan);
            return;
        }

        // Candidate 0 is the cell centre itself, so a viable centre is always preferred and
        // the common case costs exactly one chunk, as it did before in-cell search existed.
        int[] offset = SpiralMath.indexToGrid(scan.candidate);
        final int x = scan.centreX + (offset[0] * config.getStride());
        final int z = scan.centreZ + (offset[1] * config.getStride());

        // Ahead of the chunk request, because the border test needs nothing but x and z.
        // Behind it, a scan that never reaches inside the border would generate a chunk per
        // candidate - 96 of them by default - and write region files for land no player may
        // legally stand on.
        //
        // Read per candidate rather than once per scan: the border can be moved or resized
        // at any time, including while an allocation is in flight.
        if (!isInsideBorder(x, z)) {
            scan.candidatesProbed++;
            scan.rejections.merge(RejectionReason.OUTSIDE_BORDER, 1, Integer::sum);
            // Never scored, so it can never win the cell and can never become the least-bad
            // fallback either. Outside the border the terrain is beside the point: however
            // good the ground is, a player standing on it takes border damage until they
            // die, and the respawn point forced onto that same spot puts them straight back.
            runGlobally(scan.result, () -> nextCandidate(scan));
            return;
        }

        loadChunk(x >> 4, z >> 4).whenComplete((chunk, error) -> {
            if (error != null) {
                scan.result.completeExceptionally(error);
                return;
            }
            // Re-enter on the thread that owns this candidate: world/heightmap reads are not
            // thread-safe, and hopping the scheduler also gives every probe a fresh stack.
            // On Folia that owner is the target chunk's region thread; on Paper the region
            // scheduler runs the task on the main thread, so behaviour is unchanged there.
            runOnRegion(scan.result, x >> 4, z >> 4, () -> evaluateCandidate(scan, x, z));
        });
    }

    private void evaluateCandidate(Scan scan, int x, int z) {
        Candidate candidate = score(scan, x, z);
        scan.candidatesProbed++;
        for (RejectionReason reason : candidate.reasons()) {
            scan.rejections.merge(reason, 1, Integer::sum);
        }

        if (candidate.acceptable() && !config.getPlacementStrategy().needsFullSweep()) {
            scan.result.complete(scan.resultFor(candidate, false));
            return;
        }
        if (candidate.acceptable()) {
            scan.bestInCell = preferred(scan.bestInCell, candidate);
        }
        // Tracked across every cell, not just this one, so an exhausted scan can settle on
        // the least bad point it ever saw rather than whichever one happened to be last.
        scan.bestOverall = leastBad(scan.bestOverall, candidate);

        // The next candidate's chunk is not this one, so hand back to the global region.
        runGlobally(scan.result, () -> nextCandidate(scan));
    }

    private void finishCell(Scan scan) {
        if (scan.bestInCell != null) {
            scan.result.complete(scan.resultFor(scan.bestInCell, false));
            return;
        }
        if (scan.purpose.staysInCell()) {
            // Note this is max-candidates points, not the whole cell: 12 of the 961 that
            // fit a 500-block cell by default. The caller reports it as such.
            scan.result.complete(null);
            return;
        }
        if (scan.attempt < config.getMaxScanAttempts()) {
            nextCell(scan);
            return;
        }

        // Exhausted the budget without seeing one point inside the world border. The spiral
        // only grows, so advancing walks further out and every later cell is further
        // outside than this one; there is nothing left to settle on and nowhere useful to
        // advance to. Giving up is the only outcome that neither strands the player outside
        // the border nor spins: the caller leaves them standing where they are, which is
        // inside the border by definition.
        if (scan.bestOverall == null) {
            SpiralCentre walked = scan.centre;
            String message = "Spawn allocation failed: every candidate across " + scan.attempt
                    + " cells of spiral centre " + walked.id() + " (origin " + walked.originX()
                    + ", " + walked.originZ() + ", cell-size " + walked.cellSize()
                    + ") fell outside the world border of world '" + world.getName()
                    + "'. That spiral has outgrown the border; widen the border, or change "
                    + "origin.x, origin.z or cell-size so new plots grow on a spiral inside it."
                    + " This scan claimed " + scan.attempt + " spiral indices, the last of them "
                    + SpiralCentre.label(walked.id(), scan.index)
                    + ", and none of them holds a plot.";
            if (scan.purpose.recordsExhaustion()) {
                // Recorded before the outcome is published, so the next join is refused
                // without claiming an index rather than repeating this scan and burning
                // another max-scan-attempts of them. Never recorded for a diagnostic run,
                // whose cells say nothing about where the live spiral has reached.
                ExhaustionKey border = ExhaustionKey.of(world, walked);
                // Keyed on the walked spiral, so after a setcenter during the scan the
                // configured spiral is not refused, and the line must not say it is.
                if (walked.hasGeometry(config.getOriginX(), config.getOriginZ(),
                        config.getCellSize())) {
                    message += " Further allocations are refused without claiming an index"
                            + " until the border changes or origin.x, origin.z or cell-size"
                            + " is changed.";
                } else {
                    message += " The origin or cell size has changed since this scan started,"
                            + " so the next join scans the spiral configured now.";
                }
                // Reported once per border, here: not by the refusals that follow, and not
                // by the other scans that were already in flight and give up against the
                // same border after this one. Marked as announced as well, so the refusals
                // that follow do not report it a second time.
                boolean recorded = !border.equals(exhaustedAgainst.getAndSet(border));
                boolean unannounced = !border.equals(announcedAgainst.getAndSet(border));
                if (recorded || unannounced) {
                    plugin.getLogger().severe(message);
                }
            }
            scan.result.complete(new BorderExhausted(message, scan.attempt));
            return;
        }

        // Exhausted the budget. Settle on the best point seen anywhere in the scan rather
        // than a hardcoded altitude, which could bury the player or drop them into the void.
        Candidate fallback = scan.bestOverall;
        plugin.getLogger().warning("Reached maximum scan attempts ("
                + config.getMaxScanAttempts() + "); falling back to the best candidate seen, "
                + "plot " + SpiralCentre.label(fallback.centre(), fallback.index())
                + " at surface Y=" + fallback.surfaceY() + ".");
        scan.result.complete(scan.resultFor(fallback, true));
    }

    /**
     * Number of candidates probed per cell, capped so the search can never wander out of
     * the cell it belongs to and into a neighbouring player's plot.
     *
     * <p>{@link SpiralMath#indexToGrid} fills the {@code (2r+1) x (2r+1)} box around the
     * origin within its first {@code (2r+1)^2} indices, so bounding the index bounds the
     * offset.
     *
     * <p>The bound is {@code (cell-size - 1) / 2} rather than {@code cell-size / 2}, which
     * keeps every candidate inside {@link SpiralCell#area}. With an even cell size the
     * larger bound let a cell probe the first column of the next cell over, the one column
     * two neighbouring cells could both place a player in.
     *
     * @param cellSize the size of the cell being searched, which is its centre's and not
     *                 necessarily the configured one
     */
    private int candidateBudget(int cellSize) {
        int ringsThatFit = ((cellSize - 1) / 2) / config.getStride();
        int fitsInCell = (2 * ringsThatFit + 1) * (2 * ringsThatFit + 1);
        return Math.min(config.getMaxCandidates(), fitsInCell);
    }

    /**
     * Scores one candidate column. A {@code badness} of zero means every check passed.
     *
     * <p>Unacceptable candidates are still scored rather than discarded, so the exhausted
     * scan has something ranked to fall back on.
     */
    private Candidate score(Scan scan, int x, int z) {
        int surfaceY = surfaceAt(x, z);
        EnumSet<RejectionReason> reasons = EnumSet.noneOf(RejectionReason.class);
        int badness = 0;

        if (surfaceY < config.getMinSurfaceY()) {
            badness += PENALTY_UNSAFE;
            reasons.add(RejectionReason.LOW_SURFACE);
        }
        if (isOcean(x, surfaceY, z)) {
            badness += PENALTY_UNSAFE;
            reasons.add(RejectionReason.OCEAN);
        }
        if (isHazard(x, surfaceY, z)) {
            badness += PENALTY_UNSAFE;
            reasons.add(RejectionReason.HAZARD);
        }
        if (!hasHeadroom(x, surfaceY, z)) {
            badness += PENALTY_UNSAFE;
            reasons.add(RejectionReason.NO_HEADROOM);
        }

        List<int[]> profile = sampleChunkProfile(x, z);
        int roughness = 0;
        if (profile.size() >= MIN_PROFILE_SAMPLES) {
            int[] heights = new int[profile.size()];
            for (int i = 0; i < profile.size(); i++) {
                heights[i] = profile.get(i)[2];
            }

            // Sitting well below the surrounding terrain is the signature of a ravine,
            // sinkhole or crater: the heightmap reports their floor as "the surface"
            // because, unlike a sealed cave, nothing is above it.
            int pitDepth = median(heights) - surfaceY;
            if (pitDepth > config.getMaxPitDepth()) {
                badness += (pitDepth - config.getMaxPitDepth()) * PENALTY_PER_PIT_BLOCK;
                reasons.add(RejectionReason.PIT);
            }

            // Spread includes the candidate itself, so a spike or a cliff lip is caught
            // and not just a generally uneven neighbourhood.
            roughness = spread(heights, surfaceY);
            if (roughness > config.getMaxRoughness()) {
                badness += roughness - config.getMaxRoughness();
                reasons.add(RejectionReason.ROUGH);
            }

            for (int[] sample : profile) {
                Material nearby = world.getBlockAt(sample[0], sample[2], sample[1]).getType();
                if (PROXIMITY_HAZARDS.contains(nearby)) {
                    badness += PENALTY_UNSAFE;
                    reasons.add(RejectionReason.NEARBY_HAZARD);
                    break;
                }
            }
        }

        Location location = new Location(world, x + 0.5, surfaceY + 1.0, z + 0.5);
        return new Candidate(location, scan.centre.id(), scan.index, scan.gridU, scan.gridV,
                surfaceY, roughness, badness, reasons);
    }

    /**
     * Reads the terrain profile of the chunk the candidate sits in.
     *
     * <p>Every read is inside the already-loaded chunk, so this forces no generation and
     * yields the same number of samples no matter where in the chunk the candidate falls.
     */
    private List<int[]> sampleChunkProfile(int x, int z) {
        int baseX = (x >> 4) << 4;
        int baseZ = (z >> 4) << 4;
        List<int[]> samples = new ArrayList<>(PROFILE_LOCALS.length);
        for (int[] local : PROFILE_LOCALS) {
            int sampleX = baseX + local[0];
            int sampleZ = baseZ + local[1];
            if (sampleX == x && sampleZ == z) {
                continue; // Never measure the candidate against its own height.
            }
            samples.add(new int[]{sampleX, sampleZ, surfaceAt(sampleX, sampleZ)});
        }
        return samples;
    }

    private int surfaceAt(int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private boolean isOcean(int x, int surfaceY, int z) {
        Biome biome = world.getBiome(x, surfaceY, z);
        return OCEAN_BIOME_KEYS.contains(biome.getKey().getKey());
    }

    private boolean isHazard(int x, int surfaceY, int z) {
        Block block = world.getBlockAt(x, surfaceY, z);
        return HAZARD_MATERIALS.contains(block.getType());
    }

    /**
     * Confirms the player actually fits. {@code MOTION_BLOCKING_NO_LEAVES} ignores leaves,
     * so the two blocks above a surface can be canopy or a low overhang.
     */
    private boolean hasHeadroom(int x, int surfaceY, int z) {
        return isPassable(world.getBlockAt(x, surfaceY + 1, z))
                && isPassable(world.getBlockAt(x, surfaceY + 2, z));
    }

    /**
     * Whether a player can occupy this block.
     *
     * <p>Package-private for the same reason as {@link #loadChunk}: MockBukkit's
     * {@code Block.isPassable()} throws rather than answering.
     */
    boolean isPassable(Block block) {
        return block.isPassable();
    }

    /** Picks the better of two acceptable candidates under the configured strategy. */
    private Candidate preferred(Candidate current, Candidate challenger) {
        if (current == null) {
            return challenger;
        }
        if (config.getPlacementStrategy() == PlacementStrategy.HIGHEST) {
            int ceiling = config.getHeightCeiling();
            boolean currentUnder = current.surfaceY() <= ceiling;
            boolean challengerUnder = challenger.surfaceY() <= ceiling;
            if (currentUnder != challengerUnder) {
                return currentUnder ? current : challenger;
            }
            if (currentUnder) {
                return challenger.surfaceY() > current.surfaceY() ? challenger : current;
            }
            // Both overshoot the ceiling: the lower one is the closer to it.
            return challenger.surfaceY() < current.surfaceY() ? challenger : current;
        }
        return challenger.roughness() < current.roughness() ? challenger : current;
    }

    /**
     * Ranks fallback candidates. Ties keep the incumbent, which is the earlier candidate of
     * an earlier cell — the one nearest the spiral origin.
     */
    private Candidate leastBad(Candidate current, Candidate challenger) {
        if (current == null) {
            return challenger;
        }
        return challenger.badness() < current.badness() ? challenger : current;
    }

    private static int median(int[] values) {
        int[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static int spread(int[] values, int alsoInclude) {
        int min = alsoInclude;
        int max = alsoInclude;
        for (int value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    /**
     * Requests the chunk containing a candidate.
     *
     * <p>Package-private rather than inlined so tests can resolve immediately: MockBukkit
     * does not implement {@code getChunkAtAsync}, and the allocation logic under test does
     * not depend on the chunk object itself.
     */
    CompletableFuture<?> loadChunk(int chunkX, int chunkZ) {
        return world.getChunkAtAsync(chunkX, chunkZ);
    }

    /**
     * Runs {@code action} on the thread owning the given chunk, failing the pending result
     * rather than leaving it dangling if the scheduler rejects the task (e.g. during
     * shutdown).
     *
     * <p>Package-private so tests can run the action inline; MockBukkit does not implement
     * the region schedulers.
     */
    void runOnRegion(CompletableFuture<?> result,
                     int chunkX, int chunkZ, Runnable action) {
        dispatch(result, () -> plugin.getServer().getRegionScheduler()
                .execute(plugin, world, chunkX, chunkZ, guard(result, action)));
    }

    /**
     * Runs {@code action} on the global region, for work not tied to a specific chunk.
     *
     * <p>Package-private for the same reason as {@link #runOnRegion}.
     */
    void runGlobally(CompletableFuture<?> result, Runnable action) {
        dispatch(result, () -> plugin.getServer().getGlobalRegionScheduler()
                .execute(plugin, guard(result, action)));
    }

    private void dispatch(CompletableFuture<?> result, Runnable submit) {
        try {
            submit.run();
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.WARNING, "Spawn allocation aborted: scheduler unavailable.", e);
            result.completeExceptionally(e);
        }
    }

    private Runnable guard(CompletableFuture<?> result, Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };
    }

    /**
     * Who a scan is for, which decides the two ways scans differ.
     *
     * <p>Named for the caller rather than for the two flags it implies, so every place a
     * scan is built says whose it is, and the flags cannot be swapped at a call site because
     * no call site passes them. Package-private so a test can pin the table below.
     */
    enum ScanPurpose {

        /** A player's plot: walks the live spiral and may record exhaustion. */
        PLAYER_ALLOCATION,

        /**
         * {@code /sgen simulate}: walks the spiral but never records exhaustion, because its
         * index range is not the live spiral's. See {@link #simulateNextSafeSpawn}.
         */
        SIMULATION,

        /**
         * An in-cell repair of a plot a player already holds: never leaves that cell. Live,
         * not diagnostic, since it is a real player's plot; the distinction never comes up in
         * practice, because a scan that stays in its cell resolves to null before the
         * exhaustion branch is reached.
         */
        REPAIR;

        /** Pins the scan to the single cell it starts in; see {@link #findSafeSpawnInCell}. */
        boolean staysInCell() {
            return this == REPAIR;
        }

        /** Whether giving up against the border refuses the next player allocation. */
        boolean recordsExhaustion() {
            return this != SIMULATION;
        }
    }

    /**
     * Mutable state for one allocation.
     *
     * <p>Fields are plain rather than volatile: exactly one probe is ever in flight, and
     * each hand-off goes through a scheduler submission, which supplies the ordering that
     * makes the previous probe's writes visible to the next.
     */
    private static final class Scan {
        private final CellReserver cells;
        private final ScanPurpose purpose;
        private final CompletableFuture<AllocationOutcome> result;

        /**
         * Every cell this scan has reserved. Written only by the probe in flight; read by
         * the completion, which the future's own completion orders after the last write.
         */
        private final List<SpiralCell> reserved = new ArrayList<>();

        private int attempt;
        private SpiralCentre centre;
        private int index;
        private int gridU;
        private int gridV;
        private int centreX;
        private int centreZ;
        private int candidate;
        private int candidatesProbed;
        private Candidate bestInCell;
        private Candidate bestOverall;
        private final Map<RejectionReason, Integer> rejections = new EnumMap<>(RejectionReason.class);

        private Scan(CellReserver cells, ScanPurpose purpose,
                     CompletableFuture<AllocationOutcome> result) {
            this.cells = cells;
            this.purpose = purpose;
            this.result = result;
        }

        /** Hands back every reserved cell but the one {@code kept} is in, if any. */
        private void releaseAllBut(LocationResult kept) {
            for (SpiralCell cell : reserved) {
                if (kept == null || cell.centre().id() != kept.centre()
                        || cell.index() != kept.index()) {
                    cells.release(cell);
                }
            }
        }

        private LocationResult resultFor(Candidate winner, boolean fallback) {
            return new LocationResult(winner.location(), winner.index(),
                    winner.gridU(), winner.gridV(), winner.surfaceY(),
                    attempt, candidatesProbed, fallback, Map.copyOf(rejections),
                    winner.centre());
        }
    }

    /** One scored candidate point. */
    private record Candidate(Location location, int centre, int index, int gridU, int gridV,
                             int surfaceY, int roughness, int badness,
                             Set<RejectionReason> reasons) {

        private boolean acceptable() {
            return badness == 0;
        }
    }

    /**
     * The allocated location, plus what the scan cost to find it.
     *
     * <p>The diagnostic fields are what {@code /sgen simulate} aggregates: {@code
     * cellsProbed} is the number of spiral indices consumed, so a ratio well above 1 across
     * many allocations means the safety rules are rejecting more terrain than the world
     * actually warrants.
     *
     * @param centre id of the spiral centre {@code index} is on
     */
    public record LocationResult(Location location, int index, int gridU, int gridV,
                                 int surfaceY, int cellsProbed, int candidatesProbed,
                                 boolean fallback, Map<RejectionReason, Integer> rejections,
                                 int centre)
            implements AllocationOutcome {

        /** A result on centre 0. */
        public LocationResult(Location location, int index, int gridU, int gridV,
                              int surfaceY, int cellsProbed, int candidatesProbed,
                              boolean fallback, Map<RejectionReason, Integer> rejections) {
            this(location, index, gridU, gridV, surfaceY, cellsProbed, candidatesProbed,
                    fallback, rejections, 0);
        }

        /** How this plot is named in commands and logs; see {@link SpiralCentre#label}. */
        public String plotLabel() {
            return SpiralCentre.label(centre, index);
        }
    }
}
