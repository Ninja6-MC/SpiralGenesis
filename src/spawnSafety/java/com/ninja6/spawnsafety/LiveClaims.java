package com.ninja6.spawnsafety;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.CreateClaimResult;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/** Uses the installed GriefPrevention data store, never a mocked claim lookup. */
final class LiveClaims {
    private final GriefPrevention gp = GriefPrevention.instance;
    private Claim allowed;

    LiveClaims() {
        if (gp == null || gp.dataStore == null) {
            throw new IllegalStateException("GriefPrevention has no live data store");
        }
    }

    void create(World world, int x, int z, UUID owner, UUID trusted) {
        CreateClaimResult result = gp.dataStore.createClaim(world, x - 5, x + 5,
                world.getMinHeight(), world.getMaxHeight() - 1, z - 5, z + 5,
                owner, null, null, null);
        if (result == null || !result.succeeded || result.claim == null) {
            throw new IllegalStateException("Claim setup refused at " + x + "," + z);
        }
        if (trusted != null) {
            result.claim.setPermission(trusted.toString(), ClaimPermission.Build);
            gp.dataStore.saveClaim(result.claim);
        }
        if (trusted != null || owner.equals(SpawnSafetyPlugin.BOT_ID)) {
            allowed = result.claim;
        }
        Claim actual = gp.dataStore.getClaimAt(new Location(world, x, 100, z), true, null);
        if (actual != result.claim) {
            throw new IllegalStateException("Created claim is absent from live lookup");
        }
    }

    void checkSquare(Location at, boolean permitted) {
        boolean overlap = false;
        for (int x = at.getBlockX() - 4; x <= at.getBlockX() + 4; x++) {
            for (int z = at.getBlockZ() - 4; z <= at.getBlockZ() + 4; z++) {
                Claim claim = gp.dataStore.getClaimAt(new Location(at.getWorld(), x, at.getY(), z),
                        true, null);
                if (claim != null) {
                    overlap = true;
                    if (!permitted || claim != allowed
                            || !claim.hasExplicitPermission(SpawnSafetyPlugin.BOT_ID, ClaimPermission.Build)) {
                        throw new IllegalStateException("Spawn square overlaps unauthorized claim at " + x + "," + z);
                    }
                }
            }
        }
        if (permitted && !overlap) {
            throw new IllegalStateException("Repair did not use the owner/trusted control claim");
        }
    }
}
