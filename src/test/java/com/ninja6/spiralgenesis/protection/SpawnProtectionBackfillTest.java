package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the {@code /sgen protect} backfill promises: it finishes, it is safe to run twice,
 * it does not do the whole job in one tick, and a provider refusing every call is a report
 * rather than an exception.
 */
class SpawnProtectionBackfillTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Map<UUID, StoredSpawn> records(int count, String worldName) {
        Map<UUID, StoredSpawn> records = new HashMap<>();
        for (int i = 0; i < count; i++) {
            records.put(UUID.randomUUID(), new StoredSpawn(worldName, i * 32, 64, 0, i, i, 0,
                    "player" + i, "JAVA"));
        }
        return records;
    }

    private SpawnProtector protectorFor(ProtectionProvider provider) {
        return new SpawnProtector(Logger.getLogger("BackfillTest"), () -> provider, () -> 9);
    }

    /** Drives the job the way the plugin's scheduled task does, and counts the ticks. */
    private int drive(SpawnProtectionBackfill job) {
        int ticks = 0;
        while (!job.runBatch()) {
            ticks++;
            if (ticks > 10_000) {
                throw new AssertionError("the backfill never finished");
            }
        }
        return ticks + 1;
    }

    @Test
    @DisplayName("every stored spawn is claimed exactly once")
    void claimsEveryStoredSpawn() {
        RecordingProvider provider = new RecordingProvider();
        Map<UUID, StoredSpawn> stored = records(30, "world");

        SpawnProtectionBackfill job = new SpawnProtectionBackfill(protectorFor(provider), stored);
        drive(job);

        assertEquals(30, job.total());
        assertEquals(30, job.created());
        assertEquals(0, job.skipped());
        assertEquals(0, job.failed());

        Set<UUID> asked = new HashSet<>();
        for (RecordingProvider.Reservation reservation : provider.reservations) {
            assertTrue(asked.add(reservation.owner()), "asked twice for " + reservation.owner());
        }
        assertEquals(stored.keySet(), asked);
    }

    @Test
    @DisplayName("running it a second time creates nothing and fails nothing")
    void isIdempotent() {
        Map<UUID, StoredSpawn> stored = records(12, "world");

        // A provider with real state: the first ask for a square creates it, every ask after
        // that finds it already there. That is the whole idempotency mechanism - there is no
        // flag in data.yml recording that a backfill has run. It also pins the ordering the
        // GriefPrevention provider now guarantees: an already-claimed square answers
        // ALREADY_CLAIMED before any claim-block balance is consulted, so a second pass under
        // PLAYER_CLAIM reports work already done as skipped rather than as failed for
        // insufficient claim blocks.
        RecordingProvider provider = new RecordingProvider().rememberingClaims();

        SpawnProtectionBackfill first = new SpawnProtectionBackfill(protectorFor(provider), stored);
        drive(first);
        assertEquals(12, first.created());

        SpawnProtectionBackfill second = new SpawnProtectionBackfill(protectorFor(provider), stored);
        drive(second);

        assertEquals(0, second.created(), "a second pass must create nothing");
        assertEquals(12, second.skipped());
        assertEquals(0, second.failed(), "an existing claim is not a failure");
    }

    @Test
    @DisplayName("one tick never processes the whole store")
    void batchesAcrossTicks() {
        RecordingProvider provider = new RecordingProvider();
        int count = SpawnProtectionBackfill.MAX_PER_TICK * 3;

        SpawnProtectionBackfill job = new SpawnProtectionBackfill(protectorFor(provider),
                records(count, "world"));

        assertFalse(job.runBatch(), "a store this size cannot be finished in one tick");
        assertTrue(provider.reservations.size() <= SpawnProtectionBackfill.MAX_PER_TICK,
                "one tick claimed " + provider.reservations.size() + " squares");

        drive(job);
        assertEquals(count, job.created());
    }

    @Test
    @DisplayName("a provider that refuses everything is a report, not an exception")
    void reportsFailuresRatherThanThrowing() {
        RecordingProvider provider = new RecordingProvider().answering(ClaimOutcome.REFUSED);

        SpawnProtectionBackfill job = new SpawnProtectionBackfill(protectorFor(provider),
                records(5, "world"));
        drive(job);

        assertEquals(0, job.created());
        assertEquals(5, job.failed());
        assertTrue(job.summary().contains("5 failed"), job.summary());
        assertTrue(job.summary().contains("first failure"), job.summary());
    }

    @Test
    @DisplayName("a spawn in an unloaded world is skipped, not failed")
    void skipsUnloadedWorlds() {
        RecordingProvider provider = new RecordingProvider();

        SpawnProtectionBackfill job = new SpawnProtectionBackfill(protectorFor(provider),
                records(4, "a-world-that-was-retired"));
        drive(job);

        assertEquals(4, job.skipped());
        assertEquals(0, job.failed());
        assertTrue(provider.reservations.isEmpty(),
                "there is nothing to claim around a point that does not currently exist");
    }

    @Test
    @DisplayName("an empty store finishes immediately")
    void anEmptyStoreIsAlreadyDone() {
        SpawnProtectionBackfill job = new SpawnProtectionBackfill(
                protectorFor(new RecordingProvider()), Map.of());

        assertTrue(job.isFinished());
        assertTrue(job.runBatch());
        assertEquals(0, job.total());
        assertTrue(job.summary().contains("0 created"), job.summary());
    }
}
