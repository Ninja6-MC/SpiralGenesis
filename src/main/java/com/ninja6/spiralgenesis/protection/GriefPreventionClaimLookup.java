package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.math.CellArea;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads GriefPrevention's claims for allocation.
 *
 * <p>Like {@link GriefPreventionProtectionProvider}, one of the only classes that names a
 * GriefPrevention type, and only constructed by {@link ProtectionProviders} once it has
 * found GriefPrevention enabled and the server not Folia.
 *
 * <h2>How claims are found</h2>
 *
 * <p>Through GriefPrevention's own per-chunk index, {@code DataStore.getClaims(chunkX,
 * chunkZ)}: every top-level claim is listed under each chunk it touches, and a subdivision
 * lies inside its parent, so testing the claims listed under the chunks the area touches
 * tests every claim that could overlap it. The work per question is one hash lookup per
 * chunk the area touches - at most 4 for the default 9-block square, 289 for the largest
 * 255-block one - plus a rectangle test per claim listed there. It does not grow with the
 * number of claims on the server, only with how many sit in those few chunks. The index is
 * keyed by chunk position alone, not world, so each claim's world is compared as well.
 *
 * <h2>Threading</h2>
 *
 * <p>Safe on any thread, and that was read off the 16.18.2 jar this compiles against rather
 * than assumed. {@code getClaims(int, int)} is not {@code synchronized}: it returns an
 * unmodifiable view of the {@code ArrayList} GriefPrevention keeps for that chunk, which it
 * changes in place. But the only methods that change those lists - {@code addClaim},
 * {@code deleteClaim} and {@code resizeClaim}, through the private
 * {@code addToChunkClaimMap} and {@code removeFromChunkClaimMap} - are all
 * {@code synchronized} on the data store, as is {@code getClaimAt}, GriefPrevention's own
 * reader of the same map. So the lists are walked here while holding that same monitor,
 * which makes the read exactly as safe as {@code getClaimAt} is, from whatever thread a scan
 * happens to be on. Nothing else is called: no event, no player, no world read beyond the
 * claim's own corner locations.
 *
 * <h2>Failure</h2>
 *
 * <p>Every call is inside a {@code catch (Throwable)}, for the same reason the provider's
 * are: an API that moved throws an {@code Error}. A failed lookup answers "not claimed" and
 * is reported once at warning, never per candidate.
 */
final class GriefPreventionClaimLookup implements ClaimLookup {

    /**
     * Where claims are read from: the claims listed under one chunk, and the monitor every
     * writer of those lists holds. A seam, so a test can hand over real {@link Claim}
     * objects without a running GriefPrevention.
     */
    interface ClaimIndex {

        Collection<Claim> claimsInChunk(int chunkX, int chunkZ);

        Object monitor();
    }

    private final Logger logger;

    /** The index to read now, or {@code null} while GriefPrevention is not enabled. */
    private final Supplier<ClaimIndex> index;

    /** Whether a failed lookup has already been reported. */
    private final AtomicBoolean failureReported = new AtomicBoolean();

    GriefPreventionClaimLookup(Logger logger, Supplier<ClaimIndex> index) {
        this.logger = logger;
        this.index = index;
    }

    /** The lookup over the running GriefPrevention's data store. */
    static GriefPreventionClaimLookup live(Logger logger) {
        return new GriefPreventionClaimLookup(logger, GriefPreventionClaimLookup::liveIndex);
    }

    /**
     * Resolved per call rather than held: GriefPrevention's {@code onDisable} clears
     * neither its instance nor its data store, so only the plugin manager can say whether
     * the data store is still the live one, and a reload of either plugin would otherwise
     * leave this reading a stale one.
     */
    private static ClaimIndex liveIndex() {
        if (!Bukkit.getPluginManager()
                .isPluginEnabled(GriefPreventionProtectionProvider.PLUGIN_NAME)) {
            return null;
        }
        GriefPrevention gp = GriefPrevention.instance;
        if (gp == null || gp.dataStore == null) {
            return null;
        }
        DataStore store = gp.dataStore;
        return new ClaimIndex() {
            @Override
            public Collection<Claim> claimsInChunk(int chunkX, int chunkZ) {
                return store.getClaims(chunkX, chunkZ);
            }

            @Override
            public Object monitor() {
                return store;
            }
        };
    }

    @Override
    public boolean overlapsClaim(World world, CellArea area) {
        try {
            ClaimIndex claims = index.get();
            if (claims == null || world == null) {
                return false;
            }
            UUID worldId = world.getUID();
            // Half-open, so the last column is max - 1.
            int minChunkX = area.minX() >> 4;
            int maxChunkX = (area.maxX() - 1) >> 4;
            int minChunkZ = area.minZ() >> 4;
            int maxChunkZ = (area.maxZ() - 1) >> 4;
            synchronized (claims.monitor()) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        for (Claim claim : claims.claimsInChunk(chunkX, chunkZ)) {
                            if (overlaps(claim, worldId, area)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            if (failureReported.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Could not read GriefPrevention's claims while "
                        + "choosing a spawn, so allocation is not avoiding them. Further "
                        + "failures are not logged.", t);
            }
            return false;
        }
    }

    /**
     * Whether {@code claim} is in the world {@code worldId} names and shares a column with
     * {@code area}. Only the horizontal extent counts: a spawn claim reaches from below the
     * surface to the build limit, so any vertical overlap test would pass anyway.
     */
    static boolean overlaps(Claim claim, UUID worldId, CellArea area) {
        Location lesser = claim.getLesserBoundaryCorner();
        Location greater = claim.getGreaterBoundaryCorner();
        if (lesser == null || greater == null) {
            return false;
        }
        World claimWorld = worldOf(lesser);
        if (claimWorld == null || !worldId.equals(claimWorld.getUID())) {
            return false;
        }
        return CellArea.inclusive(lesser.getBlockX(), lesser.getBlockZ(),
                greater.getBlockX(), greater.getBlockZ()).overlaps(area);
    }

    /**
     * The claim's world, or {@code null} when it has none or it has been unloaded, in which
     * case the claim cannot be in the world being allocated in.
     */
    private static World worldOf(Location corner) {
        try {
            return corner.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
    }
}
