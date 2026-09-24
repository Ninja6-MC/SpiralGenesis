package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.math.CellArea;
import org.bukkit.World;

import java.util.UUID;

/**
 * Asks whether ground is already claimed, for allocation to steer around it, and whether it
 * is claimed by someone other than a given player, for a repair to steer around.
 *
 * <p>Separate from {@link ProtectionProvider} because it answers a different question on a
 * different condition. The provider creates spawn claims and exists only while
 * {@code protection.enabled} is on; this reads every claim already there, and is in force
 * whenever the claim plugin is installed, because players claim their bases whether or not
 * SpiralGenesis claims anything itself. Like the provider it names no third-party type, so
 * the core can load it on a server that has never installed a claim plugin.
 *
 * <h2>Rules an implementation has to follow</h2>
 *
 * <ul>
 *   <li><b>Never throw.</b> A lookup that fails answers {@code false}: a claim missed costs
 *       at worst the refused spawn claim allocation already handles, while a lookup that
 *       failed closed would reject every candidate and walk the spiral without end.</li>
 *   <li><b>Safe on any thread.</b> Allocation asks from whichever thread a scan is on,
 *       including the one that started it.</li>
 *   <li><b>Cost bounded by the ground asked about</b>, not by the number of claims on the
 *       server. It is asked once per candidate.</li>
 * </ul>
 */
@FunctionalInterface
public interface ClaimLookup {

    /** No claim plugin: nothing is ever claimed, by anyone. */
    ClaimLookup NONE = (world, area) -> false;

    /**
     * Whether any existing claim in {@code world} shares at least one column with
     * {@code area}. Every claim counts, whoever owns it: a player's, an administrative one,
     * and a spawn claim SpiralGenesis made earlier.
     */
    boolean overlapsClaim(World world, CellArea area);

    /**
     * Whether any column of {@code area} in {@code world} is claimed by someone other than
     * {@code player}, for a repair that has to stay off a neighbour's ground but not off the
     * player's own.
     *
     * <p>A column is the player's own when the most specific claim covering it, a
     * subdivision rather than the claim it divides, is owned by them or trusts them by name
     * at any level: access, container, build or manage. Trust granted to {@code public} does
     * not count, or a town open to everyone would be every player's own. Administrative
     * claims follow the same rule, since the spawn claim made as an administrative claim is
     * the player's own by trust alone.
     *
     * <p>The same rules apply as to {@link #overlapsClaim}, including answering
     * {@code false} on failure. A lookup with no notion of ownership answers {@code false},
     * as {@link #NONE} does, which leaves a repair where it was before claims were read at
     * all.
     */
    default boolean overlapsForeignClaim(World world, CellArea area, UUID player) {
        return false;
    }
}
