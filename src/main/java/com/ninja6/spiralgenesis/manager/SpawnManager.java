package com.ninja6.spiralgenesis.manager;

import com.ninja6.spiralgenesis.config.PlacementStrategy;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.math.SpiralMath;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
     * The border geometry a scan has already given up against, or {@code null} if none has.
     *
     * <p>Volatile because allocations resolve on whichever region thread owned the last
     * candidate, and the next one may start on another.
     */
    private volatile BorderGeometry exhaustedAgainst;

    private static final Set<Material> HAZARD_MATERIALS = EnumSet.of(
            Material.WATER, Material.LAVA, Material.ICE, Material.PACKED_ICE,
            Material.BLUE_ICE, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.POWDER_SNOW
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
     * <p>Fails with {@link BorderExhaustedException} when the whole scan stayed outside the
     * world border, which is the one case where there is no point to fall back to. Once that
     * has happened, later calls are refused on the spot, without claiming an index, until
     * the border changes.
     *
     * @param indexSupplier atomic source of candidate spiral indices
     * @return CompletableFuture resolving to safe LocationResult
     */
    public CompletableFuture<LocationResult> allocateNextSafeSpawn(IntSupplier indexSupplier) {
        CompletableFuture<LocationResult> result = new CompletableFuture<>();

        // Refused before a single index is claimed. A scan that has already walked its whole
        // budget outside this exact border will do it again for the same reason, and every
        // repeat would advance the spiral by another max-scan-attempts indices that no
        // player is ever recorded against - once per join, for every affected player, for
        // as long as the border stays where it is.
        //
        // Held against the border's geometry rather than as a flag, so widening or moving
        // the border makes the next join scan again with nothing for an operator to reset.
        //
        // Read-then-act, and deliberately not made atomic: allocations already in flight when
        // the first exhaustion is recorded each finish their own scan, so a join surge costs
        // one scan per joiner in flight and one only. Serialising them would buy nothing but
        // a lock on the join path.
        BorderGeometry gaveUpAgainst = exhaustedAgainst;
        if (gaveUpAgainst != null && gaveUpAgainst.equals(BorderGeometry.of(world))) {
            result.completeExceptionally(new BorderExhaustedException(
                    "Spawn allocation refused: an earlier scan found nothing inside the world "
                            + "border of world '" + world.getName() + "' and the border has not "
                            + "changed since. Widen the border or move its centre and the next "
                            + "join scans again on its own; if you change origin.x, origin.z or "
                            + "cell-size instead, run /sgen reload."));
            return result;
        }

        nextCell(new Scan(indexSupplier, false, true, result));
        return result;
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
     * @param indexSupplier throwaway source of indices, never the live reservation
     * @return CompletableFuture resolving to safe LocationResult
     */
    public CompletableFuture<LocationResult> simulateNextSafeSpawn(IntSupplier indexSupplier) {
        CompletableFuture<LocationResult> result = new CompletableFuture<>();
        nextCell(new Scan(indexSupplier, false, false, result));
        return result;
    }

    /**
     * Searches one already-owned cell for a safe point, without ever leaving it.
     *
     * <p>For repairing a plot that was safe when it was allocated and is not any more.
     * The spiral index is supplied rather than claimed, and the scan cannot advance to
     * another cell no matter how the search goes: the owner's builds are inside this cell,
     * and moving them out of it would turn griefing a spawn into a way to evict its owner.
     *
     * <p>Unlike allocation there is no least-bad fallback. A cell where every candidate
     * fails resolves to {@code null}, because putting the player back on a point already
     * known to be lethal is worse than sending them somewhere unremarkable.
     *
     * @param index the spiral index the player already holds
     * @return a future resolving to a safe point in that cell, or {@code null} if the
     *         sampled candidates all failed
     */
    public CompletableFuture<LocationResult> findSafeSpawnInCell(int index) {
        CompletableFuture<LocationResult> result = new CompletableFuture<>();
        // Live, not diagnostic: it repairs a real player's plot. The distinction never comes
        // up in practice, because a cell-only scan resolves to null before the exhaustion
        // branch is reached.
        nextCell(new Scan(() -> index, true, true, result));
        return result;
    }

    /**
     * Cheap, synchronous re-check of a point that was validated once, at allocation.
     *
     * <p>Every read is in the column the player would stand in, so this touches exactly one
     * chunk and no heightmap. That is what makes it callable from
     * {@code PlayerRespawnEvent}, which is synchronous and cannot await anything.
     *
     * <p>It repeats the lethal subset of {@link #score}: whether the player fits, whether
     * the ground is still under them, and whether anything that kills is at their feet,
     * head or underfoot. The quality checks (ocean biome, pit, roughness) are deliberately
     * left out - terrain shape is not what a griefer changes, and re-running them would
     * relocate players over a plot that merely scores worse than it did.
     *
     * <p>The caller must already own the chunk this location is in.
     */
    public boolean isSafeNow(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        // Walled in, or dug out from under: both leave a stored point the player cannot
        // simply stand on.
        if (!isPassable(world.getBlockAt(x, y, z)) || !isPassable(world.getBlockAt(x, y + 1, z))) {
            return false;
        }
        if (isPassable(world.getBlockAt(x, y - 1, z))) {
            return false;
        }

        // Flooding shows up at the feet and head; lava poured on the plot shows up
        // underfoot once it settles into the surface block allocation approved.
        return !isHazard(x, y, z) && !isHazard(x, y + 1, z) && !isHazard(x, y - 1, z);
    }

    /**
     * Raised when a scan spent its whole budget without finding one point inside the border.
     *
     * <p>Distinct from an ordinary allocation failure because it is neither transient nor a
     * defect: the spiral has grown past the border, and no retry helps until an operator
     * widens the border or moves the origin.
     *
     * <p>Carries no stack trace. It is raised from exactly one place, its message says
     * everything an operator can act on, and the caller logs it per join: frames of
     * scheduler internals under a line about a misconfigured border are noise, not evidence.
     */
    public static final class BorderExhaustedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        BorderExhaustedException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * What a stored point can still be used for, as far as a synchronous caller can tell.
     */
    public enum SpawnVerdict {
        /** Re-checked against live blocks and still safe. */
        USABLE,
        /** Re-checked and no longer safe: something lethal or impassable is there now. */
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
     */
    public SpawnVerdict verifyStoredSpawn(Location stored) {
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
     * Claims the next spiral index and restarts the candidate search inside its cell.
     */
    private void nextCell(Scan scan) {
        scan.attempt++;
        scan.index = scan.indexSupplier.getAsInt();
        int[] grid = SpiralMath.indexToGrid(scan.index);
        scan.gridU = grid[0];
        scan.gridV = grid[1];
        scan.centreX = config.getOriginX() + (grid[0] * config.getCellSize());
        scan.centreZ = config.getOriginZ() + (grid[1] * config.getCellSize());
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
        if (scan.candidate >= candidateBudget()) {
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
        if (scan.cellOnly) {
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
        // advance to. Failing is the only outcome that neither strands the player outside
        // the border nor spins: the caller logs it and leaves them standing where they are,
        // which is inside the border by definition.
        if (scan.bestOverall == null) {
            String message = "Spawn allocation failed: every candidate across " + scan.attempt
                    + " cells fell outside the world border of world '" + world.getName()
                    + "'. The spiral has outgrown the border; widen it, or move origin.x and "
                    + "origin.z so the spiral keeps growing inside it. This scan claimed "
                    + scan.attempt + " spiral indices, the last of them " + scan.index
                    + ", and none of them holds a plot.";
            if (scan.liveSpiral) {
                // Recorded before the failure is published, so the next join is refused
                // without claiming an index rather than repeating this scan and burning
                // another max-scan-attempts of them. Never recorded for a diagnostic run,
                // whose cells say nothing about where the live spiral has reached.
                exhaustedAgainst = BorderGeometry.of(world);
                message += " Further allocations are refused without claiming an index until "
                        + "the border changes.";
            }
            plugin.getLogger().severe(message);
            scan.result.completeExceptionally(new BorderExhaustedException(message));
            return;
        }

        // Exhausted the budget. Settle on the best point seen anywhere in the scan rather
        // than a hardcoded altitude, which could bury the player or drop them into the void.
        Candidate fallback = scan.bestOverall;
        plugin.getLogger().warning("Reached maximum scan attempts ("
                + config.getMaxScanAttempts() + "); falling back to the best candidate seen, "
                + "index " + fallback.index() + " at surface Y=" + fallback.surfaceY() + ".");
        scan.result.complete(scan.resultFor(fallback, true));
    }

    /**
     * Number of candidates probed per cell, capped so the search can never wander out of
     * the cell it belongs to and into a neighbouring player's plot.
     *
     * <p>{@link SpiralMath#indexToGrid} fills the {@code (2r+1) x (2r+1)} box around the
     * origin within its first {@code (2r+1)^2} indices, so bounding the index bounds the
     * offset.
     */
    private int candidateBudget() {
        int ringsThatFit = (config.getCellSize() / 2) / config.getStride();
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
        return new Candidate(location, scan.index, scan.gridU, scan.gridV,
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
     * Mutable state for one allocation.
     *
     * <p>Fields are plain rather than volatile: exactly one probe is ever in flight, and
     * each hand-off goes through a scheduler submission, which supplies the ordering that
     * makes the previous probe's writes visible to the next.
     */
    private static final class Scan {
        private final IntSupplier indexSupplier;
        /** Pins the scan to the single cell it starts in; see {@link #findSafeSpawnInCell}. */
        private final boolean cellOnly;
        /** False for a diagnostic run, whose index range is not the live spiral's. */
        private final boolean liveSpiral;
        private final CompletableFuture<LocationResult> result;

        private int attempt;
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

        private Scan(IntSupplier indexSupplier, boolean cellOnly, boolean liveSpiral,
                     CompletableFuture<LocationResult> result) {
            this.indexSupplier = indexSupplier;
            this.cellOnly = cellOnly;
            this.liveSpiral = liveSpiral;
            this.result = result;
        }

        private LocationResult resultFor(Candidate winner, boolean fallback) {
            return new LocationResult(winner.location(), winner.index(),
                    winner.gridU(), winner.gridV(), winner.surfaceY(),
                    attempt, candidatesProbed, fallback, Map.copyOf(rejections));
        }
    }

    /** One scored candidate point. */
    private record Candidate(Location location, int index, int gridU, int gridV,
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
     */
    public record LocationResult(Location location, int index, int gridU, int gridV,
                                 int surfaceY, int cellsProbed, int candidatesProbed,
                                 boolean fallback, Map<RejectionReason, Integer> rejections) {}
}
