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

    private final String worldName;
    private int originX;
    private int originZ;
    private final int cellSize;
    private final int minSurfaceY;
    private final int maxScanAttempts;

    public PluginConfig(FileConfiguration config) {
        String configuredWorld = config.getString("origin.world", "world");
        this.worldName = (configuredWorld == null || configuredWorld.isBlank()) ? "world" : configuredWorld;
        this.originX = config.getInt("origin.x", 0);
        this.originZ = config.getInt("origin.z", 0);
        this.cellSize = clamp(config.getInt("cell-size", 500), MIN_CELL_SIZE, MAX_CELL_SIZE);
        this.minSurfaceY = clamp(config.getInt("safety.min-surface-y", 63),
                MIN_SURFACE_Y_FLOOR, MIN_SURFACE_Y_CEILING);
        // A value below 1 would send every player straight to the fallback location.
        this.maxScanAttempts = clamp(config.getInt("safety.max-scan-attempts", 50),
                MIN_SCAN_ATTEMPTS, MAX_SCAN_ATTEMPTS);
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
}
