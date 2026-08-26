package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claims the spawn square for players who were allocated before spawn protection existed.
 *
 * <p>Anyone already in {@code data.yml} when the feature is switched on has a spawn and no
 * claim, and nothing in the ordinary run of the plugin will ever revisit them: allocation
 * fires once per player, for a player who has no plot yet. So turning the feature on would
 * quietly protect the next player to join and nobody who was already there. This is the
 * cheap answer, driven by {@code /sgen protect} when an operator asks for it rather than at
 * startup, because a startup pass would run on every boot of every server whether it was
 * wanted or not.
 *
 * <h2>Why it is a job object and not a loop</h2>
 *
 * <p>The work has to happen on the main thread - see the provider's threading contract -
 * and a server with a large {@code data.yml} has thousands of stored spawns. A plain loop
 * over them is a single tick that takes as long as it takes, which on a live server is a
 * freeze that its players feel and its watchdog may act on.
 *
 * <p>The cost is not hypothetical and it is not spread evenly. Under
 * {@code protection.claim-as: PLAYER_CLAIM} the provider reads the owner's claim-block
 * balance, and for an <em>offline</em> owner GriefPrevention answers that by loading that
 * player's data from its own storage, synchronously, on the calling thread. A backfill's
 * owners are offline almost by definition. So several hundred backfilled spawns is several
 * hundred file or database reads, and the default {@code ADMIN_CLAIM} never reaches that
 * path at all - which means the same loop is nearly free on one server and a stall on the
 * next, and it cannot tell which one it is on.
 *
 * <p>Hence {@link #runBatch()}: one tick's worth of work, bounded by both a count and a
 * wall-clock budget, with the caller driving it once per tick until it says it is done.
 * The clock is what covers the case above, because it is the only one of the two that
 * notices a per-entry cost that is a thousand times what it was on the previous server.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Running this twice is safe and needs no record of having run it once. The second pass
 * asks the provider to reserve squares that are already reserved, and a provider answers
 * {@link ClaimOutcome#ALREADY_CLAIMED} to that, which is counted as skipped. That is
 * genuinely the whole mechanism: no flag in {@code data.yml}, nothing to migrate, and no
 * way for a half-finished run to leave the store in a state a later run reads wrongly.
 */
public final class SpawnProtectionBackfill {

    /**
     * The most entries one tick will ever process, whatever the clock says.
     *
     * <p>A ceiling on the cheap case rather than a target. Where every entry is an
     * in-memory overlap check the time budget below would let thousands through in one
     * tick, and there is no reason to: an operator waiting a couple of extra seconds for a
     * one-off command has lost nothing, and a tick that does fifty small things is a tick
     * nobody can measure.
     */
    static final int MAX_PER_TICK = 50;

    /**
     * How long one tick's batch may spend before it stops early, in nanoseconds.
     *
     * <p>Two milliseconds out of a fifty millisecond tick. Deliberately well under the
     * budget rather than near it, because this runs alongside everything else the server is
     * already doing and is never urgent.
     */
    static final long BUDGET_NANOS = 2_000_000L;

    /** One stored spawn to be claimed. */
    private record Pending(UUID owner, StoredSpawn record) { }

    private final SpawnProtector protector;
    private final List<Pending> queue;

    private int cursor;
    private int created;
    private int skipped;
    private int failed;

    /**
     * The reason the first failure failed, kept so the summary can name one rather than
     * none. One is enough: a backfill fails for the same reason for every player, because
     * the causes are all server-wide settings.
     */
    private String firstFailure;

    /**
     * Takes a snapshot of everything to be done.
     *
     * <p>A snapshot rather than a live view, because the job outlives the tick it started
     * in and players join during it. A player allocated while this is running gets their
     * claim from the allocation path, which is the path that is meant to give it to them,
     * so including them here would only mean asking for the same square twice.
     *
     * @param protector the shared protector, which is what decides whether anything happens
     * @param records   every stored assignment, as {@code data.yml} has it right now
     */
    public SpawnProtectionBackfill(SpawnProtector protector, Map<UUID, StoredSpawn> records) {
        this.protector = protector;
        this.queue = new ArrayList<>(records.size());
        for (Map.Entry<UUID, StoredSpawn> entry : records.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                queue.add(new Pending(entry.getKey(), entry.getValue()));
            }
        }
    }

    /** How many stored spawns this job was given. */
    public int total() {
        return queue.size();
    }

    /** Whether every entry has been dealt with. */
    public boolean isFinished() {
        return cursor >= queue.size();
    }

    /** Squares that were reserved by this run. */
    public int created() {
        return created;
    }

    /**
     * Entries that needed nothing done: already claimed, or in a world that is not loaded
     * so there is nothing to claim around.
     */
    public int skipped() {
        return skipped;
    }

    /** Entries the provider refused, or could not resolve. */
    public int failed() {
        return failed;
    }

    /**
     * Does up to one tick's worth of work.
     *
     * <p>At least one entry always runs, however long the previous one took. Stopping on
     * the clock before doing anything would leave a job that never advances, and one entry
     * per tick is slow rather than broken.
     *
     * @return true when the job is finished and the caller should stop driving it
     */
    public boolean runBatch() {
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int done = 0;
        while (!isFinished() && done < MAX_PER_TICK) {
            step();
            done++;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return isFinished();
    }

    /** One entry. */
    private void step() {
        Pending pending = queue.get(cursor++);
        Location spawn = pending.record().toLocation();
        if (spawn == null) {
            // The world named in the record is not loaded. Not a failure: a multi-world
            // server that has retired a world still has its players in data.yml, and there
            // is nothing to claim around a point that does not currently exist.
            skipped++;
            return;
        }

        ClaimResult result = protector.protectQuietly(pending.owner(), spawn);
        switch (result.outcome()) {
            case CREATED -> created++;
            case ALREADY_CLAIMED -> skipped++;
            case REFUSED, BELOW_MINIMUM_SIZE, PROVIDER_UNAVAILABLE -> {
                failed++;
                if (firstFailure == null) {
                    firstFailure = result.hasDetail() ? result.detail() : "no reason given";
                }
            }
        }
    }

    /**
     * The line an operator reads when it is over.
     *
     * <p>Counts first, because that is the answer to the question they asked, and the one
     * failure reason afterwards, because a count of failures with no cause is a count
     * nobody can act on.
     */
    public String summary() {
        String line = "Spawn protection backfill finished: " + created + " created, "
                + skipped + " skipped, " + failed + " failed, out of " + total() + " stored spawns.";
        if (failed > 0 && firstFailure != null) {
            line += " The first failure was: " + firstFailure;
        }
        return line;
    }
}
