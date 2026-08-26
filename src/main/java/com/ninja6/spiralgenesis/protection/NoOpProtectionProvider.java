package com.ninja6.spiralgenesis.protection;

import org.bukkit.Location;

import java.util.UUID;

/**
 * The provider used when protection is switched off, or when the configured plugin is not
 * installed on this server.
 *
 * <p>It exists so the disabled path is a real object rather than a {@code null} check
 * repeated at every call site, and so the configuration tests have something to assert
 * against without a third-party jar on the test classpath. Reserving nothing is the
 * correct behaviour on a default install: this feature is opt-in, and a server that has
 * never heard of a claim plugin must behave exactly as it did before it existed.
 *
 * <p>Stateless and immutable, so a single instance serves the whole server.
 */
public final class NoOpProtectionProvider implements ProtectionProvider {

    /** The shared instance. Nothing here has per-server state. */
    public static final NoOpProtectionProvider INSTANCE = new NoOpProtectionProvider();

    /** Matches the {@code NONE} configuration value, so a log line and the file agree. */
    @Override
    public String name() {
        return "NONE";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    /**
     * Reserves nothing and says so. Arguments are ignored, including {@code null} ones:
     * a provider that cannot claim has no reason to validate a request it will not make.
     */
    @Override
    public ClaimResult reserve(Location centre, int size, UUID owner) {
        return ClaimResult.of(ClaimOutcome.PROVIDER_UNAVAILABLE);
    }
}
