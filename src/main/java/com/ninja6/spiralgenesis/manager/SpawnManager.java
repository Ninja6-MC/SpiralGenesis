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

    private static final Set<Biome> OCEAN_BIOMES = EnumSet.of(
            Biome.OCEAN, Biome.DEEP_OCEAN, Biome.WARM_OCEAN, Biome.LUKEWARM_OCEAN,
            Biome.DEEP_LUKEWARM_OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN,
            Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN
    );

    public SpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
    }

    /**
     * Allocates the next safe dry land spawn location asynchronously.
     *
     * @param startIndex The starting index to scan from
     * @return CompletableFuture resolving to safe LocationResult
     */
    public CompletableFuture<LocationResult> allocateNextSafeSpawn(int startIndex) {
        return findSafeRecursive(startIndex, 0);
    }

    private CompletableFuture<LocationResult> findSafeRecursive(int index, int attempts) {
        if (attempts >= config.getMaxScanAttempts()) {
            plugin.getLogger().warning("Reached maximum scan attempts (" + config.getMaxScanAttempts() + ") for index " + index);
            // Fallback to origin or current coordinate on max attempts
            int[] grid = SpiralMath.indexToGrid(index);
            int targetX = config.getOriginX() + (grid[0] * config.getCellSize());
            int targetZ = config.getOriginZ() + (grid[1] * config.getCellSize());
            Location fallback = new Location(world, targetX + 0.5, 75.0, targetZ + 0.5);
            return CompletableFuture.completedFuture(new LocationResult(fallback, index, grid[0], grid[1]));
        }

        int[] grid = SpiralMath.indexToGrid(index);
        int targetX = config.getOriginX() + (grid[0] * config.getCellSize());
        int targetZ = config.getOriginZ() + (grid[1] * config.getCellSize());

        // Asynchronous chunk loading via Paper API
        return world.getChunkAtAsync(targetX >> 4, targetZ >> 4).thenCompose(chunk -> {
            Biome biome = world.getBiome(targetX, 64, targetZ);
            if (OCEAN_BIOMES.contains(biome)) {
                // Reject ocean biome, advance index
                return findSafeRecursive(index + 1, attempts + 1);
            }

            int surfaceY = world.getHighestBlockYAt(targetX, targetZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block block = world.getBlockAt(targetX, surfaceY, targetZ);

            if (surfaceY < config.getMinSurfaceY() || HAZARD_MATERIALS.contains(block.getType())) {
                // Reject water/hazard surface, advance index
                return findSafeRecursive(index + 1, attempts + 1);
            }

            // Safe dry land location verified
            Location loc = new Location(world, targetX + 0.5, surfaceY + 1.0, targetZ + 0.5);
            return CompletableFuture.completedFuture(new LocationResult(loc, index, grid[0], grid[1]));
        });
    }

    public record LocationResult(Location location, int index, int gridU, int gridV) {}
}
