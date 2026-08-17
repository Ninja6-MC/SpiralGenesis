package com.ninja6.spiralgenesis.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginConfigTest {

    private static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)));
    }

    @Test
    @DisplayName("Defaults apply when the configuration is empty")
    void testDefaults() {
        PluginConfig config = parse("");

        assertEquals("world", config.getWorldName());
        assertEquals(0, config.getOriginX());
        assertEquals(0, config.getOriginZ());
        assertEquals(500, config.getCellSize());
        assertEquals(63, config.getMinSurfaceY());
        assertEquals(50, config.getMaxScanAttempts());
    }

    @Test
    @DisplayName("Valid values are read through unchanged")
    void testValidValuesArePreserved() {
        PluginConfig config = parse("""
                origin:
                  world: "spiral_world"
                  x: 1000
                  z: -2000
                cell-size: 250
                safety:
                  min-surface-y: 70
                  max-scan-attempts: 120
                """);

        assertEquals("spiral_world", config.getWorldName());
        assertEquals(1000, config.getOriginX());
        assertEquals(-2000, config.getOriginZ());
        assertEquals(250, config.getCellSize());
        assertEquals(70, config.getMinSurfaceY());
        assertEquals(120, config.getMaxScanAttempts());
    }

    @Test
    @DisplayName("max-scan-attempts below 1 is clamped so allocation never skips straight to fallback")
    void testScanAttemptsLowerClamp() {
        assertEquals(PluginConfig.MIN_SCAN_ATTEMPTS, parse("safety:\n  max-scan-attempts: 0\n").getMaxScanAttempts());
        assertEquals(PluginConfig.MIN_SCAN_ATTEMPTS, parse("safety:\n  max-scan-attempts: -10\n").getMaxScanAttempts());
    }

    @Test
    @DisplayName("max-scan-attempts is capped to bound worst-case allocation time")
    void testScanAttemptsUpperClamp() {
        assertEquals(PluginConfig.MAX_SCAN_ATTEMPTS,
                parse("safety:\n  max-scan-attempts: 100000\n").getMaxScanAttempts());
    }

    @Test
    @DisplayName("cell-size is clamped into a usable range")
    void testCellSizeClamped() {
        assertEquals(PluginConfig.MIN_CELL_SIZE, parse("cell-size: 1\n").getCellSize());
        assertEquals(PluginConfig.MAX_CELL_SIZE, parse("cell-size: 99999999\n").getCellSize());
    }

    @Test
    @DisplayName("min-surface-y is clamped to the world build range")
    void testMinSurfaceYClamped() {
        assertEquals(PluginConfig.MIN_SURFACE_Y_FLOOR, parse("safety:\n  min-surface-y: -5000\n").getMinSurfaceY());
        assertEquals(PluginConfig.MIN_SURFACE_Y_CEILING, parse("safety:\n  min-surface-y: 5000\n").getMinSurfaceY());
    }

    @Test
    @DisplayName("A blank world name falls back to the default rather than failing lookup")
    void testBlankWorldName() {
        assertEquals("world", parse("origin:\n  world: \"\"\n").getWorldName());
        assertEquals("world", parse("origin:\n  world: \"   \"\n").getWorldName());
    }
}
