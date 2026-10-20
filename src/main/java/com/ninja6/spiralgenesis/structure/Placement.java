package com.ninja6.spiralgenesis.structure;

import com.ninja6.spiralgenesis.math.CellArea;
import org.bukkit.block.structure.StructureRotation;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a template's blocks land for one spawn point and one rotation. Pure arithmetic, so
 * every rule built on it can be tested without a server.
 *
 * <p>The template is turned about its own origin, as vanilla does with a pivot of zero,
 * and then moved so that the offset block, turned the same way, is the stored spawn: the
 * player's feet. The layer under the feet is the template's floor and replaces the ground.
 *
 * @param originX  world X of the template's origin after rotation
 * @param originY  world Y of the template's origin, the floor layer
 * @param originZ  world Z of the template's origin after rotation
 * @param rotation the quarter turn applied
 * @param blocks   every stored position, in world coordinates
 */
public record Placement(int originX, int originY, int originZ, StructureRotation rotation,
                        List<WorldBlock> blocks) {

    /**
     * One stored position in the world.
     *
     * @param floor whether it is in the template's bottom layer, which needs ground under it
     */
    public record WorldBlock(int x, int y, int z, Template.Block source, boolean floor) { }

    public static Placement of(Template template, StructureSettings settings,
                               int spawnX, int spawnY, int spawnZ, StructureRotation rotation) {
        int[] offset = rotate(settings.offsetX(), settings.offsetZ(), rotation);
        int originX = spawnX - offset[0];
        int originY = spawnY - settings.offsetY();
        int originZ = spawnZ - offset[1];
        List<WorldBlock> placed = new ArrayList<>(template.blocks().size());
        for (Template.Block block : template.blocks()) {
            int[] turned = rotate(block.x(), block.z(), rotation);
            placed.add(new WorldBlock(originX + turned[0], originY + block.y(),
                    originZ + turned[1], block, block.y() == 0 && !block.air()));
        }
        return new Placement(originX, originY, originZ, rotation, List.copyOf(placed));
    }

    /** {@code (x, z)} turned about the origin, as vanilla turns a template with no pivot. */
    static int[] rotate(int x, int z, StructureRotation rotation) {
        return switch (rotation) {
            case NONE -> new int[] {x, z};
            case CLOCKWISE_90 -> new int[] {-z, x};
            case CLOCKWISE_180 -> new int[] {-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[] {z, -x};
        };
    }

    /** The columns the stored blocks cover, or {@code null} for a template with none. */
    public CellArea footprint() {
        if (blocks.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (WorldBlock block : blocks) {
            minX = Math.min(minX, block.x());
            maxX = Math.max(maxX, block.x());
            minZ = Math.min(minZ, block.z());
            maxZ = Math.max(maxZ, block.z());
        }
        return CellArea.inclusive(minX, minZ, maxX, maxZ);
    }

    /** The highest Y a stored block reaches. */
    public int topY() {
        int top = originY;
        for (WorldBlock block : blocks) {
            top = Math.max(top, block.y());
        }
        return top;
    }

    /** The lowest Y a stored block reaches. */
    public int bottomY() {
        int bottom = originY;
        for (WorldBlock block : blocks) {
            bottom = Math.min(bottom, block.y());
        }
        return bottom;
    }

    /**
     * The side of the smallest odd square centred on the spawn column that holds every
     * stored block of {@code template}, whichever way it is turned: a quarter turn swaps
     * and negates the axes, which a square centred on the spawn does not notice.
     *
     * @return the side, or 0 for a template with no stored blocks
     */
    public static int requiredSize(Template template, StructureSettings settings) {
        int radius = -1;
        for (Template.Block block : template.blocks()) {
            radius = Math.max(radius, Math.abs(block.x() - settings.offsetX()));
            radius = Math.max(radius, Math.abs(block.z() - settings.offsetZ()));
        }
        return radius < 0 ? 0 : 2 * radius + 1;
    }

    /**
     * The quarter turn that points the template's west side (negative X) at
     * {@code (towardX, towardZ)} from the spawn, to the nearest of the four directions.
     * Ties between an axis go to X; the spawn on the point itself is not turned.
     */
    public static StructureRotation facing(int spawnX, int spawnZ, int towardX, int towardZ) {
        int dx = towardX - spawnX;
        int dz = towardZ - spawnZ;
        if (dx == 0 && dz == 0) {
            return StructureRotation.NONE;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx < 0 ? StructureRotation.NONE : StructureRotation.CLOCKWISE_180;
        }
        // West (-1, 0) turned clockwise is north (0, -1), anticlockwise is south.
        return dz < 0 ? StructureRotation.CLOCKWISE_90 : StructureRotation.COUNTERCLOCKWISE_90;
    }
}
