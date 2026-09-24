package com.ninja6.spiralgenesis.manager;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vanilla collision for the blocks a MockBukkit world cannot describe.
 *
 * <p>MockBukkit throws from {@code Block.getCollisionShape()} and {@code Block.isPassable()},
 * and its block data has no door, fence gate or snow layers, so a fixture that judged by
 * material alone could only tell air from a full block. This table carries the shapes
 * vanilla gives each block state, placed per position, and the seams in
 * {@code SpawnManager} are answered from it: the collision shape directly,
 * {@code isPassable} as "the shape is empty", which is what CraftBlock computes, and
 * {@code admitsRespawn} from {@link Shape#solid()}.
 *
 * <p>Boxes are in sixteenths of a block, block-local, as {@code Block.box} declares them
 * and as {@code CraftVoxelShape.getBoundingBoxes} hands them back. Facings are fixed
 * rather than modelled; every one of them is the same shape rotated, and a door or an
 * open trapdoor leaves the column centre clear whichever way it faces.
 */
final class BlockShapes {

    /**
     * One block state's collision.
     *
     * @param solid what {@code BlockState.isSolid()} answers, which is
     *              {@code Block.isBuildable()}: forced on or off by some blocks, otherwise
     *              true when the shape's bounds average at least 0.729 of a block per side
     *              or reach a full block high
     */
    record Shape(String name, boolean solid, List<BoundingBox> boxes) implements VoxelShape {

        @Override
        public Collection<BoundingBox> getBoundingBoxes() {
            return boxes.stream().map(BoundingBox::clone).toList();
        }

        @Override
        public boolean overlaps(BoundingBox other) {
            return boxes.stream().anyMatch(box -> box.overlaps(other));
        }

        boolean isEmpty() {
            return boxes.isEmpty();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static BoundingBox box(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        return new BoundingBox(minX / 16, minY / 16, minZ / 16,
                maxX / 16, maxY / 16, maxZ / 16);
    }

    static final Shape EMPTY = new Shape("empty", false, List.of());
    static final Shape FULL = new Shape("full block", true, List.of(box(0, 0, 0, 16, 16, 16)));

    static final Shape BOTTOM_SLAB =
            new Shape("bottom slab", true, List.of(box(0, 0, 0, 16, 8, 16)));
    static final Shape TOP_SLAB =
            new Shape("top slab", true, List.of(box(0, 8, 0, 16, 16, 16)));
    /** A straight bottom stair: a slab with a quarter-block step along one side. */
    static final Shape STAIRS = new Shape("stairs", true,
            List.of(box(0, 0, 0, 16, 8, 16), box(0, 8, 0, 16, 16, 8)));

    static final Shape TRAPDOOR_CLOSED_BOTTOM =
            new Shape("closed trapdoor, bottom", true, List.of(box(0, 0, 0, 16, 3, 16)));
    static final Shape TRAPDOOR_CLOSED_TOP =
            new Shape("closed trapdoor, top", true, List.of(box(0, 13, 0, 16, 16, 16)));
    /** Hinged on one edge and standing up against it, three pixels thick. */
    static final Shape TRAPDOOR_OPEN =
            new Shape("open trapdoor", true, List.of(box(0, 0, 13, 16, 16, 16)));

    /** Not solid: its bounds average under 0.729 and it is one pixel high. */
    static final Shape CARPET = new Shape("carpet", false, List.of(box(0, 0, 0, 16, 1, 16)));

    static final Shape DOOR_CLOSED =
            new Shape("closed door", true, List.of(box(0, 0, 13, 16, 16, 16)));
    static final Shape DOOR_OPEN =
            new Shape("open door", true, List.of(box(0, 0, 0, 3, 16, 16)));

    /** A post-high bar across the middle, so it is stood on like a fence. Forced solid. */
    static final Shape FENCE_GATE_CLOSED =
            new Shape("closed fence gate", true, List.of(box(0, 0, 6, 16, 24, 10)));
    /** No collision at all, but still forced solid, so vanilla will not respawn into it. */
    static final Shape FENCE_GATE_OPEN = new Shape("open fence gate", true, List.of());

    /** Seven pixels high; a floor, and one that hurts. */
    static final Shape CAMPFIRE = new Shape("campfire", true, List.of(box(0, 0, 0, 16, 7, 16)));
    /** Inset a pixel on each side, and a pixel short of the top. */
    static final Shape CACTUS = new Shape("cactus", true, List.of(box(1, 0, 1, 15, 15, 15)));

    /**
     * A snow layer block. Collision is one layer lower than the block's look: a single
     * layer has none, and each layer after it adds two pixels. Forced not solid.
     */
    static Shape snow(int layers) {
        if (layers < 1 || layers > 8) {
            throw new IllegalArgumentException("snow has 1 to 8 layers: " + layers);
        }
        List<BoundingBox> boxes = layers == 1
                ? List.of()
                : List.of(box(0, 0, 0, 16, (layers - 1) * 2, 16));
        return new Shape(layers + " snow layers", false, boxes);
    }

    private record Position(int x, int y, int z) {}

    private record Placed(Material type, Shape shape) {}

    private final Map<Position, Placed> placed = new HashMap<>();

    /**
     * Sets a block and records the state it stands for. The record is dropped as soon as
     * the block's material changes, so a later {@code setType} is never read with a
     * stale shape.
     */
    void place(Block block, Material type, Shape shape) {
        block.setType(type);
        placed.put(new Position(block.getX(), block.getY(), block.getZ()),
                new Placed(type, shape));
    }

    /**
     * The shape recorded for the block, or else the default for its material: the
     * default state for the blocks listed below, and otherwise empty for air, fluids and
     * anything vanilla calls non-solid, and a full block for everything else.
     */
    Shape shapeOf(Block block) {
        Material type = block.getType();
        Placed here = placed.get(new Position(block.getX(), block.getY(), block.getZ()));
        if (here != null && here.type() == type) {
            return here.shape();
        }
        return defaultFor(type);
    }

    private static Shape defaultFor(Material type) {
        // Powder snow only collides for an entity that can walk on it, and there is none in
        // context when a block's shape is asked for.
        if (type.isAir() || type == Material.WATER || type == Material.LAVA
                || type == Material.POWDER_SNOW) {
            return EMPTY;
        }
        if (type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE) {
            return CAMPFIRE;
        }
        if (type == Material.CACTUS) {
            return CACTUS;
        }
        String name = type.name();
        if (name.endsWith("_SLAB")) {
            return BOTTOM_SLAB;
        }
        if (name.endsWith("_STAIRS")) {
            return STAIRS;
        }
        if (name.endsWith("_TRAPDOOR")) {
            return TRAPDOOR_CLOSED_BOTTOM;
        }
        if (name.endsWith("_DOOR")) {
            return DOOR_CLOSED;
        }
        if (name.endsWith("_FENCE_GATE")) {
            return FENCE_GATE_CLOSED;
        }
        if (name.endsWith("_CARPET")) {
            return CARPET;
        }
        if (type == Material.SNOW) {
            return snow(1);
        }
        return type.isSolid() ? FULL : EMPTY;
    }

    boolean isPassable(Block block) {
        return shapeOf(block).isEmpty();
    }

    /** {@code !isBuildable() && !isLiquid()}, as {@code SpawnManager.admitsRespawn} reads. */
    boolean admitsRespawn(Block block) {
        Material type = block.getType();
        return !shapeOf(block).solid() && type != Material.WATER && type != Material.LAVA;
    }
}
