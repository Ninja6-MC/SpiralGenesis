package com.ninja6.spiralgenesis.manager;

/**
 * Why a candidate spawn point was rejected.
 *
 * <p>Reported per allocation so a simulation run can say which rule is doing the
 * rejecting. A run dominated by {@link #ROUGH}, for example, points at
 * {@code safety.max-roughness} being too strict for the terrain rather than at the
 * terrain being genuinely unusable.
 */
public enum RejectionReason {

    /** Surface sits below {@code safety.min-surface-y}. */
    LOW_SURFACE,

    /** Surface biome is an ocean. */
    OCEAN,

    /** The block underfoot is a hazard. */
    HAZARD,

    /** No room for a player to stand. */
    NO_HEADROOM,

    /** Sits too far below the surrounding terrain: a ravine, sinkhole or crater. */
    PIT,

    /** Surroundings vary too sharply: a cliff edge, spike or jagged ground. */
    ROUGH,

    /** Lava or powder snow within the sampled surroundings. */
    NEARBY_HAZARD
}
