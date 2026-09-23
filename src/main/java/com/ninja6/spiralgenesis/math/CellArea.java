package com.ninja6.spiralgenesis.math;

/**
 * A rectangle of block columns, half-open on both axes: {@code minX <= x < maxX} and
 * {@code minZ <= z < maxZ}.
 *
 * <p>Half-open so that two cells side by side, where one ends at the column the next
 * begins, touch without overlapping.
 */
public record CellArea(int minX, int minZ, int maxX, int maxZ) {

    /** The single column a point stands in. */
    public static CellArea column(int x, int z) {
        return new CellArea(x, z, x + 1, z + 1);
    }

    /** Whether the column {@code (x, z)} is inside this area. */
    public boolean contains(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    /** Whether this area and {@code other} share at least one column. */
    public boolean overlaps(CellArea other) {
        return minX < other.maxX && other.minX < maxX
                && minZ < other.maxZ && other.minZ < maxZ;
    }
}
