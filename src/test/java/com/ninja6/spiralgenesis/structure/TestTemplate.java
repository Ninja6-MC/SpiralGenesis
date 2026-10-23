package com.ninja6.spiralgenesis.structure;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;

import java.util.ArrayList;
import java.util.List;

/** A template that places its blocks the way vanilla turns and places a structure file. */
final class TestTemplate implements Template {

    private final String name;
    private final List<Block> blocks;
    private final List<Door> doors;
    int placements;

    TestTemplate(String name, List<Block> blocks) {
        this(name, blocks, List.of());
    }

    TestTemplate(String name, List<Block> blocks, List<Door> doors) {
        this.name = name;
        this.blocks = List.copyOf(blocks);
        this.doors = List.copyOf(doors);
    }

    /**
     * The hut with doors like the shipped one: in the middle of its west and east walls,
     * facing out, both halves stored in place of the wall.
     */
    static TestTemplate hutWithDoors(String name, int side) {
        int middle = side / 2;
        List<Block> blocks = new ArrayList<>();
        for (Block block : hut(name, side).blocks()) {
            boolean doorway = (block.x() == 0 || block.x() == side - 1) && block.z() == middle
                    && (block.y() == 1 || block.y() == 2);
            blocks.add(doorway
                    ? new Block(block.x(), block.y(), block.z(), Material.OAK_DOOR, false)
                    : block);
        }
        return new TestTemplate(name, blocks, List.of(
                new Door(0, 1, middle, BlockFace.WEST),
                new Door(side - 1, 1, middle, BlockFace.EAST)));
    }

    /**
     * A hut like the shipped one, {@code side} square: a full cobblestone floor, walls two
     * high with stored air inside, and a roof. Spawn spot at the centre, one above the floor.
     */
    static TestTemplate hut(String name, int side) {
        List<Block> blocks = new ArrayList<>();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                blocks.add(new Block(x, 0, z, Material.COBBLESTONE, false));
                boolean wall = x == 0 || z == 0 || x == side - 1 || z == side - 1;
                for (int y = 1; y <= 2; y++) {
                    blocks.add(new Block(x, y, z, wall ? Material.COBBLESTONE : Material.AIR, !wall));
                }
                blocks.add(new Block(x, 3, z, Material.COBBLESTONE, false));
            }
        }
        return new TestTemplate(name, blocks);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Block> blocks() {
        return blocks;
    }

    @Override
    public List<Door> doors() {
        return doors;
    }

    @Override
    public void place(Location origin, StructureRotation rotation, boolean includeEntities) {
        placements++;
        for (Block block : blocks) {
            int[] turned = Placement.rotate(block.x(), block.z(), rotation);
            origin.getWorld().getBlockAt(origin.getBlockX() + turned[0], origin.getBlockY() + block.y(),
                    origin.getBlockZ() + turned[1]).setType(block.type());
        }
    }
}
