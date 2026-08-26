package com.ninja6.spiralgenesis.protection;

/**
 * What a protection provider did when asked to give back a spawn square it had reserved.
 *
 * <p>Separate from {@link ClaimOutcome} rather than shared with it, because none of the
 * five claim outcomes mean anything here: there is no "already claimed" for a release, and
 * "created" is not a thing a release can be. The two operations are also not symmetrical in
 * risk. Reserving ground that turns out not to be wanted costs a server owner one stale
 * claim; deleting ground that turns out to be wanted costs a player everything inside it.
 * So this enum carries an outcome the claim side has no equivalent of - {@link #NOT_OURS} -
 * and it exists to be the answer far more often than a caller might expect.
 */
public enum ReleaseOutcome {

    /** The claim was found, recognised as this plugin's spawn claim, and deleted. */
    RELEASED,

    /**
     * Nothing is claimed at that point any more.
     *
     * <p>Ordinary rather than a fault: an admin may have removed the claim by hand, or the
     * claim may never have been created because protection was switched on after the player
     * was allocated. Nothing was deleted and nothing needs reporting as a problem.
     */
    NOT_FOUND,

    /**
     * A claim is there, but it is not the one this plugin would have created, so it was
     * left completely alone.
     *
     * <p>The safe answer, and the reason a release can never be a blind delete. A player who
     * resized their spawn claim outwards to take in their house has a claim whose corners no
     * longer match the square this plugin asked for, and one that belongs to somebody else
     * entirely is not this plugin's to touch under any circumstances. Reported to whoever
     * asked for the release so they can go and look, rather than acted on.
     */
    NOT_OURS,

    /**
     * The provider was asked and declined, or threw while being asked. Worth reporting: the
     * request was well formed and the ground is still claimed.
     */
    REFUSED,

    /**
     * No provider is active: protection is switched off, or the configured plugin is not
     * installed. Silent, exactly as on the claim side.
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The active provider can reserve ground but cannot give it back.
     *
     * <p>Not reachable with the providers shipped today, and deliberately not merged into
     * {@link #REFUSED}: a provider that has no release operation at all is a fact about the
     * server's protection plugin that an operator can act on once, whereas a refusal is
     * about this particular claim. {@link ProtectionProvider#release} defaults to this, so an
     * implementation that never thinks about releasing cannot accidentally answer something
     * that reads like a successful one.
     */
    UNSUPPORTED
}
