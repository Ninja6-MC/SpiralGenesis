package com.ninja6.spiralgenesis.manager;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.math.SpiralMath;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import java.util.logging.Level;

/**
 * Asynchronous territory allocation and terrain validation engine.
 */
public class SpawnManager {

    private final JavaPlugin plugin;
    private final World world;
    private final PluginConfig config;

    private static final Set<Material> HAZARD_MATERIALS = EnumSet.of(
            Material.WATER, Material.LAVA, Material.ICE, Material.PACKED_ICE,
            Material.BLUE_ICE, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.POWDER_SNOW
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

    public SpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
    }

    /**
     * Allocates the next safe dry land spawn location asynchronously.
     *
     * <p>Each probe consumes a fresh index from {@code indexSupplier}, so two concurrent
     * allocations can never converge on the same cell. Probes are spread one-per-tick: this
     * keeps chunk generation off any single tick's budget and bounds the callback stack.
     *
     * @param indexSupplier atomic source of candidate spiral indices
     * @return CompletableFuture resolving to safe LocationResult
     */
    public CompletableFuture<LocationResult> allocateNextSafeSpawn(IntSupplier indexSupplier) {
        CompletableFuture<LocationResult> result = new CompletableFuture<>();
        probe(indexSupplier, 0, result);
        return result;
    }

    private void probe(IntSupplier indexSupplier, int attempt, CompletableFuture<LocationResult> result) {
        final int index = indexSupplier.getAsInt();
        final int[] grid = SpiralMath.indexToGrid(index);
        final int targetX = config.getOriginX() + (grid[0] * config.getCellSize());
        final int targetZ = config.getOriginZ() + (grid[1] * config.getCellSize());
        final boolean finalAttempt = attempt + 1 >= config.getMaxScanAttempts();

        world.getChunkAtAsync(targetX >> 4, targetZ >> 4).whenComplete((chunk, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            // Re-enter on the thread that owns this cell: world/heightmap reads are not
            // thread-safe, and hopping the scheduler also gives every probe a fresh stack.
            // On Folia that owner is the target chunk's region thread; on Paper the region
            // scheduler runs the task on the main thread, so behaviour is unchanged there.
            runOnRegion(result, targetX >> 4, targetZ >> 4,
                    () -> evaluate(indexSupplier, attempt, finalAttempt,
                            index, grid, targetX, targetZ, result));
        });
    }

    private void evaluate(IntSupplier indexSupplier, int attempt, boolean finalAttempt,
                          int index, int[] grid, int targetX, int targetZ,
                          CompletableFuture<LocationResult> result) {
        int surfaceY = world.getHighestBlockYAt(targetX, targetZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);

        if (!isSafe(targetX, targetZ, surfaceY)) {
            if (!finalAttempt) {
                // The next candidate's region is unknown until its index is claimed, and
                // probe() only computes coordinates before requesting the chunk
                // asynchronously, so the global region is the correct owner for this hop.
                runGlobally(result, () -> probe(indexSupplier, attempt + 1, result));
                return;
            }
            // Exhausted the budget. Settle on this cell's real surface rather than a
            // hardcoded altitude, which could bury the player or drop them into the void.
            plugin.getLogger().warning("Reached maximum scan attempts ("
                    + config.getMaxScanAttempts() + "); falling back to index " + index
                    + " at surface Y=" + surfaceY + ".");
        }

        Location location = new Location(world, targetX + 0.5, surfaceY + 1.0, targetZ + 0.5);
        result.complete(new LocationResult(location, index, grid[0], grid[1]));
    }

    private boolean isSafe(int x, int z, int surfaceY) {
        if (surfaceY < config.getMinSurfaceY()) {
            return false;
        }
        Biome biome = world.getBiome(x, surfaceY, z);
        if (OCEAN_BIOME_KEYS.contains(biome.getKey().getKey())) {
            return false;
        }
        Block block = world.getBlockAt(x, surfaceY, z);
        return !HAZARD_MATERIALS.contains(block.getType());
    }

    /**
     * Runs {@code action} on the thread owning the given chunk, failing the pending result
     * rather than leaving it dangling if the scheduler rejects the task (e.g. during
     * shutdown).
     */
    private void runOnRegion(CompletableFuture<LocationResult> result,
                             int chunkX, int chunkZ, Runnable action) {
        dispatch(result, () -> plugin.getServer().getRegionScheduler()
                .execute(plugin, world, chunkX, chunkZ, guard(result, action)));
    }

    /**
     * Runs {@code action} on the global region, for work not tied to a specific chunk.
     */
    private void runGlobally(CompletableFuture<LocationResult> result, Runnable action) {
        dispatch(result, () -> plugin.getServer().getGlobalRegionScheduler()
                .execute(plugin, guard(result, action)));
    }

    private void dispatch(CompletableFuture<LocationResult> result, Runnable submit) {
        try {
            submit.run();
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.WARNING, "Spawn allocation aborted: scheduler unavailable.", e);
            result.completeExceptionally(e);
        }
    }

    private Runnable guard(CompletableFuture<LocationResult> result, Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };
    }

    public record LocationResult(Location location, int index, int gridU, int gridV) {}
}
