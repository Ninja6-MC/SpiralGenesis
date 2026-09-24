package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.math.CellArea;
import org.bukkit.World;

/**
 * Asks whether ground is already claimed, for allocation to steer around it.
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

    /** No claim plugin: nothing is ever claimed. */
    ClaimLookup NONE = (world, area) -> false;

    /**
     * Whether any existing claim in {@code world} shares at least one column with
     * {@code area}. Every claim counts, whoever owns it: a player's, an administrative one,
     * and a spawn claim SpiralGenesis made earlier.
     */
    boolean overlapsClaim(World world, CellArea area);
}
