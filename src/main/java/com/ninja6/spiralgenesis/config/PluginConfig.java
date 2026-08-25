package com.ninja6.spiralgenesis.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Strongly typed configuration container and validator for SpiralGenesis.
 */
public class PluginConfig {

    static final int MIN_CELL_SIZE = 50;
    static final int MAX_CELL_SIZE = 100_000;
    static final int MIN_SURFACE_Y_FLOOR = -64;
    static final int MIN_SURFACE_Y_CEILING = 320;
    static final int MIN_SCAN_ATTEMPTS = 1;
    static final int MAX_SCAN_ATTEMPTS = 500;
    /** A stride below one chunk would put several candidates in the same chunk. */
    static final int MIN_STRIDE = 16;
    static final int MAX_STRIDE = 512;
    static final int MIN_CANDIDATES = 1;
    static final int MAX_CANDIDATES = 64;
    static final int MIN_PIT_DEPTH = 1;
    static final int MAX_PIT_DEPTH = 128;
    static final int MIN_ROUGHNESS = 1;
    static final int MAX_ROUGHNESS = 128;
    /** Below this the backstop could fire before a player has finished typing a password. */
    static final int MIN_ACTION_TIMEOUT = 15;
    static final int MAX_ACTION_TIMEOUT = 3600;

    private final String worldName;
    private int originX;
    private int originZ;
    private final int cellSize;
    private final int minSurfaceY;
    private final int maxScanAttempts;
    private final PlacementStrategy placementStrategy;
    private final int stride;
    private final int maxCandidates;
    private final int heightCeiling;
    private final int maxPitDepth;
    private final int maxRoughness;
    private final AllocationTrigger allocationTrigger;
    private final int actionTimeoutSeconds;

    public PluginConfig(FileConfiguration config) {
        String configuredWorld = config.getString("origin.world", "world");
        this.worldName = (configuredWorld == null || configuredWorld.isBlank()) ? "world" : configuredWorld;
        this.originX = config.getInt("origin.x", 0);
        this.originZ = config.getInt("origin.z", 0);
        this.cellSize = clamp(config.getInt("cell-size", 500), MIN_CELL_SIZE, MAX_CELL_SIZE);
        this.minSurfaceY = clamp(config.getInt("safety.min-surface-y", 63),
                MIN_SURFACE_Y_FLOOR, MIN_SURFACE_Y_CEILING);
        // A value below 1 would send every player straight to the fallback location.
        // Worst-case allocation is max-scan-attempts * max-candidates ticks, so this stays
        // low: with in-cell searching a cell rarely fails outright.
        this.maxScanAttempts = clamp(config.getInt("safety.max-scan-attempts", 8),
                MIN_SCAN_ATTEMPTS, MAX_SCAN_ATTEMPTS);

        this.placementStrategy = PlacementStrategy.parse(
                config.getString("placement.strategy"), PlacementStrategy.FIRST_SAFE);
        this.stride = clamp(config.getInt("placement.stride", 16), MIN_STRIDE, MAX_STRIDE);
        this.maxCandidates = clamp(config.getInt("placement.max-candidates", 12),
                MIN_CANDIDATES, MAX_CANDIDATES);
        this.heightCeiling = clamp(config.getInt("placement.height-ceiling", 110),
                MIN_SURFACE_Y_FLOOR, MIN_SURFACE_Y_CEILING);
        this.maxPitDepth = clamp(config.getInt("safety.max-pit-depth", 8),
                MIN_PIT_DEPTH, MAX_PIT_DEPTH);
        this.maxRoughness = clamp(config.getInt("safety.max-roughness", 12),
                MIN_ROUGHNESS, MAX_ROUGHNESS);

        this.allocationTrigger = AllocationTrigger.parse(
                config.getString("allocation.trigger"), AllocationTrigger.FIRST_ACTION);
        // 0 disables the backstop entirely, so it is preserved rather than clamped up.
        int configuredTimeout = config.getInt("allocation.action-timeout-seconds", 300);
        this.actionTimeoutSeconds = configuredTimeout <= 0
                ? 0
                : clamp(configuredTimeout, MIN_ACTION_TIMEOUT, MAX_ACTION_TIMEOUT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public String getWorldName() {
        return worldName;
    }

    public int getOriginX() {
        return originX;
    }

    public void setOriginX(int originX) {
        this.originX = originX;
    }

    public int getOriginZ() {
        return originZ;
    }

    public void setOriginZ(int originZ) {
        this.originZ = originZ;
    }

    public int getCellSize() {
        return cellSize;
    }

    public int getMinSurfaceY() {
        return minSurfaceY;
    }

    public int getMaxScanAttempts() {
        return maxScanAttempts;
    }

    public PlacementStrategy getPlacementStrategy() {
        return placementStrategy;
    }

    public int getStride() {
        return stride;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public int getHeightCeiling() {
        return heightCeiling;
    }

    public int getMaxPitDepth() {
        return maxPitDepth;
    }

    public int getMaxRoughness() {
        return maxRoughness;
    }

    public AllocationTrigger getAllocationTrigger() {
        return allocationTrigger;
    }

    /** Seconds before the gate opens regardless, or 0 to wait indefinitely. */
    public int getActionTimeoutSeconds() {
        return actionTimeoutSeconds;
    }
}
