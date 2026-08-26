package com.ninja6.spiralgenesis.protection;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * A protection provider that answers whatever a test tells it to and writes down what it
 * was asked.
 *
 * <p>Shared rather than repeated per suite, because the same three questions come up in
 * every one of them: was the provider called at all, was it called with the square the
 * caller was supposed to ask for, and does the caller survive an answer it did not want.
 * Naming no GriefPrevention type, so it exercises the seam and not an implementation.
 */
public final class RecordingProvider implements ProtectionProvider {

    /** One call to {@link #reserve}. */
    public record Reservation(Location centre, int size, UUID owner) { }

    /** One call to {@link #release}. */
    public record Releasing(Location centre, int size, UUID owner) { }

    public final List<Reservation> reservations = new ArrayList<>();
    public final List<Releasing> releases = new ArrayList<>();

    private boolean available = true;

    /**
     * Whether even answering {@link #isAvailable()} throws.
     *
     * <p>Its own mode rather than part of {@link #throwing()}, because it is a different
     * failure and the first version of these tests missed it precisely by conflating the
     * two: a provider that throws from the question "can you claim anything" is reached
     * earlier than one that throws from the claim itself, and a caller that reads
     * availability outside its guard survives the second and not the first.
     */
    private boolean availabilityThrows;
    private Function<Reservation, ClaimResult> answer = r -> ClaimResult.of(ClaimOutcome.CREATED);
    private Function<Releasing, ReleaseResult> releaseAnswer = r -> ReleaseResult.of(ReleaseOutcome.RELEASED);

    /** Runs on every reserve before the answer is produced, for ordering assertions. */
    private Runnable onReserve = () -> { };

    public RecordingProvider available(boolean value) {
        this.available = value;
        return this;
    }

    public RecordingProvider answering(ClaimOutcome outcome) {
        this.answer = r -> ClaimResult.of(outcome, "test");
        return this;
    }

    public RecordingProvider answering(Function<Reservation, ClaimResult> function) {
        this.answer = function;
        return this;
    }

    public RecordingProvider releasing(ReleaseOutcome outcome) {
        this.releaseAnswer = r -> ReleaseResult.of(outcome, "test");
        return this;
    }

    public RecordingProvider onReserve(Runnable hook) {
        this.onReserve = hook;
        return this;
    }

    /** A provider whose very availability check throws, before any claim is attempted. */
    public RecordingProvider throwingAvailability() {
        this.availabilityThrows = true;
        return this;
    }

    /**
     * Stands in for a provider with real state: the first ask for a square creates it and
     * every ask after that finds it already there.
     *
     * <p>What makes an idempotency test mean anything. A provider that answers CREATED
     * every time would let a second pass look successful while doing the work twice.
     */
    public RecordingProvider rememberingClaims() {
        java.util.Set<UUID> claimed = new java.util.HashSet<>();
        this.answer = reservation -> claimed.add(reservation.owner())
                ? ClaimResult.of(ClaimOutcome.CREATED)
                : ClaimResult.of(ClaimOutcome.ALREADY_CLAIMED, "already there");
        return this;
    }

    /** The provider that breaks the contract: it throws instead of answering. */
    public RecordingProvider throwing() {
        this.answer = r -> {
            throw new IllegalStateException("provider exploded");
        };
        this.releaseAnswer = r -> {
            throw new IllegalStateException("provider exploded");
        };
        return this;
    }

    @Override
    public String name() {
        return "RECORDING";
    }

    @Override
    public boolean isAvailable() {
        if (availabilityThrows) {
            // NoClassDefFoundError rather than an exception, because that is what a
            // protection plugin whose API has moved actually throws, and it is an Error:
            // a caller catching Exception would not contain it.
            throw new NoClassDefFoundError("provider classes are missing");
        }
        return available;
    }

    @Override
    public ClaimResult reserve(Location centre, int size, UUID owner) {
        Reservation reservation = new Reservation(centre, size, owner);
        reservations.add(reservation);
        onReserve.run();
        return answer.apply(reservation);
    }

    @Override
    public ReleaseResult release(Location centre, int size, UUID owner) {
        Releasing releasing = new Releasing(centre, size, owner);
        releases.add(releasing);
        return releaseAnswer.apply(releasing);
    }
}
