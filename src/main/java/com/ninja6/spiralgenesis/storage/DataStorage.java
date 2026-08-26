package com.ninja6.spiralgenesis.storage;

import org.bukkit.Location;

import java.util.Map;
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
     * Flushes in-memory data to disk immediately on the calling thread.
     */
    void save();

    /**
     * Releases background resources (flush task). Safe to call more than once.
     */
    void shutdown();

    /**
     * Checks if a player has an assigned genesis spawn.
     *
     * <p>This is independent of whether the target world is currently loaded, so a player
     * whose world is temporarily unavailable is never mistaken for an unassigned player.
     */
    boolean hasSpawn(UUID uuid);

    /**
     * Retrieves the assigned Location for a player.
     *
     * @return the location, or {@code null} if unassigned or the world is not loaded
     */
    Location getSpawn(UUID uuid);

    /**
     * Retrieves the raw assignment record for a player, world-resolution aside.
     *
     * @return the record, or {@code null} if unassigned
     */
    StoredSpawn getRecord(UUID uuid);

    /**
     * An immutable snapshot of every recorded assignment, keyed by player.
     *
     * <p>A copy rather than a view, and that is the point of it. Its only caller is the
     * {@code /sgen protect} backfill, which spreads its work across many ticks while
     * players are joining and being allocated; iterating the live store would mean
     * iterating something being written to, and would sweep in the very players whose
     * claims the allocation path is creating at that moment.
     */
    Map<UUID, StoredSpawn> getAllRecords();

    /**
     * Records a new spawn assignment for a player.
     */
    void setSpawn(UUID uuid, Location location, int index, int gridU, int gridV, String playerName, String clientType);

    /**
     * Removes a player's assigned spawn.
     */
    void removeSpawn(UUID uuid);

    /**
     * Resolves a previously seen player name to their UUID without blocking on a
     * remote profile lookup.
     *
     * @return the UUID, or {@code null} if that name has no recorded assignment
     */
    UUID findByName(String playerName);

    /**
     * Reads the next index that would be handed out, without consuming it.
     */
    int getCurrentIndex();

    /**
     * Atomically claims the next global spiral sequence index.
     *
     * <p>Every caller receives a distinct value, so concurrent allocations can never be
     * mapped onto the same grid cell. Indices consumed by a rejected (for example ocean)
     * candidate are simply never reused.
     *
     * @return the claimed index
     */
    int reserveNextIndex();
}
