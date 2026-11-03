package com.ninja6.spiralgenesis.terrain;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The blocks that are natural plants: grass, flowers, bushes, bamboo, sugar cane, cactus,
 * saplings, natural logs and leaves.
 *
 * <p>Two rules read it. Allocation and the in-cell repair take the surface of a column as
 * the first block under its plants, so a spawn is never stood on a bamboo stalk or a log.
 * Doorway clearing removes them, with soil and stone, as natural terrain.
 *
 * <p>Matched by name rather than by tag, so the set is the same on every supported version,
 * blocks newer than the API this is built against (bushes, leaf litter, pale moss) are
 * included where the server has them, and the rule can be tested without a server. Stripped
 * logs, planks, crops and potted plants are never in it.
 */
public final class NaturalPlants {

    private static final List<String> NAMES = List.of(
            "GRASS", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "BUSH",
            "FIREFLY_BUSH", "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "VINE", "GLOW_LICHEN",
            "HANGING_ROOTS", "MOSS_CARPET", "PALE_MOSS_CARPET", "PALE_HANGING_MOSS",
            "LEAF_LITTER", "PINK_PETALS", "WILDFLOWERS", "SWEET_BERRY_BUSH", "BROWN_MUSHROOM",
            "RED_MUSHROOM", "AZALEA", "FLOWERING_AZALEA", "MANGROVE_PROPAGULE", "CACTUS",
            "SUGAR_CANE", "BAMBOO", "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM", "SMALL_DRIPLEAF",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY",
            "CORNFLOWER", "LILY_OF_THE_VALLEY", "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM",
            "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY");

    private static final Set<Material> PLANTS = build();

    private NaturalPlants() { }

    private static Set<Material> build() {
        Set<Material> plants = EnumSet.noneOf(Material.class);
        for (String name : NAMES) {
            Material material = Material.getMaterial(name);
            if (material != null) {
                plants.add(material);
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
                    || name.endsWith("_LOG") && !name.startsWith("STRIPPED_")) {
                plants.add(material);
            }
        }
        return plants;
    }

    /** Whether {@code material} is a natural plant. */
    public static boolean contains(Material material) {
        return PLANTS.contains(material);
    }
}
