package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.config.ClaimOwnership;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** All third-party types stay behind the inspection seam. */
final class GriefPreventionClaimInspector implements ClaimInspector {
    private final Function<Location, Claim> lookup;
    private final Logger logger;

    GriefPreventionClaimInspector(Logger logger, Function<Location, Claim> lookup) {
        this.logger = logger;
        this.lookup = lookup;
    }

    static ClaimInspector live(Logger logger) {
        return new GriefPreventionClaimInspector(logger, spawn -> {
            if (!Bukkit.isPrimaryThread() || !Bukkit.getPluginManager()
                    .isPluginEnabled(GriefPreventionProtectionProvider.PLUGIN_NAME)) {
                throw new IllegalStateException("GriefPrevention is unavailable on this thread");
            }
            GriefPrevention gp = GriefPrevention.instance;
            if (gp == null || gp.dataStore == null) {
                throw new IllegalStateException("GriefPrevention data is unavailable");
            }
            // getClaimAt ignores height and returns a subdivision before its parent.
            return gp.dataStore.getClaimAt(spawn, true, null);
        });
    }

    @Override
    public ClaimInspection inspect(Location spawn, UUID player) {
        try {
            if (spawn == null || spawn.getWorld() == null || player == null) {
                return ClaimInspection.unavailable();
            }
            Claim claim = lookup.apply(spawn);
            if (claim == null || !GriefPreventionClaimLookup.trusts(claim, player)) {
                return ClaimInspection.none();
            }
            Location min = claim.getLesserBoundaryCorner();
            Location max = claim.getGreaterBoundaryCorner();
            Claim root = claim;
            while (root.parent != null) {
                root = root.parent;
            }
            return new ClaimInspection(ClaimInspection.Status.FOUND,
                    min.getBlockX(), min.getBlockZ(), max.getBlockX(), max.getBlockZ(),
                    root.isAdminClaim() ? ClaimOwnership.ADMIN_CLAIM : ClaimOwnership.PLAYER_CLAIM,
                    player.equals(root.ownerID), namedPermission(claim, player, ClaimPermission.Build),
                    namedPermission(claim, player, ClaimPermission.Manage));
        } catch (Throwable t) {
            logger.log(Level.FINE, "Could not inspect the spawn claim", t);
            return ClaimInspection.unavailable();
        }
    }

    private static boolean namedPermission(Claim claim, UUID player, ClaimPermission permission) {
        return claim.hasExplicitPermission(player, permission)
                || (claim.parent != null && !claim.getSubclaimRestrictions()
                && namedPermission(claim.parent, player, permission));
    }
}
