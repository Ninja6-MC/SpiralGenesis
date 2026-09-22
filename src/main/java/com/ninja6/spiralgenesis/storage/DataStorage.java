package com.ninja6.spiralgenesis.storage;

import org.bukkit.Location;

import java.time.Instant;
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
     * <p>Replaces any earlier record whole, so a record written here is never owed a
     * placement.
     *
     * @return true if the record was written, false if it was refused because storage is
     *         failed, in which case nothing changed
     */
    default boolean setSpawn(UUID uuid, Location location, int index, int gridU, int gridV,
                             String playerName, String clientType) {
        return setSpawn(uuid, location, index, gridU, gridV, playerName, clientType, false);
    }

    /**
     * Records a spawn assignment, as {@link #setSpawn(UUID, Location, int, int, int, String,
     * String)} does, and whether its player is still owed a placement on it: set for a plot
     * recorded after its player disconnected, so the mark survives a restart.
     *
     * @return true if the record was written, false if it was refused because storage is
     *         failed, in which case nothing changed
     */
    boolean setSpawn(UUID uuid, Location location, int index, int gridU, int gridV,
                     String playerName, String clientType, boolean placementOwed);

    /**
     * Clears the placement mark on a player's record, once they have been placed.
     *
     * <p>Refused like a write while storage is failed, and answered the same way: a caller
     * places the player only on true, so the file never goes on claiming a placement is
     * owed after it has been made.
     *
     * @return true if the record now carries no mark, including when it had none or there is
     *         no record; false if it was refused because storage is failed, in which case
     *         nothing changed
     */
    boolean clearPlacementOwed(UUID uuid);

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
     * When SpiralGenesis first recorded anything on this server, or {@code null} while
     * storage is failed and nothing recorded can be read.
     *
     * <p>A player who played here before this instant has no plot because the plugin did not
     * exist yet, not because allocation missed them, and is left where they are.
     *
     * <p>Set once, by the first load that finds no value, and kept from then on. For a file
     * written before the value existed it is the earliest assignment the file records, the
     * nearest the plugin can get to its own install from what it wrote, since an older
     * version allocated the first player to join on their first action. Every write of a
     * record rewrites its assignment date, so this can be later than the real install,
     * which leans toward leaving players alone.
     *
     * <p>Null is never a reason to allocate: callers treat it as storage that cannot be
     * read, and hold.
     */
    Instant getInstalledAt();

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
