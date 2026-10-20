package com.ninja6.spiralgenesis.structure;

import org.bukkit.Material;

import java.util.Locale;

/**
 * The {@code structure} block of {@code config.yml}: whether a template is placed on each
 * new spawn plot, which one, and how.
 *
 * @param enabled         whether new plots get a structure at all
 * @param file            the template's file name inside {@code plugins/SpiralGenesis/structures}
 * @param offsetX         template X of the block the player stands in
 * @param offsetY         template Y of that block; the layer below it replaces the ground
 * @param offsetZ         template Z of that block
 * @param rotation        how the template is turned on each plot
 * @param includeEntities whether entities saved in the template are placed with it
 * @param terrain         what is done where the ground does not carry the template's floor
 * @param foundationBlock what {@link Terrain#FOUNDATION} fills with
 * @param foundationDepth how far down a foundation may reach before the plot is skipped
 */
public record StructureSettings(boolean enabled, String file, int offsetX, int offsetY,
                                int offsetZ, Rotation rotation, boolean includeEntities,
                                Terrain terrain, Material foundationBlock,
                                int foundationDepth) {

    public static final String DEFAULT_FILE = "spawn-hut.nbt";
    public static final int MIN_FOUNDATION_DEPTH = 1;
    public static final int MAX_FOUNDATION_DEPTH = 64;

    /** How a template is turned on a plot. */
    public enum Rotation {
        /** As saved. */
        NONE,
        /** One of the four quarter turns, chosen per plot. */
        RANDOM,
        /** Turned so the template's west side (negative X) faces the plot's spiral centre. */
        FACING_CENTRE;

        public static Rotation parse(String name, Rotation fallback) {
            return parseEnum(Rotation.class, name, fallback);
        }
    }

    /** What happens where the ground under the template's floor is missing. */
    public enum Terrain {
        /** Leave the plot without a structure. */
        SKIP,
        /** Fill down from the floor to the ground with {@code foundation-block}. */
        FOUNDATION;

        public static Terrain parse(String name, Terrain fallback) {
            return parseEnum(Terrain.class, name, fallback);
        }
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
