package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.config.ClaimOwnership;

/** A snapshot of the most specific claim at a spawn column, never configured geometry. */
public record ClaimInspection(Status status, int minX, int minZ, int maxX, int maxZ,
                              ClaimOwnership ownership, boolean owner, boolean build,
                              boolean manage) {
    public enum Status { FOUND, NONE, UNAVAILABLE }

    public static ClaimInspection unavailable() {
        return new ClaimInspection(Status.UNAVAILABLE, 0, 0, 0, 0, null, false, false, false);
    }

    public static ClaimInspection none() {
        return new ClaimInspection(Status.NONE, 0, 0, 0, 0, null, false, false, false);
    }

    public long width() { return (long) maxX - minX + 1; }
    public long depth() { return (long) maxZ - minZ + 1; }
}
