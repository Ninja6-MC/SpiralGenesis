package com.ninja6.spiralgenesis.structure;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which blocks a doorway may be cleared of. */
class NaturalTerrainTest {

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"GRASS_BLOCK", "DIRT", "STONE", "DEEPSLATE",
            "SAND", "GRAVEL", "SNOW", "OAK_LOG", "MANGROVE_LOG", "OAK_LEAVES", "AZALEA_LEAVES",
            "SHORT_GRASS", "TALL_GRASS", "FERN", "POPPY", "RED_TULIP", "OAK_SAPLING",
            "PINK_PETALS", "WHITE_TERRACOTTA", "TERRACOTTA", "SWEET_BERRY_BUSH", "MOSS_CARPET"})
    @DisplayName("natural terrain and plants are cleared")
    void natural(Material material) {
        assertTrue(NaturalTerrain.contains(material), material.name());
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"AIR", "WATER", "LAVA", "BEDROCK", "COBBLESTONE",
            "OAK_PLANKS", "STRIPPED_OAK_LOG", "OAK_WOOD", "POTTED_POPPY", "POTTED_OAK_SAPLING",
            "WHITE_GLAZED_TERRACOTTA", "WHEAT", "CHEST", "TORCH", "OAK_DOOR", "GLASS",
            "STONE_BRICKS", "FARMLAND", "DIRT_PATH"})
    @DisplayName("fluids, building blocks, crops and anything placed are left")
    void notNatural(Material material) {
        assertFalse(NaturalTerrain.contains(material), material.name());
    }
}
