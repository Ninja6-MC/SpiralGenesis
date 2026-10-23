package com.ninja6.spiralgenesis.structure;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the approach in front of each door lands, for every rotation. */
class DoorwaysTest {

    private static final StructureSettings HUT = new StructureSettings(true, "spawn-hut.nbt", 3, 1,
            3, StructureSettings.Rotation.NONE, false, StructureSettings.Terrain.SKIP,
            Material.COBBLESTONE, 8, true);

    private static List<Doorways.Approach> approaches(Template template, StructureRotation rotation) {
        return Doorways.of(template, Placement.of(template, HUT, 0, 21, 0, rotation));
    }

    @Test
    @DisplayName("each door gets one approach, outside the walls, two high, with the floor under it")
    void outsideEachDoor() {
        List<Doorways.Approach> approaches = approaches(TestTemplate.hutWithDoors("hut", 7),
                StructureRotation.NONE);

        assertEquals(2, approaches.size());
        Doorways.Approach west = approaches.get(0);
        assertEquals(new Doorways.Cell(-4, 21, 0), west.feet());
        assertEquals(new Doorways.Cell(-4, 22, 0), west.head());
        assertEquals(new Doorways.Cell(-4, 20, 0), west.below());
        assertEquals(List.of(west.feet(), west.head()), west.clear());
        assertTrue(west.landing(), "the template stores nothing under the approach");
        assertEquals(new Doorways.Cell(4, 21, 0), approaches.get(1).feet());
    }

    @Test
    @DisplayName("the approaches turn with the template")
    void turned() {
        TestTemplate hut = TestTemplate.hutWithDoors("hut", 7);

        // The west door turned a quarter clockwise faces north.
        List<Doorways.Approach> clockwise = approaches(hut, StructureRotation.CLOCKWISE_90);
        assertEquals(new Doorways.Cell(0, 21, -4), clockwise.get(0).feet());
        assertEquals(new Doorways.Cell(0, 21, 4), clockwise.get(1).feet());

        List<Doorways.Approach> half = approaches(hut, StructureRotation.CLOCKWISE_180);
        assertEquals(new Doorways.Cell(4, 21, 0), half.get(0).feet());
        assertEquals(new Doorways.Cell(-4, 21, 0), half.get(1).feet());

        List<Doorways.Approach> anticlockwise = approaches(hut,
                StructureRotation.COUNTERCLOCKWISE_90);
        assertEquals(new Doorways.Cell(0, 21, 4), anticlockwise.get(0).feet());
    }

    @Test
    @DisplayName("a door between two stored rooms has no approach")
    void interiorDoor() {
        TestTemplate hut = TestTemplate.hutWithDoors("hut", 7);
        TestTemplate inner = new TestTemplate("hut", hut.blocks(),
                List.of(new Template.Door(3, 1, 3, BlockFace.NORTH)));

        assertTrue(approaches(inner, StructureRotation.NONE).isEmpty());
    }

    @Test
    @DisplayName("only the cells the template does not store are cleared")
    void storedHeadIsLeftToTheTemplate() {
        TestTemplate hut = TestTemplate.hutWithDoors("hut", 7);
        List<Template.Block> blocks = new ArrayList<>(hut.blocks());
        // An awning over the west approach and a step under it.
        blocks.add(new Template.Block(-1, 2, 3, Material.OAK_SLAB, false));
        blocks.add(new Template.Block(-1, 0, 3, Material.COBBLESTONE, false));
        TestTemplate awning = new TestTemplate("hut", blocks, hut.doors());

        Doorways.Approach west = approaches(awning, StructureRotation.NONE).get(0);

        assertEquals(List.of(west.feet()), west.clear());
        assertFalse(west.landing());
    }
}
