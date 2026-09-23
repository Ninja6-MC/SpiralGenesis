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

    /**
     * The square of side {@code side} centred on the column {@code (x, z)}: with an odd
     * side, {@code (side - 1) / 2} columns out from it in each direction, which is the
     * square a spawn claim covers.
     */
    public static CellArea square(int x, int z, int side) {
        int radius = (side - 1) / 2;
        return new CellArea(x - radius, z - radius, x + radius + 1, z + radius + 1);
    }

    /**
     * The columns from {@code (lesserX, lesserZ)} to {@code (greaterX, greaterZ)}, both
     * corners included, as a protection plugin states a claim's extent.
     */
    public static CellArea inclusive(int lesserX, int lesserZ, int greaterX, int greaterZ) {
        return new CellArea(lesserX, lesserZ, greaterX + 1, greaterZ + 1);
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
