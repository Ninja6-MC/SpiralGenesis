package com.ninja6.spiralgenesis.protection;

import java.util.Objects;

/**
 * The answer to a single {@link ProtectionProvider#release} call.
 *
 * <p>Shaped exactly like {@link ClaimResult}, for the same reason: the outcome is what a
 * caller branches on and the detail is free text for a human. The detail matters more here
 * than it does on the claim side, because {@link ReleaseOutcome#NOT_OURS} is only actionable
 * when it says what was found instead.
 *
 * @param outcome what happened, and the only field worth a decision
 * @param detail  provider wording for the operator, empty when there is nothing to add
 */
public record ReleaseResult(ReleaseOutcome outcome, String detail) {

    public ReleaseResult {
        Objects.requireNonNull(outcome, "outcome");
        detail = (detail == null) ? "" : detail;
    }

    /** A result carrying an outcome and no further explanation. */
    public static ReleaseResult of(ReleaseOutcome outcome) {
        return new ReleaseResult(outcome, "");
    }

    /** A result carrying an outcome and the provider's own wording. */
    public static ReleaseResult of(ReleaseOutcome outcome, String detail) {
        return new ReleaseResult(outcome, detail);
    }

    /** Whether ground stopped being claimed as a direct result of this call. */
    public boolean isReleased() {
        return outcome == ReleaseOutcome.RELEASED;
    }

    /** Whether a detail string is present, so a caller can word its line either way. */
    public boolean hasDetail() {
        return !detail.isEmpty();
    }
}
