package com.ninja6.spiralgenesis.protection;

import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationType;
import com.ninja6.spiralgenesis.config.ClaimOwnership;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** All third-party types stay behind the inspection seam. */
final class GriefPreventionClaimInspector implements ClaimInspector {
    private final Function<Location, Claim> lookup;
    private final Logger logger;
    private final Supplier<Material> tool;
    private final BiConsumer<Player, Claim> edges;

    GriefPreventionClaimInspector(Logger logger, Function<Location, Claim> lookup) {
        this(logger, lookup, GriefPreventionClaimInspector::configuredTool,
                GriefPreventionClaimInspector::drawEdges);
    }

    GriefPreventionClaimInspector(Logger logger, Function<Location, Claim> lookup,
                                  Supplier<Material> tool, BiConsumer<Player, Claim> edges) {
        this.logger = logger;
        this.lookup = lookup;
        this.tool = tool;
        this.edges = edges;
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

    @Override
    public void showEdges(Location spawn, Player player) {
        try {
            if (spawn == null || spawn.getWorld() == null || player == null) {
                return;
            }
            Claim claim = lookup.apply(spawn);
            if (claim != null && GriefPreventionClaimLookup.trusts(claim, player.getUniqueId())) {
                edges.accept(player, claim);
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "Could not show the spawn claim's edges", t);
        }
    }

    @Override
    public String investigationTool() {
        try {
            Material material = tool.get();
            return material == null ? null
                    : material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        } catch (Throwable t) {
            logger.log(Level.FINE, "Could not read GriefPrevention's investigation tool", t);
            return null;
        }
    }

    // Method references to plain methods: the GriefPrevention types they name resolve when
    // a method first runs, inside the catches above, and never while the inspector binds.
    private static Material configuredTool() {
        GriefPrevention gp = GriefPrevention.instance;
        return gp == null ? null : gp.config_claims_investigationTool;
    }

    private static void drawEdges(Player player, Claim claim) {
        BoundaryVisualization.visualizeClaim(player, claim, VisualizationType.CLAIM);
    }

    private static boolean namedPermission(Claim claim, UUID player, ClaimPermission permission) {
        return claim.hasExplicitPermission(player, permission)
                || (claim.parent != null && !claim.getSubclaimRestrictions()
                && namedPermission(claim.parent, player, permission));
    }
}
