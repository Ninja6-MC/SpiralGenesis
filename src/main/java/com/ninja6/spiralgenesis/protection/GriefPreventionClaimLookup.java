package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.math.CellArea;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads GriefPrevention's claims for allocation and repair.
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
 * <h2>Whose a claim is</h2>
 *
 * <p>For {@link #overlapsForeignClaim}, each column is judged by the most specific claim
 * covering it, as {@code DataStore.getClaimAt} picks one: a subdivision where there is one,
 * otherwise the claim it divides. Trust is read the way GriefPrevention's own permission
 * check reads it for a player known only by id, off the 16.18.2 jar:
 * {@code hasExplicitPermission(UUID, level)} is true for the owner, which for a
 * subdivision is its parent's owner, and otherwise looks the player up by their id's string
 * alone, in the {@code managers} list for {@code Manage} and in the single-valued trust map
 * for every other level. {@code Access} is granted by an {@code Access}, {@code Inventory}
 * or {@code Build} entry, so asking for {@code Access} and then {@code Manage} covers every
 * named level. {@code public} trust is stored in the same map under the key
 * {@code "public"}, which no player's id matches; GriefPrevention consults it in a separate
 * step of {@code getDefaultDenial}, which is not called here. Neither are the
 * {@code [permission]} entries, which only a {@code Player} can match. When the subdivision
 * does not trust the player itself, that same denial step falls back to its parent unless
 * the subdivision restricts inheritance ({@code getSubclaimRestrictions}), and so does this.
 * Subdivisions are read from their parent's {@code children}, which {@code addClaim} and
 * {@code deleteClaim} change under the same monitor as the chunk lists. The trust lists
 * are not: {@code setPermission} holds no lock, so a trust changed while a repair reads it
 * may be seen either way, and anything thrown by the read is caught as below.
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
 * claim's own corner locations. The trust read for a repair is {@code hasExplicitPermission}
 * by id, which reads only the claim's own fields and its parent's owner, and fires no
 * {@code ClaimPermissionCheckEvent}, unlike {@code checkPermission}.
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
        return anyClaim(world, area, (claim, worldId) -> overlaps(claim, worldId, area));
    }

    @Override
    public boolean overlapsForeignClaim(World world, CellArea area, UUID player) {
        if (player == null) {
            return false;
        }
        return anyClaim(world, area, (claim, worldId) -> {
            CellArea extent = extent(claim, worldId);
            CellArea part = extent == null ? null : intersection(extent, area);
            return part != null && foreignIn(claim, worldId, part, player);
        });
    }

    /** One top-level claim listed under a chunk the area touches, asked about the area. */
    @FunctionalInterface
    private interface ClaimTest {

        boolean test(Claim claim, UUID worldId);
    }

    /**
     * Whether {@code test} holds for any top-level claim listed under a chunk
     * {@code area} touches, answering {@code false} on any failure; see the class
     * description.
     */
    private boolean anyClaim(World world, CellArea area, ClaimTest test) {
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
                            if (test.test(claim, worldId)) {
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
                        + "choosing a spawn, so claims are not being avoided. Further "
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
        CellArea extent = extent(claim, worldId);
        return extent != null && extent.overlaps(area);
    }

    /**
     * Whether any column of {@code part}, which lies inside {@code claim}, is foreign to
     * {@code player}: inside a subdivision that is foreign there, or outside every
     * subdivision while {@code claim} itself does not trust the player.
     */
    static boolean foreignIn(Claim claim, UUID worldId, CellArea part, UUID player) {
        List<CellArea> ownSubdivisions = new ArrayList<>();
        if (claim.children != null) {
            for (Claim child : claim.children) {
                CellArea extent = extent(child, worldId);
                CellArea inChild = extent == null ? null : intersection(extent, part);
                if (inChild == null) {
                    continue;
                }
                if (foreignIn(child, worldId, inChild, player)) {
                    return true;
                }
                ownSubdivisions.add(inChild);
            }
        }
        return !trusts(claim, player) && !covers(ownSubdivisions, part);
    }

    /**
     * Whether {@code claim} counts as {@code player}'s own: owned by them, trusted to them
     * by name at any level, or, for a subdivision that does not restrict inheritance,
     * inherited from its parent. See the class description for why this reads no
     * {@code public} trust.
     */
    static boolean trusts(Claim claim, UUID player) {
        if (claim.hasExplicitPermission(player, ClaimPermission.Access)
                || claim.hasExplicitPermission(player, ClaimPermission.Manage)) {
            return true;
        }
        return claim.parent != null && !claim.getSubclaimRestrictions()
                && trusts(claim.parent, player);
    }

    /**
     * Whether {@code parts}, each inside {@code area}, leave no column of it uncovered.
     * Tested on the grid their edges cut the area into, so the cost is set by how many
     * subdivisions reach the area, not by its size.
     */
    static boolean covers(List<CellArea> parts, CellArea area) {
        if (parts.isEmpty()) {
            return false;
        }
        TreeSet<Integer> xs = new TreeSet<>(List.of(area.minX(), area.maxX()));
        TreeSet<Integer> zs = new TreeSet<>(List.of(area.minZ(), area.maxZ()));
        for (CellArea part : parts) {
            xs.add(part.minX());
            xs.add(part.maxX());
            zs.add(part.minZ());
            zs.add(part.maxZ());
        }
        Integer[] xEdges = xs.toArray(Integer[]::new);
        Integer[] zEdges = zs.toArray(Integer[]::new);
        for (int i = 0; i + 1 < xEdges.length; i++) {
            for (int j = 0; j + 1 < zEdges.length; j++) {
                int x = xEdges[i];
                int z = zEdges[j];
                if (parts.stream().noneMatch(part -> part.contains(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The columns {@code claim} covers, or {@code null} when it has no corners or is not in
     * the world {@code worldId} names.
     */
    private static CellArea extent(Claim claim, UUID worldId) {
        Location lesser = claim.getLesserBoundaryCorner();
        Location greater = claim.getGreaterBoundaryCorner();
        if (lesser == null || greater == null) {
            return null;
        }
        World claimWorld = worldOf(lesser);
        if (claimWorld == null || !worldId.equals(claimWorld.getUID())) {
            return null;
        }
        return CellArea.inclusive(lesser.getBlockX(), lesser.getBlockZ(),
                greater.getBlockX(), greater.getBlockZ());
    }

    /** The columns {@code a} and {@code b} share, or {@code null} when they share none. */
    private static CellArea intersection(CellArea a, CellArea b) {
        if (!a.overlaps(b)) {
            return null;
        }
        return new CellArea(Math.max(a.minX(), b.minX()), Math.max(a.minZ(), b.minZ()),
                Math.min(a.maxX(), b.maxX()), Math.min(a.maxZ(), b.maxZ()));
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
