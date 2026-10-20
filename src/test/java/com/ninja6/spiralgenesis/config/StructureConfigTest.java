package com.ninja6.spiralgenesis.config;

import com.ninja6.spiralgenesis.structure.StructureSettings;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code structure} block of {@code config.yml}. */
class StructureConfigTest {

    @Test
    @DisplayName("the shipped config.yml has structures off, on the hut, as the defaults say")
    void shippedDefaults() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (Exception e) {
            throw new IOException(e);
        }
        PluginConfig config = new PluginConfig(yaml);
        StructureSettings shipped = config.getStructure();
        StructureSettings absent = new PluginConfig(new YamlConfiguration()).getStructure();
        assertEquals(absent, shipped, "a file without the block behaves as the shipped one");
        assertFalse(shipped.enabled());
        assertEquals("spawn-hut.nbt", shipped.file());
        assertEquals(3, shipped.offsetX());
        assertEquals(1, shipped.offsetY());
        assertEquals(3, shipped.offsetZ());
        assertEquals(StructureSettings.Rotation.NONE, shipped.rotation());
        assertEquals(StructureSettings.Terrain.SKIP, shipped.terrain());
        assertEquals(Material.COBBLESTONE, shipped.foundationBlock());
        assertEquals(8, shipped.foundationDepth());
        assertTrue(config.getWarnings().isEmpty(), config.getWarnings().toString());
    }

    @Test
    @DisplayName("every corrected structure value is named in a warning")
    void corrections() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("structure.file", "../data.yml");
        yaml.set("structure.rotation", "sideways");
        yaml.set("structure.terrain", "flatten");
        yaml.set("structure.foundation-block", "torch");
        yaml.set("structure.foundation-depth", 500);
        PluginConfig config = new PluginConfig(yaml);
        StructureSettings settings = config.getStructure();

        assertEquals("spawn-hut.nbt", settings.file());
        assertEquals(StructureSettings.Rotation.NONE, settings.rotation());
        assertEquals(StructureSettings.Terrain.SKIP, settings.terrain());
        assertEquals(Material.COBBLESTONE, settings.foundationBlock());
        assertEquals(64, settings.foundationDepth());
        assertEquals(5, config.getWarnings().size(), config.getWarnings().toString());
    }

    @Test
    @DisplayName("names in either spelling are accepted without a warning")
    void spellings() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("structure.rotation", "facing-centre");
        yaml.set("structure.terrain", "foundation");
        yaml.set("structure.foundation-block", "minecraft:stone_bricks");
        PluginConfig config = new PluginConfig(yaml);

        assertEquals(StructureSettings.Rotation.FACING_CENTRE, config.getStructure().rotation());
        assertEquals(StructureSettings.Terrain.FOUNDATION, config.getStructure().terrain());
        assertEquals(Material.STONE_BRICKS, config.getStructure().foundationBlock());
        assertTrue(config.getWarnings().isEmpty(), config.getWarnings().toString());
    }

    @Test
    @DisplayName("only a plain .nbt file name is read")
    void plainNames() {
        assertTrue(PluginConfig.isPlainTemplateName("myhut.nbt"));
        assertFalse(PluginConfig.isPlainTemplateName("myhut.schem"));
        assertFalse(PluginConfig.isPlainTemplateName("sub/myhut.nbt"));
        assertFalse(PluginConfig.isPlainTemplateName("..\\myhut.nbt"));
        assertFalse(PluginConfig.isPlainTemplateName("C:myhut.nbt"));
        assertFalse(PluginConfig.isPlainTemplateName(".nbt"));
    }
}
