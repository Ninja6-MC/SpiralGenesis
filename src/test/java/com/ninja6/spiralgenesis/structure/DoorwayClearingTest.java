package com.ninja6.spiralgenesis.structure;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code structure.clear-doorways}, against a MockBukkit world with ground at Y=20 and a
 * 7x7 hut with doors west and east of the spawn at the origin. The doors land at X -3 and 3,
 * so their approaches are the columns (-4, 0) and (4, 0), from Y=21 up.
 */
class DoorwayClearingTest {

    private static final int GROUND = 20;
    private static final int FEET = GROUND + 1;

    private ServerMock server;
    private WorldMock world;
    private YamlDataStorage storage;
    private TestStructureEnvironment env;
    private SpawnStructures structures;
    private TestTemplate hut;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = new YamlDataStorage(plugin);
        storage.load();
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = GROUND - 4; y <= GROUND; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE);
                }
            }
        }
        env = new TestStructureEnvironment(storage, plugin.getDataFolder());
        hut = TestTemplate.hutWithDoors("spawn-hut.nbt", 7);
        env.templates.put("spawn-hut.nbt", hut);
        structures = new SpawnStructures(env);
        structures.reload();
        assertTrue(storage.setSpawn(owner, new Location(world, 0.5, FEET, 0.5), 0, 0, 0, 0,
                "Builder", "JAVA", false));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Material at(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType();
    }

    private void set(int x, int y, int z, Material type) {
        world.getBlockAt(x, y, z).setType(type);
    }

    private SpawnStructures.Outcome placeNow(boolean dryRun) {
        AtomicReference<SpawnStructures.Outcome> outcome = new AtomicReference<>();
        structures.placeNow(owner, "Builder", dryRun, outcome::set);
        env.drain();
        assertNotNull(outcome.get(), "an outcome is always reported");
        return outcome.get();
    }

    @Test
    @DisplayName("a hillside and plants in front of the doors are cleared, two high and one deep")
    void clearsNaturalTerrain() {
        set(-4, FEET, 0, Material.DIRT);
        set(-4, FEET + 1, 0, Material.GRASS_BLOCK);
        set(-4, FEET + 2, 0, Material.STONE);
        set(-5, FEET, 0, Material.DIRT);
        set(4, FEET, 0, Material.TALL_GRASS);
        set(4, FEET + 1, 0, Material.OAK_LEAVES);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.AIR, at(-4, FEET, 0));
        assertEquals(Material.AIR, at(-4, FEET + 1, 0));
        assertEquals(Material.STONE, at(-4, FEET + 2, 0), "only two high");
        assertEquals(Material.DIRT, at(-5, FEET, 0), "only one deep");
        assertEquals(Material.AIR, at(4, FEET, 0));
        assertEquals(Material.AIR, at(4, FEET + 1, 0));
        assertEquals(Material.STONE, at(-4, GROUND, 0), "the ground in front stays");
        assertEquals(Material.OAK_DOOR, at(-3, FEET, 0));
        assertTrue(outcome.message().contains("clearing 4 blocks in front of its doors"),
                outcome.message());
    }

    @Test
    @DisplayName("the dry run says what it would clear and changes nothing")
    void dryRunClearsNothing() {
        set(-4, FEET, 0, Material.DIRT);

        SpawnStructures.Outcome outcome = placeNow(true);

        assertTrue(outcome.wouldPlace(), outcome.message());
        assertTrue(outcome.message().contains("clearing 1 block in front of its doors"),
                outcome.message());
        assertEquals(Material.DIRT, at(-4, FEET, 0));
        assertEquals(0, hut.placements);
    }

    @Test
    @DisplayName("player blocks and block entities stay, are reported, and do not stop placement")
    void leavesPlayerBlocks() {
        set(-4, FEET, 0, Material.OAK_PLANKS);
        set(-4, FEET + 1, 0, Material.DIRT);
        set(4, FEET + 1, 0, Material.CHEST);

        SpawnStructures.Outcome dry = placeNow(true);
        assertTrue(dry.wouldPlace(), dry.message());
        assertTrue(dry.message().contains(
                "oak_planks, which is not natural terrain, at (-4, 21, 0)"), dry.message());
        assertTrue(dry.message().contains("a block entity (chest) at (4, 22, 0)"), dry.message());

        SpawnStructures.Outcome placed = placeNow(false);

        assertTrue(placed.placed(), placed.message());
        assertEquals(Material.OAK_PLANKS, at(-4, FEET, 0));
        assertEquals(Material.DIRT, at(-4, FEET + 1, 0),
                "an approach that cannot be cleared whole is left whole");
        assertEquals(Material.CHEST, at(4, FEET + 1, 0));
        assertTrue(placed.message().contains("Doorways left as they are"), placed.message());
    }

    @Test
    @DisplayName("structure.clear-doorways false leaves the approaches as they are")
    void switchedOff() {
        env.yaml.set("structure.clear-doorways", false);
        env.reconfigure();
        set(-4, FEET, 0, Material.DIRT);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.DIRT, at(-4, FEET, 0));
        assertFalse(outcome.message().contains("clearing"), outcome.message());
    }

    @Test
    @DisplayName("sand resting on a cleared block is cleared too, so it cannot fall in")
    void fallingBlocksAbove() {
        set(-4, FEET, 0, Material.SAND);
        set(-4, FEET + 1, 0, Material.SAND);
        set(-4, FEET + 2, 0, Material.SAND);
        set(-4, FEET + 3, 0, Material.GRAVEL);
        set(-4, FEET + 4, 0, Material.STONE);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        for (int y = FEET; y <= FEET + 3; y++) {
            assertEquals(Material.AIR, at(-4, y, 0), "Y=" + y);
        }
        assertEquals(Material.STONE, at(-4, FEET + 4, 0));
    }

    @Test
    @DisplayName("the column above a cleared doorway stops at a block the template stores")
    void storedBlockAboveIsKept() {
        List<Template.Block> blocks = new ArrayList<>(hut.blocks());
        // A step under the west approach and an awning one block over its head.
        blocks.add(new Template.Block(-1, 0, 3, Material.COBBLESTONE, false));
        blocks.add(new Template.Block(-1, 3, 3, Material.OAK_SLAB, false));
        TestTemplate awning = new TestTemplate("spawn-hut.nbt", blocks, hut.doors());
        env.templates.put("spawn-hut.nbt", awning);
        structures.reload();
        set(-4, FEET, 0, Material.DIRT);
        set(-4, FEET + 1, 0, Material.DIRT);
        set(-4, FEET + 2, 0, Material.SAND);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.AIR, at(-4, FEET, 0));
        assertEquals(Material.AIR, at(-4, FEET + 1, 0));
        assertEquals(Material.OAK_SLAB, at(-4, FEET + 2, 0), "the template's own block stays");
        assertTrue(outcome.message().contains("clearing 2 blocks"), outcome.message());
    }

    @Test
    @DisplayName("plants and snow standing on a cleared head block are cleared with it")
    void plantsAboveAreCleared() {
        set(-4, FEET + 1, 0, Material.GRASS_BLOCK);
        set(-4, FEET + 2, 0, Material.POPPY);
        set(4, FEET + 1, 0, Material.DIRT);
        set(4, FEET + 2, 0, Material.SNOW);
        set(4, FEET + 3, 0, Material.STONE);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.AIR, at(-4, FEET + 1, 0));
        assertEquals(Material.AIR, at(-4, FEET + 2, 0));
        assertEquals(Material.AIR, at(4, FEET + 2, 0));
        assertEquals(Material.STONE, at(4, FEET + 3, 0), "a solid block above is not followed");
    }

    @Test
    @DisplayName("a doorway under a falling block that is not natural is left and reported")
    void fallingPlayerBlockAbove() {
        set(-4, FEET + 1, 0, Material.DIRT);
        set(-4, FEET + 2, 0, Material.RED_CONCRETE_POWDER);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.DIRT, at(-4, FEET + 1, 0));
        assertTrue(outcome.message().contains("a block that would fall in"), outcome.message());
    }

    @Test
    @DisplayName("an unsafe spawn puts the cleared blocks back as well")
    void rollbackRestoresDoorways() {
        set(-4, FEET, 0, Material.DIRT);
        env.safe = spawn -> false;

        SpawnStructures.Outcome outcome = placeNow(false);

        assertFalse(outcome.placed(), outcome.message());
        assertEquals(Material.DIRT, at(-4, FEET, 0));
        assertNull(storage.getStructure(0, 0));
    }

    @Test
    @DisplayName("an approach outside protection.size is reported and not touched")
    void outsideProtectionSize() {
        env.yaml.set("protection.size", 7);
        env.reconfigure();
        set(-4, FEET, 0, Material.DIRT);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.DIRT, at(-4, FEET, 0));
        assertTrue(outcome.message().contains("is outside protection.size"), outcome.message());
    }

    @Test
    @DisplayName("an approach outside the player's actual claim is not touched")
    void outsideClaim() {
        env.claimBounds(-3, -4, 4, 4);
        set(-4, FEET, 0, Material.DIRT);
        set(4, FEET, 0, Material.DIRT);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.DIRT, at(-4, FEET, 0));
        assertEquals(Material.AIR, at(4, FEET, 0));
        assertTrue(outcome.message().contains("outside the player's claim"), outcome.message());
    }

    @Test
    @DisplayName("under FOUNDATION a doorway over a drop gets a landing down to the ground")
    void foundationLanding() {
        env.yaml.set("structure.terrain", "FOUNDATION");
        env.reconfigure();
        // The ground falls away on the west side, under the floor's edge and the approach.
        for (int y = GROUND - 2; y <= GROUND; y++) {
            set(-4, y, 0, Material.AIR);
        }
        set(-4, GROUND - 1, 0, Material.SHORT_GRASS);
        set(-3, GROUND - 1, 0, Material.AIR);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.COBBLESTONE, at(-4, GROUND, 0), "level with the floor");
        assertEquals(Material.COBBLESTONE, at(-4, GROUND - 1, 0));
        assertEquals(Material.COBBLESTONE, at(-4, GROUND - 2, 0));
        assertEquals(Material.STONE, at(-4, GROUND - 3, 0), "down to the ground");
        assertTrue(outcome.message().contains("building 3 landing blocks"), outcome.message());
    }

    @Test
    @DisplayName("under SKIP no landing is built; a drop of one is a step down")
    void noLandingUnderSkip() {
        set(4, GROUND, 0, Material.AIR);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.AIR, at(4, GROUND, 0));
    }

    @Test
    @DisplayName("a landing past foundation-depth is reported, and the doorway still cleared")
    void landingTooDeep() {
        env.yaml.set("structure.terrain", "FOUNDATION");
        env.yaml.set("structure.foundation-depth", 1);
        env.reconfigure();
        for (int y = GROUND - 2; y <= GROUND; y++) {
            set(4, y, 0, Material.AIR);
        }
        set(4, FEET, 0, Material.FERN);

        SpawnStructures.Outcome outcome = placeNow(false);

        assertTrue(outcome.placed(), outcome.message());
        assertEquals(Material.AIR, at(4, FEET, 0));
        assertEquals(Material.AIR, at(4, GROUND, 0));
        assertTrue(outcome.message().contains("has no landing"), outcome.message());
    }
}
