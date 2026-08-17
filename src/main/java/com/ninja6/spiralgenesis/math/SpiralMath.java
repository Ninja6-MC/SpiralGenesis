package com.ninja6.spiralgenesis.math;

/**
 * Mathematical utilities for deterministic 2D square spiral coordinate mapping.
 */
public final class SpiralMath {

    private SpiralMath() {
        // Utility class
    }

    /**
     * Converts a 1D sequence index k (0, 1, 2, ...) into 2D grid coordinates (u, v).
     * Follows expanding clockwise square spiral:
     * (0,0) -> (1,0) -> (1,1) -> (0,1) -> (-1,1) -> (-1,0) -> (-1,-1) -> (0,-1) ...
     *
     * @param index The step index (k >= 0)
     * @return An array containing [u, v] grid coordinates
     */
    public static int[] indexToGrid(int index) {
        if (index <= 0) {
            return new int[]{0, 0};
        }

        int u = 0;
        int v = 0;
        int du = 1;
        int dv = 0;
        int segmentLength = 1;
        int segmentPassed = 0;

        for (int i = 0; i < index; i++) {
            u += du;
            v += dv;
            segmentPassed++;

            if (segmentPassed == segmentLength) {
                segmentPassed = 0;
                // Rotate 90 degrees clockwise (East -> South -> West -> North)
                int temp = du;
                du = -dv;
                dv = temp;
                if (du != 0) {
                    segmentLength++;
                }
            }
        }
        return new int[]{u, v};
    }

    /**
     * Converts discrete grid coordinates (u, v) to world coordinates (X, Z).
     *
     * @param originX Global origin X coordinate
     * @param originZ Global origin Z coordinate
     * @param gridU   Discrete grid cell U
     * @param gridV   Discrete grid cell V
     * @param cellSize Cell dimensions (N)
     * @return An array containing [worldX, worldZ]
     */
    public static int[] gridToWorld(int originX, int originZ, int gridU, int gridV, int cellSize) {
        int worldX = originX + (gridU * cellSize);
        int worldZ = originZ + (gridV * cellSize);
        return new int[]{worldX, worldZ};
    }
}
