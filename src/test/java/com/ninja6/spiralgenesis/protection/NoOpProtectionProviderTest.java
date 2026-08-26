package com.ninja6.spiralgenesis.protection;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NoOpProtectionProviderTest {

    private final ProtectionProvider provider = NoOpProtectionProvider.INSTANCE;

    @Test
    @DisplayName("The no-op provider reports itself unavailable without touching a server")
    void testUnavailable() {
        assertFalse(provider.isAvailable());
        assertEquals("NONE", provider.name());
    }

    @Test
    @DisplayName("Reserving through the no-op claims nothing and says the provider is unavailable")
    void testReserveClaimsNothing() {
        // A world-less Location is enough: nothing here dereferences it. That is the point
        // of the no-op - it is reachable on a server with no protection plugin at all.
        ClaimResult result = provider.reserve(new Location(null, 0, 64, 0), 9, UUID.randomUUID());

        assertNotNull(result);
        assertEquals(ClaimOutcome.PROVIDER_UNAVAILABLE, result.outcome());
        assertFalse(result.isCreated());
        assertFalse(result.hasDetail(), "the disabled path has nothing to explain");
    }

    @Test
    @DisplayName("The no-op tolerates a null request rather than throwing on the disabled path")
    void testReserveToleratesNulls() {
        assertEquals(ClaimOutcome.PROVIDER_UNAVAILABLE, provider.reserve(null, 0, null).outcome());
    }
}
