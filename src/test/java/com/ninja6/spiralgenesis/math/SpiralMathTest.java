package com.ninja6.spiralgenesis.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpiralMathTest {

    @Test
    @DisplayName("Verify sequence progression matches mathematical specification")
    void testSpiralProgression() {
        // k = 0 (Player 1) -> (0, 0)
        assertArrayEquals(new int[]{0, 0}, SpiralMath.indexToGrid(0));

        // k = 1 (Player 2) -> (1, 0) [East]
        assertArrayEquals(new int[]{1, 0}, SpiralMath.indexToGrid(1));

        // k = 2 (Player 3) -> (1, 1) [South]
        assertArrayEquals(new int[]{1, 1}, SpiralMath.indexToGrid(2));

        // k = 3 (Player 4) -> (0, 1) [West]
        assertArrayEquals(new int[]{0, 1}, SpiralMath.indexToGrid(3));

        // k = 4 (Player 5) -> (-1, 1) [West]
        assertArrayEquals(new int[]{-1, 1}, SpiralMath.indexToGrid(4));

        // k = 5 (Player 6) -> (-1, 0) [North]
        assertArrayEquals(new int[]{-1, 0}, SpiralMath.indexToGrid(5));

        // k = 6 (Player 7) -> (-1, -1) [North]
        assertArrayEquals(new int[]{-1, -1}, SpiralMath.indexToGrid(6));

        // k = 7 (Player 8) -> (0, -1) [East]
        assertArrayEquals(new int[]{0, -1}, SpiralMath.indexToGrid(7));

        // k = 8 (Player 9) -> (1, -1) [East]
        assertArrayEquals(new int[]{1, -1}, SpiralMath.indexToGrid(8));

        // k = 9 (Player 10) -> (2, -1) [East]
        assertArrayEquals(new int[]{2, -1}, SpiralMath.indexToGrid(9));

        // k = 10 (Player 11) -> (2, 0) [South]
        assertArrayEquals(new int[]{2, 0}, SpiralMath.indexToGrid(10));
    }

    @Test
    @DisplayName("Verify negative or zero index gracefully defaults to (0,0)")
    void testZeroAndNegativeIndex() {
        assertArrayEquals(new int[]{0, 0}, SpiralMath.indexToGrid(0));
        assertArrayEquals(new int[]{0, 0}, SpiralMath.indexToGrid(-5));
    }

    @Test
    @DisplayName("Verify grid to world coordinate transformation with origin and cell size")
    void testGridToWorld() {
        int originX = 1000;
        int originZ = -2000;
        int cellSize = 500;

        // Origin cell (0, 0)
        assertArrayEquals(new int[]{1000, -2000}, SpiralMath.gridToWorld(originX, originZ, 0, 0, cellSize));

        // Cell (1, 0) -> (+500, 0)
        assertArrayEquals(new int[]{1500, -2000}, SpiralMath.gridToWorld(originX, originZ, 1, 0, cellSize));

        // Cell (-1, 1) -> (-500, +500)
        assertArrayEquals(new int[]{500, -1500}, SpiralMath.gridToWorld(originX, originZ, -1, 1, cellSize));
    }

    @Test
    void closedFormMatchesTheWalk() {
        // Every index up to the far edge of a 450x450 spiral, then a few near the int
        // limit, where the walk is too slow to run and the square root least exact.
        for (int index = 0; index <= 200_000; index++) {
            assertArrayEquals(SpiralMath.walkToGrid(index), SpiralMath.indexToGrid(index),
                    "index " + index);
        }
        for (int index : new int[]{2_000_000_000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
            int[] grid = SpiralMath.indexToGrid(index);
            // Ring r holds (2r - 1)^2 .. (2r + 1)^2 - 1, so the ring bounds both coordinates.
            long ring = (long) Math.ceil((Math.sqrt(index + 1.0) - 1.0) / 2.0);
            assertEquals(ring, Math.max(Math.abs((long) grid[0]), Math.abs((long) grid[1])),
                    "index " + index);
        }
    }
}
