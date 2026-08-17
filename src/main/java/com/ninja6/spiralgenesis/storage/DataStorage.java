package com.ninja6.spiralgenesis.storage;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Storage interface for persisting per-player genesis spawn assignments and sequence indices.
 */
public interface DataStorage {

    /**
     * Initializes or loads storage backend.
     */
    void load();

    /**
     * Saves and flushes in-memory data to disk.
     */
    void save();

    /**
     * Checks if a player has an assigned genesis spawn.
     */
    boolean hasSpawn(UUID uuid);

    /**
     * Retrieves the assigned Location for a player.
     */
    Location getSpawn(UUID uuid);

    /**
     * Records a new spawn assignment for a player.
     */
    void setSpawn(UUID uuid, Location location, int index, int gridU, int gridV, String playerName, String clientType);

    /**
     * Removes a player's assigned spawn.
     */
    void removeSpawn(UUID uuid);

    /**
     * Gets the current global spiral sequence index.
     */
    int getCurrentIndex();

    /**
     * Updates the current global spiral sequence index.
     */
    void setCurrentIndex(int index);
}
