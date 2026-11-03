package com.ninja6.spiralgenesis.terrain;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which blocks allocation looks under for the ground. */
class NaturalPlantsTest {

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"BAMBOO", "SUGAR_CANE", "CACTUS", "OAK_LOG",
            "JUNGLE_LOG", "MANGROVE_LOG", "OAK_LEAVES", "SHORT_GRASS", "TALL_GRASS", "POPPY",
            "RED_TULIP", "OAK_SAPLING", "SWEET_BERRY_BUSH", "VINE"})
    @DisplayName("stalks, trunks, leaves and smaller plants are plants")
    void plants(Material material) {
        assertTrue(NaturalPlants.contains(material), material.name());
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"AIR", "WATER", "LAVA", "GRASS_BLOCK", "DIRT",
            "PODZOL", "STONE", "SAND", "SNOW", "STRIPPED_OAK_LOG", "OAK_PLANKS", "OAK_WOOD",
            "POTTED_BAMBOO", "WHEAT", "CHEST"})
    @DisplayName("ground, fluids, building blocks, crops and potted plants are not")
    void notPlants(Material material) {
        assertFalse(NaturalPlants.contains(material), material.name());
    }
}
