package com.ninja6.spiralgenesis.config;

/**
 * How a spawn point is chosen from the candidate points inside an allocated cell.
 *
 * <p>Every strategy applies the same safety filter; they differ only in which of the
 * surviving candidates wins.
 */
public enum PlacementStrategy {

    /**
     * Take the first viable candidate. Since candidate 0 is the cell centre, a viable
     * centre is used directly and no further chunks are generated — the cheapest option,
     * and the only one that short-circuits.
     */
    FIRST_SAFE,

    /**
     * Probe the full candidate budget and take the one with the least height variation.
     * Costs the whole budget in chunk generation on every allocation.
     */
    FLATTEST,

    /**
     * Probe the full candidate budget and take the highest candidate at or below
     * {@code placement.height-ceiling}. The ceiling keeps this off jagged peaks, which are
     * exposed, resourceless and a long fall from anything useful.
     */
    HIGHEST;

    /**
     * Parses a configured name, falling back to {@code fallback} for anything unrecognised
     * rather than failing plugin startup over a typo.
     */
    public static PlacementStrategy parse(String name, PlacementStrategy fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Whether this strategy must evaluate every candidate before it can rank them. */
    public boolean needsFullSweep() {
        return this != FIRST_SAFE;
    }
}
