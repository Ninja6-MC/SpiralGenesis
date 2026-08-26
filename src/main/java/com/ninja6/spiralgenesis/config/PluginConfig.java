package com.ninja6.spiralgenesis.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    /** The smallest square that is a centre block with one ring of ground around it. */
    static final int MIN_PROTECTION_SIZE = 3;
    /**
     * Above this the claim stops being "a little ground around spawn" and starts eating the
     * plot the player is meant to claim for themselves. Odd, so rounding an even value up
     * can never leave the range.
     */
    static final int MAX_PROTECTION_SIZE = 255;

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
    private final boolean protectionEnabled;
    private final ProtectionProviderType protectionProvider;
    private final int protectionSize;
    private final ClaimOwnership claimOwnership;

    /**
     * Things the server owner should be told about their own file, collected during
     * parsing and logged by the plugin once loading finishes.
     *
     * <p>Kept as text rather than logged from here so that this class stays constructible
     * from a plain {@code FileConfiguration} in a unit test, with no server and no logger,
     * which is what every test in {@code PluginConfigTest} depends on.
     */
    private final List<String> warnings = new ArrayList<>();

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

        this.protectionEnabled = config.getBoolean("protection.enabled", false);
        String configuredProvider = config.getString("protection.provider");
        this.protectionProvider = (configuredProvider == null || configuredProvider.isBlank())
                ? ProtectionProviderType.GRIEF_PREVENTION
                // NONE as the fallback, not the default: a misspelled provider name is not
                // a reason to start creating claims through the one that happens to be
                // default. Silently, it would also be indistinguishable from a working
                // server that simply never claims anything, hence the warning.
                : warnIfCorrected("protection.provider", configuredProvider,
                        ProtectionProviderType.parse(configuredProvider, ProtectionProviderType.NONE));
        this.protectionSize = readProtectionSize(config);
        String configuredOwnership = config.getString("protection.claim-as");
        this.claimOwnership = (configuredOwnership == null || configuredOwnership.isBlank())
                ? ClaimOwnership.ADMIN_CLAIM
                : warnIfCorrected("protection.claim-as", configuredOwnership,
                        ClaimOwnership.parse(configuredOwnership, ClaimOwnership.ADMIN_CLAIM));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Reads {@code protection.size}, correcting it into the supported range and onto an odd
     * number, and naming every correction it makes.
     *
     * <p>Two separate corrections, in this order, because they have different reasons and a
     * server owner needs to be told which one applied. The range check comes first, then the
     * odd check, so a value corrected twice reports both steps and the second message names
     * the number the first one produced rather than the raw one.
     *
     * <p>An even square has no centre block, so "the player stands in the middle of their
     * claim" is not satisfiable at that size and the claim would have to be biased a block
     * to one side. Rounding up keeps the promise and never shrinks anyone's protection. It
     * is silent in the file and loud in the log: the owner reads back a number the plugin
     * did not use, so both numbers are named. Both bounds of the range are odd, so rounding
     * up can never leave it.
     */
    private int readProtectionSize(FileConfiguration config) {
        int configured = config.getInt("protection.size", 9);
        int size = clamp(configured, MIN_PROTECTION_SIZE, MAX_PROTECTION_SIZE);
        if (size != configured) {
            warnings.add("protection.size is " + configured + ", which is outside the supported "
                    + "range " + MIN_PROTECTION_SIZE + "-" + MAX_PROTECTION_SIZE + ". Using "
                    + size + " instead.");
        }
        if (size % 2 == 0) {
            int rounded = size + 1;
            warnings.add("protection.size is " + size + ", which is even and so has no centre "
                    + "block for the spawn point to sit on. Using " + rounded + " instead. Set an "
                    + "odd size to silence this.");
            size = rounded;
        }
        return size;
    }

    /**
     * Reports a configured name the parser did not recognise, and returns the value it fell
     * back to unchanged.
     *
     * <p>A typo in an enum value is the one correction with no visible symptom: the plugin
     * keeps running, the file still reads the way the owner wrote it, and the behaviour is
     * simply not what it says. Comparing the parsed constant's name against the configured
     * text catches it without the parser having to report failure separately.
     */
    private <E extends Enum<E>> E warnIfCorrected(String key, String configured, E parsed) {
        if (!parsed.name().equalsIgnoreCase(configured.trim())) {
            warnings.add(key + " is '" + configured + "', which is not a recognised value. Using "
                    + parsed.name() + " instead.");
        }
        return parsed;
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

    /** Whether a claim is created around a player's spawn point at all. Off by default. */
    public boolean isProtectionEnabled() {
        return protectionEnabled;
    }

    /** Which protection plugin the claim is created through. */
    public ProtectionProviderType getProtectionProvider() {
        return protectionProvider;
    }

    /** The side of the claimed square in blocks. Always odd, so the square has a centre. */
    public int getProtectionSize() {
        return protectionSize;
    }

    /** Whether the claim is an admin claim the player is trusted on, or their own claim. */
    public ClaimOwnership getClaimOwnership() {
        return claimOwnership;
    }

    /**
     * Complaints about this configuration, in the order they were found, for the caller to
     * log. Empty when the file needed no correcting.
     */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
}
