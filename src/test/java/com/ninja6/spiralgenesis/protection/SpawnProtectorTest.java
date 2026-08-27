package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two promises {@link SpawnProtector} exists to keep.
 *
 * <p>The first is that no protection plugin can break allocation: whatever a provider does,
 * including throwing, the caller gets a result back and carries on. The second is that
 * nothing is ever deleted unless somebody asked in as many words, which is a property of
 * one method and is asserted here as the absence of a call rather than as an outcome -
 * "the provider's release was never reached" is the only assertion that would actually have
 * caught a default path that quietly deleted something.
 */
class SpawnProtectorTest {

    private ServerMock server;
    private WorldMock world;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SpawnProtector protectorFor(ProtectionProvider provider) {
        return new SpawnProtector(Logger.getLogger("SpawnProtectorTest"), () -> provider, () -> 9);
    }

    private Location at(int x, int z) {
        return new Location(world, x, 64, z);
    }

    @Test
    @DisplayName("a claim is asked for at the spawn point, with the configured size")
    void claimsTheConfiguredSquare() {
        RecordingProvider provider = new RecordingProvider();
        Location spawn = at(100, -40);

        ClaimResult result = protectorFor(provider).protect(owner, spawn, "test");

        assertEquals(ClaimOutcome.CREATED, result.outcome());
        assertEquals(1, provider.reservations.size());
        RecordingProvider.Reservation asked = provider.reservations.get(0);
        assertSame(spawn, asked.centre());
        assertEquals(9, asked.size());
        assertEquals(owner, asked.owner());
    }

    @Test
    @DisplayName("a provider that throws costs a claim and nothing else")
    void aThrowingProviderIsContained() {
        RecordingProvider provider = new RecordingProvider().throwing();

        ClaimResult result = protectorFor(provider).protect(owner, at(0, 0), "test");

        // The point is that this line is reached at all: the contract says a provider
        // swallows its own throwables, and this asserts the plugin survives one that does
        // not, because an exception here would escape into allocation.
        assertEquals(ClaimOutcome.REFUSED, result.outcome());
        assertTrue(result.hasDetail());
    }

    @Test
    @DisplayName("a provider that breaks its no-null contract is treated as a refusal")
    void aNullReturningProviderIsContained() {
        ProtectionProvider broken = new RecordingProvider().answering(r -> null);

        ClaimResult result = protectorFor(broken).protect(owner, at(0, 0), "test");

        assertEquals(ClaimOutcome.REFUSED, result.outcome());
    }

    @Test
    @DisplayName("nothing is asked of an unavailable provider")
    void anUnavailableProviderIsNeverCalled() {
        RecordingProvider provider = new RecordingProvider().available(false);

        ClaimResult result = protectorFor(provider).protect(owner, at(0, 0), "test");

        assertEquals(ClaimOutcome.PROVIDER_UNAVAILABLE, result.outcome());
        assertTrue(provider.reservations.isEmpty(),
                "a default install must not reach the provider at all");
    }

    @Test
    @DisplayName("the default hand-off names the stale claim and deletes nothing")
    void theDefaultHandOffDeletesNothing() {
        RecordingProvider provider = new RecordingProvider();

        String line = protectorFor(provider).handOffStaleClaim(owner, "Bob", at(10, 10), at(500, 500),
                false, "Re-run with 'release' to remove it.");

        assertNotNull(line);
        assertTrue(line.contains("10, 64, 10"), "the operator has to be told where it is: " + line);
        assertTrue(line.contains("still standing"), line);
        assertTrue(line.contains("release"), "the way to remove it belongs in the line: " + line);
        assertTrue(line.contains("Bob"), "an operator typed a name and should read one back: " + line);
        assertFalse(line.contains(owner.toString()), "and not a raw UUID: " + line);
        assertTrue(provider.releases.isEmpty(),
                "the default path must be incapable of deleting anything");
    }

    @Test
    @DisplayName("the release flag is the only thing that reaches the provider's release")
    void theFlagReleasesTheOldClaim() {
        RecordingProvider provider = new RecordingProvider().releasing(ReleaseOutcome.RELEASED);
        Location old = at(10, 10);

        String line = protectorFor(provider).handOffStaleClaim(owner, "Bob", old, at(500, 500), true, null);

        assertEquals(1, provider.releases.size());
        assertSame(old, provider.releases.get(0).centre());
        assertEquals(9, provider.releases.get(0).size());
        assertEquals(owner, provider.releases.get(0).owner());
        assertNotNull(line);
        assertTrue(line.startsWith("Released"), line);
    }

    @Test
    @DisplayName("a claim the provider does not recognise is reported, not deleted")
    void anUnrecognisedClaimIsReported() {
        RecordingProvider provider = new RecordingProvider().releasing(ReleaseOutcome.NOT_OURS);

        String line = protectorFor(provider).handOffStaleClaim(owner, "Bob", at(10, 10), at(500, 500),
                true, null);

        assertNotNull(line);
        assertTrue(line.contains("left alone"), line);
    }

