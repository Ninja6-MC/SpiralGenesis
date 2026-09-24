package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Releases the spawn claim around every stored plot, for {@code /sgen release-all confirm}.
 *
 * <p>Exists for uninstalling. Under the default {@code protection.claim-as: ADMIN_CLAIM} the
 * spawn claims are administrative, so a player cannot abandon their own, and without this
 * an operator removing the plugin would have to run one {@code /sgen reassign <player>
 * release} per player - which also moves each of them to a new plot on the way.
 *
 * <h2>What it covers, and what it cannot</h2>
 *
 * <p>{@code data.yml} records each player's current spawn point and nothing about claims:
 * no claim id, no record that a claim was made, and not the size or ownership setting in
 * force when it was. So this asks the provider, once per stored spawn, to release the square
 * it would create around that point today - through {@link SpawnProtector#releaseQuietly},
 * the same call {@code reassign ... release} makes - and the provider deletes a claim only
 * when it recognises it as exactly that square. Three kinds of claim are therefore out of
 * reach, and are left standing rather than guessed at:
 *
 * <ul>
 *   <li>claims around plots a player has since left through {@code reassign} or
 *       {@code setspawn}, which are no longer recorded anywhere;</li>
 *   <li>claims made before {@code protection.size} or {@code protection.claim-as} was
 *       changed, which no longer match and answer {@link ReleaseOutcome#NOT_OURS};</li>
 *   <li>everything under {@code PLAYER_CLAIM}, which the command refuses outright, because
 *       a claim a player made themselves over the same square is indistinguishable from the
 *       one this plugin made for them.</li>
 * </ul>
 *
 * <p>Nothing in {@code data.yml} is changed. The spawn records stay, so a server that keeps
 * the plugin after running this still knows where everyone lives, and {@code /sgen protect}
 * can put the claims back.
 *
 * <h2>Batching</h2>
 *
 * <p>Driven one batch per tick, bounded by the same count and clock as
 * {@link SpawnProtectionBackfill}, and for the same reason: every release has to happen on
 * the main thread and a large {@code data.yml} is thousands of them.
 */
public final class SpawnClaimRelease {

    /** One stored spawn whose claim is to be released. */
    private record Pending(UUID owner, StoredSpawn record) { }

    private final SpawnProtector protector;
    private final List<Pending> queue;

    /**
     * Where the individual claims that were left standing are reported, one line each.
     *
     * <p>Only those: a released claim needs no follow-up, but a claim left standing is one
     * the operator has to remove with the protection plugin's own tools, and a count
     * without the places is not something they can act on.
     */
    private final Consumer<String> leftStanding;

    private int cursor;
    private int released;
    private int notOurs;
    private int notFound;
    private int unavailable;
    private int unloaded;
    private int failed;
    private String firstFailure;

    /**
     * Asked before every entry: {@code null} to carry on, or why the run has to stop.
     *
     * <p>The command checks that protection is active and not {@code PLAYER_CLAIM} before it
     * starts, but the job outlives that check by as many ticks as {@code data.yml} is long,
     * and {@code /sgen reload} rebuilds the provider from the new configuration in between.
     * The provider is resolved per call, so without this a reload to {@code PLAYER_CLAIM}
     * mid-run would have the remaining releases match on owner and square alone and delete
     * claims players made themselves - the exact case the refusal exists for.
     */
    private final Supplier<String> haltReason;

    /** Entries never looked at because the run stopped, and why it did. */
    private int skipped;
    private String haltedBecause;

    /**
     * Takes a snapshot of every stored spawn.
     *
     * @param protector    the shared protector, which owns the provider call
     * @param records      every stored assignment, as {@code data.yml} has it right now
     * @param leftStanding receives one line per claim that was found and not released
     * @param haltReason   asked before every entry; {@code null} to carry on, otherwise why
     *                     the rest of the run is skipped
     */
    public SpawnClaimRelease(SpawnProtector protector, Map<UUID, StoredSpawn> records,
                             Consumer<String> leftStanding, Supplier<String> haltReason) {
        this.protector = protector;
        this.leftStanding = leftStanding;
        this.haltReason = haltReason;
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

    /** Claims deleted by this run. */
    public int released() {
        return released;
    }

    /** Claims found at a stored spawn that were not the square this plugin creates. */
    public int notOurs() {
        return notOurs;
    }

    /** Stored spawns with no claim at all over them. */
    public int notFound() {
        return notFound;
    }

    /** Entries reached after the protection plugin stopped being available. */
    public int unavailable() {
        return unavailable;
    }

    /** Entries whose world is not loaded, so there was nothing to look at. */
    public int unloaded() {
        return unloaded;
    }

    /** Entries the provider refused, threw on, or cannot release at all. */
    public int failed() {
        return failed;
    }

    /** Entries skipped because the run stopped part way. */
    public int skipped() {
        return skipped;
    }

    /** Why the run stopped part way, or {@code null} if it did not. */
    public String haltedBecause() {
        return haltedBecause;
    }

    /**
     * Does up to one tick's worth of work, with the bounds {@link SpawnProtectionBackfill}
     * uses.
     *
     * @return true when the job is finished and the caller should stop driving it
     */
    public boolean runBatch() {
        long deadline = System.nanoTime() + SpawnProtectionBackfill.BUDGET_NANOS;
        int done = 0;
        while (!isFinished() && done < SpawnProtectionBackfill.MAX_PER_TICK) {
            String reason = haltReason.get();
            if (reason != null) {
                haltedBecause = reason;
                skipped += queue.size() - cursor;
                cursor = queue.size();
                break;
            }
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
            unloaded++;
            return;
        }

        ReleaseResult result = protector.releaseQuietly(pending.owner(), spawn);
        String who = pending.record().playerName() == null || pending.record().playerName().isEmpty()
                ? String.valueOf(pending.owner()) : pending.record().playerName();
        switch (result.outcome()) {
            case RELEASED -> released++;
            case NOT_FOUND -> notFound++;
            case NOT_OURS -> {
                notOurs++;
                leftStanding.accept("Left the claim at " + SpawnProtector.describe(spawn)
                        + " (" + who + ") standing: " + result.detail());
            }
            case PROVIDER_UNAVAILABLE -> unavailable++;
            case REFUSED, UNSUPPORTED -> {
                failed++;
                String reason = result.hasDetail() ? result.detail() : "no reason given.";
                if (firstFailure == null) {
                    firstFailure = reason;
                }
                leftStanding.accept("Could not release the claim at "
                        + SpawnProtector.describe(spawn) + " (" + who + "): " + reason);
            }
        }
    }

    /** The line an operator reads when it is over. */
    public String summary() {
        String line = (haltedBecause == null ? "Spawn claim release finished: "
                : "Spawn claim release stopped because " + haltedBecause + ": ")
                + released + " released, " + notOurs
                + " not ours, " + notFound + " with no claim, " + unavailable
                + " with no GriefPrevention, " + unloaded + " in unloaded worlds, " + failed
                + " failed, " + skipped + " skipped, out of " + total() + " stored spawns.";
        if (failed > 0 && firstFailure != null) {
            line += " The first failure was: " + firstFailure;
        }
        if (notOurs > 0 || failed > 0) {
            line += " The claims left standing are listed in the console; remove them with"
                    + " GriefPrevention's own commands if they are no longer wanted.";
        }
        return line;
    }
}
