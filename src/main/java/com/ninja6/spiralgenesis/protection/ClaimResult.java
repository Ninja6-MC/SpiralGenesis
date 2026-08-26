package com.ninja6.spiralgenesis.protection;

import java.util.Objects;

/**
 * The answer to a single {@link ProtectionProvider#reserve} call.
 *
 * <p>The {@link ClaimOutcome} is the part callers branch on. The detail is free text from
 * the provider for the log line and nothing else: never parsed, never switched on, and
 * never shown to a player. It exists because {@link ClaimOutcome#REFUSED} and
 * {@link ClaimOutcome#BELOW_MINIMUM_SIZE} are only actionable when the reason travels with
 * them.
 *
 * @param outcome what happened, and the only field worth a decision
 * @param detail  provider wording for the log, empty when there is nothing to add
 */
public record ClaimResult(ClaimOutcome outcome, String detail) {

    public ClaimResult {
        Objects.requireNonNull(outcome, "outcome");
        detail = (detail == null) ? "" : detail;
    }

    /** A result carrying an outcome and no further explanation. */
    public static ClaimResult of(ClaimOutcome outcome) {
        return new ClaimResult(outcome, "");
    }

    /** A result carrying an outcome and the provider's own wording for the log. */
    public static ClaimResult of(ClaimOutcome outcome, String detail) {
        return new ClaimResult(outcome, detail);
    }

    /** Whether the ground is now reserved as a direct result of this call. */
    public boolean isCreated() {
        return outcome == ClaimOutcome.CREATED;
    }

    /** Whether a detail string is present, so a caller can word its log line either way. */
    public boolean hasDetail() {
        return !detail.isEmpty();
    }
}
