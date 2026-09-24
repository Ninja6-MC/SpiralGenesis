package com.ninja6.spiralgenesis.math;

/**
 * The cell spiral index {@code index} of {@code centre} maps to.
 *
 * <p>Allocation, the overlap test between plots of different centres, and anything else
 * that asks where a recorded plot's cell is, all go through here, so they agree on it.
 */
public record SpiralCell(SpiralCentre centre, int index) {

    /** Grid coordinates {@code [u, v]} of this cell around its centre. */
    public int[] grid() {
        return SpiralMath.indexToGrid(index);
    }

    /** X of the column the in-cell search starts from. */
    public int centreX() {
        return centre.originX() + grid()[0] * centre.cellSize();
    }

    /** Z of the column the in-cell search starts from. */
    public int centreZ() {
        return centre.originZ() + grid()[1] * centre.cellSize();
    }

    /**
     * The columns this cell covers: {@code cell-size} of them on each axis, starting
     * {@code cell-size / 2} below its centre. Every candidate the in-cell search probes is
     * inside it, so this is the whole of what a plot in this cell can occupy.
     */
    public CellArea area() {
        int size = centre.cellSize();
        int minX = centreX() - size / 2;
        int minZ = centreZ() - size / 2;
        return new CellArea(minX, minZ, minX + size, minZ + size);
    }

    /** This cell's {@link SpiralCentre#label}. */
    public String label() {
        return SpiralCentre.label(centre.id(), index);
    }
}
