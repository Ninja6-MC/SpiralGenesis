package com.ninja6.spiralgenesis.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimResultTest {

    @Test
    @DisplayName("A missing detail is normalised to empty rather than carried as null")
    void testDetailNormalised() {
        ClaimResult result = ClaimResult.of(ClaimOutcome.REFUSED, null);

        assertEquals("", result.detail());
        assertFalse(result.hasDetail());
        assertFalse(ClaimResult.of(ClaimOutcome.REFUSED).hasDetail());
    }

    @Test
    @DisplayName("A detail is carried through for the log line that needs it")
    void testDetailPreserved() {
        ClaimResult result = ClaimResult.of(ClaimOutcome.BELOW_MINIMUM_SIZE, "minimum is 10");

        assertTrue(result.hasDetail());
        assertEquals("minimum is 10", result.detail());
    }

    @Test
    @DisplayName("Only CREATED reports that ground was reserved")
    void testIsCreated() {
        assertTrue(ClaimResult.of(ClaimOutcome.CREATED).isCreated());
        for (ClaimOutcome outcome : ClaimOutcome.values()) {
            if (outcome != ClaimOutcome.CREATED) {
                assertFalse(ClaimResult.of(outcome).isCreated(), outcome + " did not create a claim");
            }
        }
    }

    @Test
    @DisplayName("An outcome is mandatory, since it is the field every caller branches on")
    void testOutcomeRequired() {
        assertThrows(NullPointerException.class, () -> ClaimResult.of(null));
        assertThrows(NullPointerException.class, () -> new ClaimResult(null, "why"));
    }
}