    @Test
    @DisplayName("a provider that throws on release leaves the claim standing and says so")
    void aThrowingReleaseIsContained() {
        RecordingProvider provider = new RecordingProvider().throwing();

        String line = protectorFor(provider).handOffStaleClaim(owner, "Bob", at(10, 10), at(500, 500),
                true, null);

        assertNotNull(line);
        assertTrue(line.contains("could not be released"), line);
    }

    @Test
    @DisplayName("a server with protection off says nothing about claims at all")
    void aDisabledServerSaysNothing() {
        SpawnProtector protector = protectorFor(NoOpProtectionProvider.INSTANCE);

        assertNull(protector.handOffStaleClaim(owner, "Bob", at(10, 10), at(500, 500), false, "hint"),
                "a server that never claimed anything must read exactly as it did before");
        assertNull(protector.handOffStaleClaim(owner, "Bob", at(10, 10), at(500, 500), true, "hint"),
                "and must not be told a release happened either");
    }

    @Test
    @DisplayName("a spawn that did not actually move has no stale claim to report")
    void anUnmovedSpawnIsNotStale() {
        RecordingProvider provider = new RecordingProvider();

        // Reachable from setspawn, where re-running the command with the same coordinates
        // is an ordinary thing to do.
        assertNull(protectorFor(provider).handOffStaleClaim(owner, "Bob", at(10, 10), at(10, 10),
                false, "hint"));
        assertTrue(provider.releases.isEmpty());
    }

    /**
     * Everything a protector was asked to log, with the level it asked for.
     *
     * <p>Attached to a logger of its own per test rather than to a shared one, because the
     * level a record was published at is the whole assertion and a handler left behind on a
     * shared logger would collect another test's lines as well.
     */
    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }

        private LogRecord only() {
            assertEquals(1, records.size(), records.toString());
            return records.get(0);
        }
    }

    /** A protector whose log lines are collected instead of printed. */
    private SpawnProtector protectorLogging(ProtectionProvider provider, CapturingHandler handler) {
        Logger logger = Logger.getLogger("SpawnProtectorTest." + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        // ALL, so the assertion is about the level the protector chose rather than about
        // what this logger happens to be configured to pass through. A FINE line that the
        // logger swallowed and a FINE line the protector never wrote look identical
        // otherwise, which is exactly the confusion being tested against.
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        return new SpawnProtector(logger, () -> provider, () -> 9);
    }

    @Test
    @DisplayName("an overlapping claim is reported at a level a server owner actually sees")
    void anOverlapIsLoggedAtInfo() {
        RecordingProvider provider = new RecordingProvider()
                .answering(r -> ClaimResult.of(ClaimOutcome.ALREADY_CLAIMED,
                        "the square overlaps claim 42 (Steve)."));
        CapturingHandler handler = new CapturingHandler();

        protectorLogging(provider, handler).protect(owner, at(10, 10), "first allocation");

        LogRecord line = handler.only();
        // FINE is below the default JUL level, so this outcome used to reach no console at
        // all. It is per-player, transient, and the only thing anywhere that could tell an
        // owner a player silently has no protection.
        assertEquals(Level.INFO, line.getLevel(), line.getMessage());
        assertTrue(line.getMessage().contains("claim 42"),
                "the claim in the way has to be named: " + line.getMessage());
        assertTrue(line.getMessage().contains("their cover there is whatever that claim"),
                "and that this plugin protected nobody: " + line.getMessage());
    }

    @Test
    @DisplayName("a square below the provider's minimum stays quiet, since startup already said so")
    void belowMinimumSizeStaysQuiet() {
        RecordingProvider provider = new RecordingProvider()
                .answering(ClaimOutcome.BELOW_MINIMUM_SIZE);
        CapturingHandler handler = new CapturingHandler();

        protectorLogging(provider, handler).protect(owner, at(10, 10), "first allocation");

        // Permanent and server-wide rather than per-player: the provider reports it once at
        // startup naming both numbers, so a line here would be that same fact repeated for
        // every player who ever joins, for as long as nobody edits the file.
        assertEquals(Level.FINE, handler.only().getLevel());
    }

    @Test
    @DisplayName("a default install still says nothing at all")
    void anAbsentProviderLogsNothing() {
        RecordingProvider provider = new RecordingProvider().available(false);
        CapturingHandler handler = new CapturingHandler();

        protectorLogging(provider, handler).protect(owner, at(10, 10), "first allocation");

        assertTrue(handler.records.isEmpty(),
                "a server that never heard of a claim plugin must read as it always did: "
                        + handler.records);
    }
}
