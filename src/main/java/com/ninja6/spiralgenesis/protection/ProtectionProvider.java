package com.ninja6.spiralgenesis.protection;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Reserves ground for a player through whatever land protection plugin the server runs.
 *
 * <p>One call that matters, and it is deliberately the narrowest thing every claim plugin
 * can be asked to do: take this square, register it to this player, say what happened.
 * Nothing common to GriefPrevention, WorldGuard, Towny, Lands and BentoBox survives past
 * that, so nothing else is promised here. Unclaiming, resizing, trust management, claim
 * block accounting and anything asynchronous are all out: if an implementation needs one
 * of them internally that is its own business, not part of this contract.
 *
 * <h2>Rules an implementation has to follow</h2>
 *
 * <ul>
 *   <li><b>Resolve the third-party plugin once, at construction.</b> The existing
 *       {@code hook/} adapters do this and the reason applies here unchanged: naming
 *       another plugin's classes is what risks {@code NoClassDefFoundError}, and doing it
 *       during registration takes the whole enable down with it.</li>
 *   <li><b>Swallow {@link Throwable} at every call site.</b> Not {@code Exception}: a
 *       provider whose API has moved between versions throws {@code NoSuchMethodError},
 *       which is an {@code Error}. A failed claim must cost the player a claim, never a
 *       join.</li>
 *   <li><b>Keep the third-party imports inside the implementation class.</b> The core
 *       loads this interface, {@link ClaimResult} and {@link ClaimOutcome} on every server,
 *       including one that has never installed a claim plugin, so none of the three may
 *       name a type the core cannot resolve.</li>
 *   <li><b>Never return {@code null}.</b> Every path ends in a {@link ClaimResult},
 *       including the caught-throwable path.</li>
 *   <li><b>Take configuration at construction, not per call.</b> Whether the claim is an
 *       administrative one the player is trusted on or a claim they own outright is a
 *       server-wide setting ({@code protection.claim-as}), so it is an implementation's
 *       constructor argument. It is deliberately absent from the signature below: every
 *       option any one plugin needs would otherwise end up in a call every other plugin
 *       has to ignore.</li>
 * </ul>
 */
public interface ProtectionProvider {

    /**
     * A short name for this provider, for the log line that says which one is in use.
     * Constant for the lifetime of the object and safe to call whether or not the
     * third-party plugin is present.
     */
    String name();

    /**
     * Whether this provider can actually reserve anything on this server.
     *
     * <p>Answered from state resolved at construction, so it is cheap and does not touch
     * the third-party plugin. False means every {@link #reserve} call will return
     * {@link ClaimOutcome#PROVIDER_UNAVAILABLE}.
     */
    boolean isAvailable();

    /**
     * Reserves a square of side {@code size} centred on {@code centre} for {@code owner}.
     *
     * <p>The square is horizontal and centred: with an odd {@code size} it extends
     * {@code (size - 1) / 2} blocks out from the centre block in each of the four compass
     * directions. Sizes reaching this method are always odd, because an even square has no
     * centre block and configuration rounds up before anything gets here. The vertical
     * extent is the provider's to decide - claim plugins disagree about whether a claim is
     * a prism or a column, and forcing one answer would fit exactly one of them.
     *
     * <p>Synchronous, and expected to be called on the thread that owns the region
     * containing {@code centre}. Claim plugins are not thread safe and the callers in this
     * plugin are already on that thread.
     *
     * @param centre the block at the middle of the square, whose world is the claim's world
     * @param size   the length of one side in blocks, odd and at least 3
     * @param owner  the player the reserved ground belongs to, who need not be online
     * @return what happened; never {@code null}
     */
    ClaimResult reserve(Location centre, int size, UUID owner);
}
