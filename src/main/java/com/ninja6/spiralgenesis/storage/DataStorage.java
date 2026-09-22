package com.ninja6.spiralgenesis.storage;

import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;

/**
 * Storage interface for persisting per-player genesis spawn assignments and sequence indices.
 */
public interface DataStorage {

    /** What {@link #load()} found. */
    enum LoadOutcome {
        /** Records were read. */
        LOADED,
        /** Nothing is recorded yet: no file, or a file that parses to nothing. */
        NO_FILE,
        /**
         * The file exists and could not be read. Storage is failed until a later load
         * succeeds; see {@link #getFailure()}.
         */
        UNREADABLE
    }

    /**
     * Initializes or loads storage backend.
     *
     * <p>An unreadable file is never treated as an empty one. It leaves storage failed:
     * no records, nothing written back, and every write refused, until a later call reads
     * the file successfully.
     */
    LoadOutcome load();

    /**
     * Why storage is failed, or {@code null} if the last load succeeded.
     */
    StorageFailure getFailure();

    /** Whether the last load failed, so nothing may be read from or written to storage. */
    default boolean isFailed() {
        return getFailure() != null;
    }

    /**
     * Flushes in-memory data to disk immediately on the calling thread. Writes nothing
     * while storage is failed.
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
     * Records a new spawn assignment for a player, unless storage is failed.
     *
     * <p>The result is the only safe answer to "was it recorded". A caller that checked
     * {@link #isFailed()} first can still be refused here, by a reload that fails to read
     * the file in between, so anything that acts on the record - a respawn point, a
     * teleport, a claim - has to be gated on this return value and not on that check.
     *
     * @return true if the record was written, false if it was refused because storage is
     *         failed, in which case nothing changed
     */
    boolean setSpawn(UUID uuid, Location location, int index, int gridU, int gridV, String playerName, String clientType);

    /**
     * Removes a player's assigned spawn. Ignored while storage is failed.
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
     * <p>That holds across a load too, including one that recovers from a failure with a
     * file older than the reservation: the scan that holds an index may still write it
     * afterwards, so a load never restores the counter below it. The one index a load
     * hands out again is one whose {@link #setSpawn} was refused, since nothing can still
     * write it.
     *
     * @return the claimed index
     * @throws IllegalStateException while storage is failed, since the counter it would
     *                               advance is the one that could not be read
     */
    int reserveNextIndex();
}
