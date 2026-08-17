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
        String clientType
) {

    /**
     * Resolves this record against the currently loaded worlds.
     *
     * @return the location, or {@code null} if the world is not loaded right now
     */
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z);
    }

    public static StoredSpawn of(Location location, int index, int gridU, int gridV,
                                 String playerName, String clientType) {
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "world";
        return new StoredSpawn(worldName, location.getX(), location.getY(), location.getZ(),
                index, gridU, gridV, playerName, clientType);
    }
}
