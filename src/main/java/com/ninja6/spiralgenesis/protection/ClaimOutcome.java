package com.ninja6.spiralgenesis.protection;

/**
 * What a protection provider did when asked to reserve a square.
 *
 * <p>Deliberately not a boolean. "The claim was created", "someone already owns this
 * ground", "the provider looked at the request and said no", "the provider is not on this
 * server" and "the square is smaller than the provider will accept" are five different
 * situations, and a caller that collapses them either warns about all of them or about
 * none. Only some of them are a server owner's problem, and the notes below say which.
 */
public enum ClaimOutcome {

    /** The square was reserved for the player. Nothing to report beyond a debug line. */
    CREATED,

    /**
     * A claim already covers the square, in whole or in part, so nothing was created.
     *
     * <p>Ordinary on a server that has been running a while: a player may already have
     * claimed the ground, or an admin claim may cover the area. Not a fault, and not worth
     * a warning.
     */
    ALREADY_CLAIMED,

    /**
     * The provider was asked and declined for a reason of its own - the owner is out of
     * claim blocks, a world is excluded from claiming, another plugin vetoed the creation.
     *
     * <p>The one outcome that is usually worth a warning: the request was well formed and
     * the provider still said no, so a server owner probably wants to know.
     */
    REFUSED,

    /**
     * No provider is active: protection is switched off, or the configured plugin is not
     * installed on this server.
     *
     * <p>The default state of a default install, so it must stay silent. A server that has
     * never heard of a claim plugin has to behave exactly as it did before this feature
     * existed.
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The requested square is smaller than the minimum the provider enforces.
     *
     * <p>Distinct from {@link #REFUSED} because it is neither transient nor per-player: the
     * configured size will fail for every player on the server, for ever, until somebody
     * edits a file. A caller that reports this can name the number to change; one that
     * reports a generic failure cannot.
     */
    BELOW_MINIMUM_SIZE
}
