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
        // The legs run 1, 1, 2, 2, 3, 3, ... blocks, turning clockwise each time. After the
        // first 2m legs, m(m + 1) steps, the walk stands on a diagonal corner: (k, k) with
        // k = (m + 1) / 2 when m is odd, (-k, -k) with k = m / 2 when m is even. The next two
        // legs are m + 1 long, east then south from an even corner and west then north from
        // an odd one. Closed form rather than a walk, so a large index costs no more than a
        // small one.
        long k = index;
        long m = (long) ((Math.sqrt(4.0 * k + 1.0) - 1.0) / 2.0);
        while (m * (m + 1) > k) {
            m--;
        }
        while ((m + 1) * (m + 2) <= k) {
            m++;
        }
        long rest = k - m * (m + 1);
        long leg = m + 1;
        boolean even = (m & 1) == 0;
        long corner = even ? -(m / 2) : (m + 1) / 2;
        long sign = even ? 1 : -1;
        long u = corner + sign * Math.min(rest, leg);
        long v = corner + sign * Math.max(0, rest - leg);
        return new int[]{(int) u, (int) v};
    }

    /**
     * {@link #indexToGrid} by walking the spiral one step at a time. Kept as the reference
     * the closed form is tested against.
     */
    static int[] walkToGrid(int index) {
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
