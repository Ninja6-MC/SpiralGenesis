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
        assertEquals(8, config.getMaxScanAttempts());
        assertEquals(PlacementStrategy.FIRST_SAFE, config.getPlacementStrategy());
        assertEquals(16, config.getStride());
        assertEquals(12, config.getMaxCandidates());
        assertEquals(110, config.getHeightCeiling());
        assertEquals(8, config.getMaxPitDepth());
        assertEquals(12, config.getMaxRoughness());
        assertEquals(AllocationTrigger.FIRST_ACTION, config.getAllocationTrigger());
        assertEquals(300, config.getActionTimeoutSeconds());
    }

    @Test
    @DisplayName("The allocation trigger is read, and an unrecognised one falls back to gating")
    void testAllocationTrigger() {
        assertEquals(AllocationTrigger.ON_JOIN, parse("""
                allocation:
                  trigger: ON_JOIN
                """).getAllocationTrigger());

        assertEquals(AllocationTrigger.FIRST_ACTION, parse("""
                allocation:
                  trigger: first_action
                """).getAllocationTrigger());

        // A typo must not silently open the gate: that would allocate players who are
        // still held in limbo, which is the failure the trigger exists to prevent.
        assertEquals(AllocationTrigger.FIRST_ACTION, parse("""
                allocation:
                  trigger: ON_JOIM
                """).getAllocationTrigger());
    }

    @Test
    @DisplayName("A zero action timeout disables the backstop rather than being clamped up")
    void testActionTimeoutZeroIsPreserved() {
        assertEquals(0, parse("""
                allocation:
                  action-timeout-seconds: 0
                """).getActionTimeoutSeconds());

        assertEquals(0, parse("""
                allocation:
                  action-timeout-seconds: -5
                """).getActionTimeoutSeconds());
    }

    @Test
    @DisplayName("A non-zero action timeout is clamped away from firing mid-login")
    void testActionTimeoutClamps() {
        // A few seconds would fire while the player is still typing their password.
        assertEquals(PluginConfig.MIN_ACTION_TIMEOUT, parse("""
                allocation:
                  action-timeout-seconds: 1
                """).getActionTimeoutSeconds());

        assertEquals(PluginConfig.MAX_ACTION_TIMEOUT, parse("""
                allocation:
                  action-timeout-seconds: 999999
                """).getActionTimeoutSeconds());

        assertEquals(300, parse("""
                allocation:
                  action-timeout-seconds: 300
                """).getActionTimeoutSeconds());
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
    @DisplayName("An unrecognised placement strategy falls back to the default")
    void testUnknownStrategyFallsBack() {
        assertEquals(PlacementStrategy.FIRST_SAFE,
                parse("placement:\n  strategy: \"NEAR_SOUTH\"\n").getPlacementStrategy());
        assertEquals(PlacementStrategy.FLATTEST,
                parse("placement:\n  strategy: \"flattest\"\n").getPlacementStrategy(),
                "names should be case-insensitive");
    }

    @Test
    @DisplayName("stride is clamped to at least one chunk so candidates never share a chunk")
    void testStrideClamped() {
        assertEquals(PluginConfig.MIN_STRIDE, parse("placement:\n  stride: 1\n").getStride());
        assertEquals(PluginConfig.MAX_STRIDE, parse("placement:\n  stride: 99999\n").getStride());
    }

    @Test
    @DisplayName("max-candidates is clamped to bound per-cell chunk generation")
    void testMaxCandidatesClamped() {
        assertEquals(PluginConfig.MIN_CANDIDATES,
                parse("placement:\n  max-candidates: 0\n").getMaxCandidates());
        assertEquals(PluginConfig.MAX_CANDIDATES,
                parse("placement:\n  max-candidates: 5000\n").getMaxCandidates());
    }

    @Test
    @DisplayName("Terrain shape thresholds are clamped above zero so they stay meaningful")
    void testShapeThresholdsClamped() {
        assertEquals(PluginConfig.MIN_PIT_DEPTH, parse("safety:\n  max-pit-depth: 0\n").getMaxPitDepth());
        assertEquals(PluginConfig.MAX_PIT_DEPTH, parse("safety:\n  max-pit-depth: 9999\n").getMaxPitDepth());
        assertEquals(PluginConfig.MIN_ROUGHNESS, parse("safety:\n  max-roughness: -3\n").getMaxRoughness());
        assertEquals(PluginConfig.MAX_ROUGHNESS, parse("safety:\n  max-roughness: 9999\n").getMaxRoughness());
    }

    @Test
    @DisplayName("A blank world name falls back to the default rather than failing lookup")
    void testBlankWorldName() {
        assertEquals("world", parse("origin:\n  world: \"\"\n").getWorldName());
        assertEquals("world", parse("origin:\n  world: \"   \"\n").getWorldName());
    }
}
