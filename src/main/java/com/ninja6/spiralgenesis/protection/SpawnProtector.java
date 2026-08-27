package com.ninja6.spiralgenesis.protection;

import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one place in the plugin that asks a {@link ProtectionProvider} for anything.
 *
 * <p>Five separate paths can move or create a player's spawn point - first allocation,
 * {@code /sgen reassign}, {@code /sgen setspawn}, the revalidation repair, and the
 * {@code /sgen protect} backfill - and each of them would otherwise repeat the same four
 * decisions: read the configured size, skip the work when no provider is active, decide
 * which outcomes deserve a log line, and make sure a provider that misbehaves cannot break
 * the caller. Repeating that five times is how one of them ends up subtly different from
 * the other four.
 *
 * <h2>Two invariants this class exists to keep</h2>
 *
 * <ul>
 *   <li><b>A claim can never fail an allocation.</b> Every method here returns a result and
 *       throws nothing, including when the provider throws, and no caller is expected to
 *       branch on failure for anything except what it prints. The player keeps their plot,
 *       their teleport and their respawn point whatever a protection plugin does.</li>
 *   <li><b>Nothing is deleted unless a caller asked in as many words.</b> The provider's
 *       release call is reachable from exactly one place - {@link #handOffStaleClaim} with
 *       {@code releaseOld} set - and every default path in the plugin passes {@code false}.
 *       That is what makes "the default never deletes anything" a property of one method
 *       rather than a promise repeated at five call sites.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #protect}, {@link #protectQuietly} and {@link #handOffStaleClaim} inherit the
 * provider's contract unchanged: <b>they must be called on the thread that owns the region
 * containing the spawn</b>, which is the main thread on every server where a real provider
 * exists, since no claim plugin this seam supports runs on Folia. This class deliberately
 * does not hop threads on the caller's behalf. Every caller in the plugin already runs
 * inside something that pins the thread - the player's entity scheduler after allocation
 * and after a repair, the command thread for the operator commands, and the backfill's own
 * global-region task - so a hop here would be a second, redundant one that also made the
 * ordering of a claim against the spawn write it protects impossible to read off the code.
 * The provider refuses and logs at SEVERE if this is ever got wrong.
 */
public final class SpawnProtector {

    private final Logger logger;
    private final Supplier<ProtectionProvider> provider;
    private final IntSupplier size;

    /**
     * @param logger   where the lines that are worth a server owner's attention go
     * @param provider the provider in force, read per call rather than captured, because
     *                 {@code /sgen reload} replaces it and a captured one would keep a
     *                 provider built against the previous configuration alive forever
     * @param size     the configured side of the square, read per call for the same reason
     */
    public SpawnProtector(Logger logger, Supplier<ProtectionProvider> provider, IntSupplier size) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.size = Objects.requireNonNull(size, "size");
    }

    /**
     * Whether a provider is actually able to reserve anything on this server.
     *
     * <p>The gate on every piece of operator-facing wording about claims. A server with
     * protection switched off must read exactly as it did before this feature existed, so a
     * command that would otherwise say "the old claim is still standing" says nothing at
     * all when there was never a claim to begin with.
     */
    public boolean isActive() {
        try {
            return current().isAvailable();
        } catch (Throwable t) {
            // Inside the guard for the same reason the reserve call is. A provider that
            // cannot even say whether it is available is not available, and this catch is
            // the difference between a broken protection plugin costing a claim and costing
            // the allocation that was about to happen around it.
            logger.log(Level.WARNING, "The protection provider threw while being asked whether "
                    + "it is available. Treating it as absent.", t);
            return false;
        }
    }

    /** The side of the square that would be claimed, in blocks. Always odd. */
    public int size() {
        return size.getAsInt();
    }

    /**
     * Claims the square around a spawn point and logs whatever is worth logging.
     *
     * <p>Call this <em>after</em> the spawn location is final and written to storage, never
     * before. A claim around a point the player then does not end up at is worse than no
     * claim: it protects ground nobody lives on and, under {@code PLAYER_CLAIM}, it is
     * charged to them for the privilege.
     *
     * @param owner   the player the ground belongs to; may be offline
     * @param spawn   the spawn point, already stored
     * @param context two or three words naming the path that got here, for the log line
     * @return what the provider did; never {@code null}, and never a thrown exception
     */
    public ClaimResult protect(UUID owner, Location spawn, String context) {
        ClaimResult result = protectQuietly(owner, spawn);
        switch (result.outcome()) {
            case CREATED -> logger.info("Claimed the " + size() + "x" + size()
                    + " spawn square for " + owner + " (" + context + ")"
                    + (result.hasDetail() ? ": " + result.detail() : "."));
            case REFUSED -> logger.warning("Could not claim the spawn square for " + owner
                    + " (" + context + "): " + (result.hasDetail() ? result.detail() : "no reason given.")
                    + " They keep the spawn and lose the protection.");
            // Not a fault - a claim is legitimately in the way - but this plugin protected
            // nobody, and usually that means a player has silently ended up with none. This
            // line is the only thing anywhere that could say so: not the command output on
            // the paths that have one, not the player, not the provider. It sat at FINE on
            // the grounds that it would otherwise be "one line per joining player", and
            // that is not what this method's call sites are: first allocation fires once
            // per player, the two commands fire when an operator types them, the
            // revalidation repair fires when a stored point has gone bad, and the backfill
            // does not come through here at all. So the ceiling is the same ceiling the
            // CREATED line above already lives at, which makes de-duplication state that
            // would have to be cleared on reload, and could not tell a genuinely new
            // overlap at a new point from a repeat, a cost with nothing to buy. INFO rather
            // than WARNING because nothing malfunctioned: WARNING is what this class says
            // when the provider refuses or breaks, and spending it on an ordinary outcome
            // is how a server owner learns to skim past the real ones.
            case ALREADY_CLAIMED -> logger.info("No spawn claim for " + owner + " ("
                    + context + "): " + (result.hasDetail() ? result.detail()
                    : "the square is already claimed.")
                    // Not "they lose the protection", which would be wrong in a case this
                    // outcome reaches: a spawn moved a few blocks by setspawn or by a repair
                    // overlaps the square the player is already trusted on, and they are
                    // exactly as protected as they were a moment ago. What is always true is
                    // that this plugin made nothing, and that whatever cover they have there
                    // now is the existing claim's business rather than SpiralGenesis's.
                    + " They keep the spawn, and their cover there is whatever that claim"
                    + " already gives them.");
            // Quiet on purpose, and the one place the two outcomes part company. This is
            // neither per-player nor transient: the configured size is below the provider's
            // minimum for everybody, for ever, until somebody edits a file. The provider
            // reports it once at startup naming both numbers, so a line per player here
            // would be that same permanent fact repeated for as long as the server runs.
            case BELOW_MINIMUM_SIZE -> logger.fine("No spawn claim for " + owner + " ("
                    + context + "): " + (result.hasDetail() ? result.detail()
                    : "the square is below the provider's minimum size."));
            // The default install. Silence is the whole point of it.
            case PROVIDER_UNAVAILABLE -> { }
        }
        return result;
    }

    /**
     * Claims the square and says nothing at all, for the backfill.
     *
     * <p>The backfill reports counts. Several hundred individual lines saying the same
     * thing is not a report, and the one condition worth naming - why the failures failed -
     * it names once from the aggregate instead.
     */
    public ClaimResult protectQuietly(UUID owner, Location spawn) {
        if (owner == null || spawn == null || spawn.getWorld() == null) {
            return ClaimResult.of(ClaimOutcome.REFUSED, "the spawn point could not be resolved.");
        }
        try {
            // Every call into the provider is inside this guard, availability included.
            // Reading it outside would leave one unguarded call on the path allocation
            // takes, which is the whole path this method exists to keep safe.
            ProtectionProvider active = current();
            if (!active.isAvailable()) {
                return ClaimResult.of(ClaimOutcome.PROVIDER_UNAVAILABLE);
            }
            ClaimResult result = active.reserve(spawn, size(), owner);
            // A provider is required never to return null. Believing that here would turn a
            // third-party bug into a NullPointerException inside allocation, which is the
            // one thing this whole class exists to prevent.
            return result == null
                    ? ClaimResult.of(ClaimOutcome.REFUSED, "the provider returned no result.")
                    : result;
        } catch (Throwable t) {
            // The provider contract says it swallows Throwable itself. This is the belt to
            // that braces: an implementation that breaks the contract must cost a player a
            // claim, never a plot.
            logger.log(Level.WARNING, "The protection provider threw while claiming the spawn "
                    + "square for " + owner + ". They keep the spawn and lose the protection.", t);
            return ClaimResult.of(ClaimOutcome.REFUSED, "the provider threw " + t.getClass().getSimpleName());
        }
    }

    /**
     * Decides what happens to the claim around a spawn point a player has just moved off,
     * and returns the one line whoever asked should be told.
     *
     * <p>The default is non-destructive and that is the entire design. A spawn claim is
     * four blocks of ground when it is created and a chest, a bed and a first night's work
     * by the time anybody reassigns anybody. Deleting it silently as a side effect of an
     * unrelated command is not recoverable, and an operator who did want it gone has all
     * the time in the world to say so; an operator who did not cannot get it back. So the
     * old square is left standing and named, and {@code releaseOld} is the only path that
     * can delete anything - reached from {@code /sgen reassign <player> release} and from
     * nowhere else.
     *
     * @param owner      the player whose spawn moved
     * @param ownerName  what to call them in the line, since an operator typed a name and
     *                   should not be handed back a UUID to match up themselves. Falls back
     *                   to the UUID when a caller has no name, which the log-only paths do
     * @param oldSpawn   where their spawn was, or {@code null} if they had none
     * @param newSpawn   where it is now
     * @param releaseOld true only when an operator explicitly asked for the old claim to go
     * @param hint       what to suggest when the claim is left standing, or {@code null} for
     *                   a path with no command output to suggest anything in
     * @return one line for the operator, or {@code null} when there is nothing to say
     */
    public String handOffStaleClaim(UUID owner, String ownerName, Location oldSpawn,
                                    Location newSpawn, boolean releaseOld, String hint) {
        if (!isActive() || oldSpawn == null || oldSpawn.getWorld() == null) {
            return null;
        }
        if (isSameBlock(oldSpawn, newSpawn)) {
            // The spawn did not actually move, so the claim that is there is the claim that
            // should be there. Reachable from setspawn, where an operator re-running the
            // command with the same coordinates is not unusual.
            return null;
        }

        String who = (ownerName == null || ownerName.isEmpty()) ? String.valueOf(owner) : ownerName;
        if (!releaseOld) {
            return "The old spawn claim at " + describe(oldSpawn) + " is still standing; it "
                    + "protects ground " + who + " no longer spawns on."
                    + (hint == null || hint.isEmpty() ? "" : " " + hint);
        }

        ReleaseResult result;
        try {
            result = current().release(oldSpawn, size(), owner);
            if (result == null) {
                result = ReleaseResult.of(ReleaseOutcome.REFUSED, "the provider returned no result.");
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "The protection provider threw while releasing the old spawn "
                    + "claim for " + owner + ". It is still standing.", t);
            result = ReleaseResult.of(ReleaseOutcome.REFUSED,
                    "the provider threw " + t.getClass().getSimpleName());
        }

        String where = describe(oldSpawn);
        return switch (result.outcome()) {
            case RELEASED -> "Released the old spawn claim at " + where + ".";
            case NOT_FOUND -> "There was no claim at the old spawn " + where + "; nothing to release.";
            case NOT_OURS -> "The old spawn claim at " + where + " was left alone: " + result.detail();
            case REFUSED -> "The old spawn claim at " + where + " could not be released: "
                    + (result.hasDetail() ? result.detail() : "no reason given.");
            case UNSUPPORTED -> "The active protection provider cannot release claims, so the old "
                    + "spawn claim at " + where + " is still standing.";
            // isActive() was true on the way in, so this needs the provider to have gone
            // away mid-command. Say so rather than reporting a release that did not happen.
            case PROVIDER_UNAVAILABLE -> "The protection provider went away before the old spawn "
                    + "claim at " + where + " could be released; it is still standing.";
        };
    }

    /** A location as an operator reads it: block coordinates and the world's name. */
    public static String describe(Location location) {
        String world = location.getWorld() == null ? "an unloaded world" : location.getWorld().getName();
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                + " in '" + world + "'";
    }

    /** Whether two points sit on the same block of the same world. */
    private static boolean isSameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    /** The provider in force right now, never {@code null}. */
    private ProtectionProvider current() {
        ProtectionProvider active = provider.get();
        return active == null ? NoOpProtectionProvider.INSTANCE : active;
    }
}
