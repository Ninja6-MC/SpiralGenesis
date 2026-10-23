package com.ninja6.spiralgenesis.structure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the approach in front of each door of a placed template lies. Pure arithmetic over a
 * {@link Placement}, so it can be tested without a server.
 *
 * <p>A door is walked through along its facing axis. Each of its two sides on that axis whose
 * block at the door's lower half is not a stored position of the template is outside the
 * building, and is an approach: the block there and the one above it are to be cleared. A
 * door with both sides stored, between two rooms, has none.
 */
final class Doorways {

    /** One block position in the world. */
    record Cell(int x, int y, int z) { }

    /**
     * The approach on one side of one door, in world coordinates.
     *
     * @param feet  the block level with the door's lower half
     * @param head  the block level with its upper half
     * @param below the block under {@code feet}, level with the template's floor under the door
     * @param clear the cells of {@code feet} and {@code head} the template does not store, which
     *              placement leaves as they were
     * @param landing whether {@code below} is not stored either, so the template does not put
     *              anything under the approach
     */
    record Approach(Cell feet, Cell head, Cell below, List<Cell> clear, boolean landing) { }

    private Doorways() { }

    /** Every approach of the template's doors as {@code placement} turns and places them. */
    static List<Approach> of(Template template, Placement placement) {
        Set<Cell> stored = new HashSet<>();
        for (Placement.WorldBlock block : placement.blocks()) {
            stored.add(new Cell(block.x(), block.y(), block.z()));
        }
        Set<Approach> approaches = new LinkedHashSet<>();
        for (Template.Door door : template.doors()) {
            int[] at = Placement.rotate(door.x(), door.z(), placement.rotation());
            int x = placement.originX() + at[0];
            int y = placement.originY() + door.y();
            int z = placement.originZ() + at[1];
            int[] step = Placement.rotate(door.facing().getModX(), door.facing().getModZ(),
                    placement.rotation());
            if (step[0] == 0 && step[1] == 0) {
                continue;
            }
            for (int sign : new int[] {1, -1}) {
                Cell feet = new Cell(x + sign * step[0], y, z + sign * step[1]);
                if (stored.contains(feet)) {
                    continue;
                }
                Cell head = new Cell(feet.x(), y + 1, feet.z());
                Cell below = new Cell(feet.x(), y - 1, feet.z());
                List<Cell> clear = new ArrayList<>(2);
                clear.add(feet);
                if (!stored.contains(head)) {
                    clear.add(head);
                }
                approaches.add(new Approach(feet, head, below, List.copyOf(clear),
                        !stored.contains(below)));
            }
        }
        return List.copyOf(approaches);
    }
}
