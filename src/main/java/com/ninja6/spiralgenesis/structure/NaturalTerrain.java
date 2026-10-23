package com.ninja6.spiralgenesis.structure;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The blocks that count as natural terrain, which doorway clearing may remove: soil, stone,
 * sand, gravel, clay, terracotta, snow, natural logs, leaves and plants.
 *
 * <p>Matched by name rather than by tag, so the set is the same on every supported version,
 * blocks newer than the API this is built against (leaf litter, bushes, pale moss) are
 * included where the server has them, and the rule can be tested without a server. Fluids,
 * stripped logs, planks, crops and anything with a block entity are never in it.
 */
final class NaturalTerrain {

    private static final List<String> NAMES = List.of(
            // Soil and stone.
            "GRASS_BLOCK", "DIRT", "COARSE_DIRT", "PODZOL", "ROOTED_DIRT", "MYCELIUM", "MUD",
            "MOSS_BLOCK", "PALE_MOSS_BLOCK", "STONE", "GRANITE", "DIORITE", "ANDESITE",
            "DEEPSLATE", "TUFF", "CALCITE", "SAND", "RED_SAND", "GRAVEL", "CLAY", "SANDSTONE",
            "RED_SANDSTONE", "TERRACOTTA", "SNOW", "SNOW_BLOCK", "POWDER_SNOW",
            "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS",
            // Plants.
            "GRASS", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "BUSH",
            "FIREFLY_BUSH", "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "VINE", "GLOW_LICHEN",
            "HANGING_ROOTS", "MOSS_CARPET", "PALE_MOSS_CARPET", "PALE_HANGING_MOSS",
            "LEAF_LITTER", "PINK_PETALS", "WILDFLOWERS", "SWEET_BERRY_BUSH", "BROWN_MUSHROOM",
            "RED_MUSHROOM", "AZALEA", "FLOWERING_AZALEA", "MANGROVE_PROPAGULE", "CACTUS",
            "SUGAR_CANE", "BAMBOO", "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM", "SMALL_DRIPLEAF",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY",
            "CORNFLOWER", "LILY_OF_THE_VALLEY", "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM",
            "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY");

    private static final Set<Material> NATURAL = build();

    private NaturalTerrain() { }

    private static Set<Material> build() {
        Set<Material> natural = EnumSet.noneOf(Material.class);
        for (String name : NAMES) {
            Material material = Material.getMaterial(name);
            if (material != null) {
                natural.add(material);
            }
        }
        for (Material material : Material.values()) {
            String name = material.name();
            // Potted plants are placed by players; legacy names are not real blocks.
            if (name.startsWith("LEGACY_") || name.startsWith("POTTED_") || !material.isBlock()) {
                continue;
            }
            if (name.endsWith("_LEAVES") || name.endsWith("_SAPLING")
                    || name.endsWith("_TULIP")
                    || name.endsWith("_LOG") && !name.startsWith("STRIPPED_")
                    || name.endsWith("_TERRACOTTA") && !name.endsWith("_GLAZED_TERRACOTTA")) {
                natural.add(material);
            }
        }
        return natural;
    }

    /** Whether {@code material} is natural terrain that a doorway may be cleared of. */
    static boolean contains(Material material) {
        return NATURAL.contains(material);
    }
}
