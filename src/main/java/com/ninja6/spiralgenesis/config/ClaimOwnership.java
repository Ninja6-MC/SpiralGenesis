package com.ninja6.spiralgenesis.config;

import java.util.Locale;

/**
 * How the spawn claim is registered with the protection plugin.
 *
 * <p>The two options differ in who pays for the ground, and that is the whole decision.
 * The claim covers the same blocks and the player can build on it either way.
 */
public enum ClaimOwnership {

    /**
     * Create the claim as an administrative claim and trust the player on it.
     *
     * <p>The default, because it costs the player nothing. An admin claim consumes no
     * claim blocks, so it works on a server whose starting balance is zero - a common
     * configuration, and one where a player claim would fail for every player on the
     * server until somebody noticed. The trade is that the player does not own the claim:
     * they cannot resize it, delete it, or manage trust on it themselves, and it does not
     * appear as theirs in the protection plugin's own listings.
     */
    ADMIN_CLAIM,

    /**
     * Create the claim as an ordinary player claim owned by the player.
     *
     * <p>Theirs in every sense: they can resize it, delete it, and hand out trust, and it
     * counts against the claim blocks they hold. That last part is the catch. On a server
     * with a zero starting balance the player has nothing to spend and the provider
     * refuses every claim, so this only makes sense where new players are given a balance
     * large enough for the configured size.
     */
    PLAYER_CLAIM;

    /**
     * Parses a configured name, falling back to {@code fallback} for anything unrecognised
     * rather than failing plugin startup over a typo.
     */
    public static ClaimOwnership parse(String name, ClaimOwnership fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
