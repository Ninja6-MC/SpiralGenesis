package com.ninja6.spiralgenesis.math;

/**
 * One spiral: the origin it grows from and the size of its cells, under the id its plots
 * are recorded against.
 *
 * <p>Moving {@code origin.x} or {@code origin.z}, or changing {@code cell-size}, starts a
 * different spiral rather than moving this one, because the plots already handed out stay
 * where they were put. A plot is therefore identified by its centre and its index together,
 * written {@code centre,index} (see {@link #label}).
 *
 * @param id       the id plots of this spiral are recorded against
 * @param originX  X of the centre of cell 0
 * @param originZ  Z of the centre of cell 0
 * @param cellSize width of every cell, in blocks
 */
public record SpiralCentre(int id, int originX, int originZ, int cellSize) {

    /** Whether this spiral grows from {@code (x, z)} with cells of {@code size}. */
    public boolean hasGeometry(int x, int z, int size) {
        return originX == x && originZ == z && cellSize == size;
    }

    /** The cell holding spiral index {@code index} of this centre. */
    public SpiralCell cell(int index) {
        return new SpiralCell(this, index);
    }

    /**
     * How a plot is named in commands and logs: {@code #centre,index}, or {@code #-1} for a
     * point set by hand, which is not on any spiral.
     */
    public static String label(int centre, int index) {
        return index < 0 ? "#" + index : "#" + centre + "," + index;
    }
}
