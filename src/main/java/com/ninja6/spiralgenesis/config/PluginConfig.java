package com.ninja6.spiralgenesis.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Strongly typed configuration container and validator for SpiralGenesis.
 */
public class PluginConfig {

    private final String worldName;
    private int originX;
    private int originZ;
    private final int cellSize;
    private final int minSurfaceY;
    private final int maxScanAttempts;
    private final boolean metricsEnabled;

    public PluginConfig(FileConfiguration config) {
        this.worldName = config.getString("origin.world", "world");
        this.originX = config.getInt("origin.x", 0);
        this.originZ = config.getInt("origin.z", 0);
        this.cellSize = Math.max(50, config.getInt("cell-size", 500));
        this.minSurfaceY = config.getInt("safety.min-surface-y", 63);
        this.maxScanAttempts = config.getInt("safety.max-scan-attempts", 50);
        this.metricsEnabled = config.getBoolean("metrics.enabled", true);
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

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }
}
