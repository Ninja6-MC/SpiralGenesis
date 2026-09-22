package com.ninja6.spiralgenesis.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * An immutable persisted spawn assignment.
 *
 * <p>The world is retained by name rather than as a resolved {@link World} handle so that
 * an assignment survives being loaded before its world is available (multi-world setups
 * load worlds after plugins enable). Resolution is deferred to {@link #toLocation()}.
 *
 * @param placementOwed whether the plot was recorded after its player disconnected and they
 *                      have not been placed on it since. Stored as the optional
 *                      {@code placement-owed} key, which a file written before it existed
 *                      does not have, and which reads as false
 */
public record StoredSpawn(
        String worldName,
        double x,
        double y,
        double z,
        int index,
        int gridU,
        int gridV,
        String playerName,
        String clientType,
        boolean placementOwed
) {

    /** A record whose player is not owed a placement, which is every record but one. */
    public StoredSpawn(String worldName, double x, double y, double z, int index, int gridU,
                       int gridV, String playerName, String clientType) {
        this(worldName, x, y, z, index, gridU, gridV, playerName, clientType, false);
    }

    /**
     * Resolves this record against the currently loaded worlds.
     *
     * @return the location, or {@code null} if the world is not loaded right now
     */
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z);
    }

    /** The same record with the placement mark set or cleared. */
    public StoredSpawn withPlacementOwed(boolean owed) {
        return new StoredSpawn(worldName, x, y, z, index, gridU, gridV, playerName, clientType,
                owed);
    }

    public static StoredSpawn of(Location location, int index, int gridU, int gridV,
                                 String playerName, String clientType, boolean placementOwed) {
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "world";
        return new StoredSpawn(worldName, location.getX(), location.getY(), location.getZ(),
                index, gridU, gridV, playerName, clientType, placementOwed);
    }
}
