package com.ninja6.spiralgenesis.structure;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door halves broken by a structure placement are set again, without neighbour updates, and
 * the door they dropped is taken back.
 *
 * <p>MockBukkit has no door block data, no structure manager and no
 * {@code setBlockData(data, false)}, so doors, the structure file and the writer are stand-ins.
 */
class DoorRepairTest {

    private static final int FLOOR = 20;

    private ServerMock server;
    private WorldMock world;
    /** Every block the writer set, in order. */
    private final List<Block> written = new ArrayList<>();
    private final DoorRepair.Writer writer = (block, data) -> {
        written.add(block);
        block.setBlockData(data);
    };

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * A 3 x 3 hut floor with a west door at the template's {@code (0, 1, 1)}, as the
     * structure file stores it. Placing it sets every state; {@code damage} then runs as the
     * shape updates of a real placement could.
     */
    private BukkitTemplate hut(Consumer<Location> damage) {
        List<BlockState> states = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                states.add(state(x, 0, z, server.createBlockData(Material.COBBLESTONE)));
            }
        }
        states.add(state(0, 1, 1, door(Bisected.Half.BOTTOM, BlockFace.WEST)));
        states.add(state(0, 2, 1, door(Bisected.Half.TOP, BlockFace.WEST)));
        return new BukkitTemplate("spawn-hut.nbt", structure(states, damage), writer);
    }

    private Block at(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    private static void assertDoor(Block block, Bisected.Half half, BlockFace facing) {
        assertEquals(Material.OAK_DOOR, block.getType(), "door at " + block.getLocation());
        Door door = assertInstanceOf(Door.class, block.getBlockData());
        assertEquals(half, door.getHalf());
        assertEquals(facing, door.getFacing());
    }

    @Test
    @DisplayName("Both halves broken by placement are set again, and the dropped door is removed")
    void restoresBrokenDoor() {
        Location origin = new Location(world, 10, FLOOR, 10);
        at(9, FLOOR + 1, 11).setType(Material.PINK_PETALS);
        List<Item> drops = new ArrayList<>();
        BukkitTemplate template = hut(o -> {
            at(10, FLOOR + 1, 11).setType(Material.AIR);
            at(10, FLOOR + 2, 11).setType(Material.AIR);
            drops.add(world.dropItem(new Location(world, 10.5, FLOOR + 1.3, 11.5),
                    new ItemStack(Material.OAK_DOOR)));
        });

        template.place(origin, StructureRotation.NONE, false);

        assertDoor(at(10, FLOOR + 1, 11), Bisected.Half.BOTTOM, BlockFace.WEST);
        assertDoor(at(10, FLOOR + 2, 11), Bisected.Half.TOP, BlockFace.WEST);
        assertEquals(2, written.size());
        assertFalse(drops.get(0).isValid(), "the door the broken half dropped is taken back");
        assertEquals(Material.PINK_PETALS, at(9, FLOOR + 1, 11).getType(),
                "the natural block beside the doorway is left alone");
    }

    @Test
    @DisplayName("A door left standing is not set again")
    void leavesStandingDoor() {
        BukkitTemplate template = hut(o -> { });

        template.place(new Location(world, 10, FLOOR, 10), StructureRotation.NONE, false);

        assertDoor(at(10, FLOOR + 1, 11), Bisected.Half.BOTTOM, BlockFace.WEST);
        assertDoor(at(10, FLOOR + 2, 11), Bisected.Half.TOP, BlockFace.WEST);
        assertTrue(written.isEmpty());
    }

    @Test
    @DisplayName("Only the half that broke is set again")
    void restoresOneHalf() {
        BukkitTemplate template = hut(o -> at(10, FLOOR + 2, 11).setType(Material.AIR));

        template.place(new Location(world, 10, FLOOR, 10), StructureRotation.NONE, false);

        assertDoor(at(10, FLOOR + 2, 11), Bisected.Half.TOP, BlockFace.WEST);
        assertEquals(List.of(at(10, FLOOR + 2, 11)), written);
    }

    @Test
    @DisplayName("A half turned into the other half is set back to the half the template stores")
    void restoresWrongHalf() {
        BukkitTemplate template = hut(o -> at(10, FLOOR + 2, 11)
                .setBlockData(door(Bisected.Half.BOTTOM, BlockFace.WEST)));

        template.place(new Location(world, 10, FLOOR, 10), StructureRotation.NONE, false);

        assertDoor(at(10, FLOOR + 2, 11), Bisected.Half.TOP, BlockFace.WEST);
        assertEquals(1, written.size());
    }

    @Test
    @DisplayName("A turned template has its door set again where it landed, facing the turned way")
    void restoresTurnedDoor() {
        Location origin = new Location(world, 10, FLOOR, 10);
        // (0, 1) turned a quarter clockwise is (-1, 0); west turned clockwise is north.
        BukkitTemplate template = hut(o -> {
            at(9, FLOOR + 1, 10).setType(Material.AIR);
            at(9, FLOOR + 2, 10).setType(Material.AIR);
        });

        template.place(origin, StructureRotation.CLOCKWISE_90, false);

        assertDoor(at(9, FLOOR + 1, 10), Bisected.Half.BOTTOM, BlockFace.NORTH);
        assertDoor(at(9, FLOOR + 2, 10), Bisected.Half.TOP, BlockFace.NORTH);
        assertEquals(2, written.size());
    }

    @Test
    @DisplayName("A door item already lying in the doorway before placement is kept")
    void keepsEarlierItem() {
        Item earlier = world.dropItem(new Location(world, 10.5, FLOOR + 1.3, 11.5),
                new ItemStack(Material.OAK_DOOR));
        BukkitTemplate template = hut(o -> at(10, FLOOR + 1, 11).setType(Material.AIR));

        template.place(new Location(world, 10, FLOOR, 10), StructureRotation.NONE, false);

        assertDoor(at(10, FLOOR + 1, 11), Bisected.Half.BOTTOM, BlockFace.WEST);
        assertTrue(earlier.isValid());
    }

    @Test
    @DisplayName("Only door states count as door halves")
    void findsDoorHalves() {
        BlockData lower = door(Bisected.Half.BOTTOM, BlockFace.EAST);
        List<DoorRepair.Half> halves = DoorRepair.halvesOf(List.of(
                state(1, 0, 1, server.createBlockData(Material.COBBLESTONE)),
                state(2, 1, 3, lower)));

        assertEquals(1, halves.size());
        assertEquals(2, halves.get(0).x());
        assertEquals(1, halves.get(0).y());
        assertEquals(3, halves.get(0).z());
        assertSame(lower, halves.get(0).data());
    }

    // Stand-ins for what MockBukkit does not provide.

    /** A palette state at a position relative to the template's origin. */
    private static BlockState state(int x, int y, int z, BlockData data) {
        return (BlockState) Proxy.newProxyInstance(BlockState.class.getClassLoader(),
                new Class<?>[] {BlockState.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getType" -> data.getMaterial();
                    case "getBlockData" -> data;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * A structure that places its states the way vanilla turns them, without block updates,
     * then runs {@code damage} with the origin.
     */
    private Structure structure(List<BlockState> states, Consumer<Location> damage) {
        Palette palette = (Palette) Proxy.newProxyInstance(Palette.class.getClassLoader(),
                new Class<?>[] {Palette.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBlocks" -> states;
                    case "getBlockCount" -> states.size();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Structure) Proxy.newProxyInstance(Structure.class.getClassLoader(),
                new Class<?>[] {Structure.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPalettes":
                            return List.of(palette);
                        case "place":
                            if (args.length == 7 && args[0] instanceof Location origin) {
                                assertEquals(Mirror.NONE, args[3]);
                                StructureRotation rotation = (StructureRotation) args[2];
                                for (BlockState state : states) {
                                    int[] turned = Placement.rotate(state.getX(), state.getZ(), rotation);
                                    BlockData data = state.getBlockData().clone();
                                    // Cobblestone has nothing to turn, and its mock cannot.
                                    if (data instanceof Door) {
                                        data.rotate(rotation);
                                    }
                                    world.getBlockAt(origin.getBlockX() + turned[0],
                                            origin.getBlockY() + state.getY(),
                                            origin.getBlockZ() + turned[1]).setBlockData(data);
                                }
                                damage.accept(origin);
                                return null;
                            }
                            throw new UnsupportedOperationException("place" + args.length);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    /** An oak door half, with only the properties the repair reads. */
    private static BlockData door(Bisected.Half half, BlockFace facing) {
        BlockFace[] state = {facing};
        Bisected.Half[] halfState = {half};
        return (BlockData) Proxy.newProxyInstance(Door.class.getClassLoader(),
                new Class<?>[] {Door.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> Material.OAK_DOOR;
                    case "getHalf" -> halfState[0];
                    case "getFacing" -> state[0];
                    case "clone" -> door(halfState[0], state[0]);
                    case "rotate" -> {
                        state[0] = turn(state[0], (StructureRotation) args[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "door[" + halfState[0] + "," + state[0] + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BlockFace turn(BlockFace face, StructureRotation rotation) {
        List<BlockFace> clockwise = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
                BlockFace.WEST);
        int steps = switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
        return clockwise.get((clockwise.indexOf(face) + steps) % 4);
    }
}
