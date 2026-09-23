package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the {@code /sgen release-all} job promises: every stored spawn is asked about once,
 * at its own point, with the configured size and its own owner; every outcome is counted
 * where the summary says it is; and the claims left standing are named one by one.
 */
class SpawnClaimReleaseTest {

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

    private SpawnProtector protectorFor(ProtectionProvider provider) {
        return new SpawnProtector(Logger.getLogger("ClaimReleaseTest"), () -> provider, () -> 9);
    }

    private static StoredSpawn at(String worldName, int x, String name) {
        return new StoredSpawn(worldName, x, 64, 0, 0, 0, 0, name, "JAVA");
    }

    private static int drive(SpawnClaimRelease job) {
        int ticks = 0;
        while (!job.runBatch()) {
            ticks++;
            if (ticks > 10_000) {
                throw new AssertionError("the job never finished");
            }
        }
        return ticks + 1;
    }

    @Test
    @DisplayName("each stored spawn is released at its own point, for its own owner, at the configured size")
    void releasesEachStoredSpawn() {
        RecordingProvider provider = new RecordingProvider();
        Map<UUID, StoredSpawn> records = new HashMap<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        records.put(first, at("world", 100, "Ann"));
        records.put(second, at("world", 900, "Bob"));

        SpawnClaimRelease job = new SpawnClaimRelease(protectorFor(provider), records, line -> { }, () -> null);
        drive(job);

        assertEquals(2, provider.releases.size());
        for (RecordingProvider.Releasing asked : provider.releases) {
            assertEquals(9, asked.size());
            int expectedX = asked.owner().equals(first) ? 100 : 900;
            assertEquals(expectedX, asked.centre().getBlockX(),
                    "the claim looked for must be the one around that owner's own plot");
        }
        assertEquals(2, job.released());
        assertTrue(job.summary().contains("2 released"), job.summary());
    }

    @Test
    @DisplayName("every outcome lands in its own count, and only claims left standing are listed")
    void countsEveryOutcome() {
        Map<UUID, StoredSpawn> records = new HashMap<>();
        records.put(UUID.randomUUID(), at("world", 1, "Released"));
        records.put(UUID.randomUUID(), at("world", 2, "NotOurs"));
        records.put(UUID.randomUUID(), at("world", 3, "NotFound"));
        records.put(UUID.randomUUID(), at("world", 4, "Gone"));
        records.put(UUID.randomUUID(), at("world", 5, "Refused"));
        records.put(UUID.randomUUID(), at("retired_world", 6, "Unloaded"));
        RecordingProvider provider = new RecordingProvider().releasing(r ->
                switch (r.centre().getBlockX()) {
                    case 1 -> ReleaseResult.of(ReleaseOutcome.RELEASED);
                    case 2 -> ReleaseResult.of(ReleaseOutcome.NOT_OURS, "it has been resized.");
                    case 3 -> ReleaseResult.of(ReleaseOutcome.NOT_FOUND);
                    case 4 -> ReleaseResult.of(ReleaseOutcome.PROVIDER_UNAVAILABLE);
                    default -> ReleaseResult.of(ReleaseOutcome.REFUSED, "GriefPrevention threw.");
                });
        List<String> listed = new ArrayList<>();

        SpawnClaimRelease job = new SpawnClaimRelease(protectorFor(provider), records, listed::add, () -> null);
        drive(job);

        assertEquals(5, provider.releases.size(), "an unloaded world is never asked about");
        assertEquals(1, job.released());
        assertEquals(1, job.notOurs());
        assertEquals(1, job.notFound());
        assertEquals(1, job.unavailable());
        assertEquals(1, job.failed());
        assertEquals(1, job.unloaded());
        String summary = job.summary();
        assertTrue(summary.contains("1 released, 1 not ours, 1 with no claim, 1 with no "
                + "GriefPrevention, 1 in unloaded worlds, 1 failed, 0 skipped, out of 6"), summary);
        assertTrue(summary.contains("GriefPrevention threw."), summary);

        assertEquals(2, listed.size(), listed.toString());
        assertTrue(listed.stream().anyMatch(l -> l.contains("NotOurs") && l.contains("resized")),
                listed.toString());
        assertTrue(listed.stream().anyMatch(l -> l.contains("Refused")), listed.toString());
    }

    @Test
    @DisplayName("a provider that throws on every release is a count, not an exception")
    void aThrowingProviderIsCounted() {
        Map<UUID, StoredSpawn> records = new HashMap<>();
        records.put(UUID.randomUUID(), at("world", 1, "Ann"));
        SpawnClaimRelease job = new SpawnClaimRelease(
                protectorFor(new RecordingProvider().throwing()), records, line -> { }, () -> null);

        drive(job);

        assertEquals(1, job.failed());
        assertEquals(0, job.released());
    }

    @Test
    @DisplayName("a large store is spread over several ticks")
    void doesNotDoEverythingInOneTick() {
        Map<UUID, StoredSpawn> records = new HashMap<>();
        for (int i = 0; i < SpawnProtectionBackfill.MAX_PER_TICK * 3; i++) {
            records.put(UUID.randomUUID(), at("world", i * 32, "p" + i));
        }
        SpawnClaimRelease job = new SpawnClaimRelease(protectorFor(new RecordingProvider()),
                records, line -> { }, () -> null);

        assertFalse(job.runBatch(), "one tick must not take the whole store");
        drive(job);
        assertEquals(records.size(), job.released());
    }

    @Test
    @DisplayName("a halt reason stops the run before the next entry and skips the rest")
    void stopsWhenTheHaltReasonAppears() {
        Map<UUID, StoredSpawn> records = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            records.put(UUID.randomUUID(), at("world", i * 32, "p" + i));
        }
        RecordingProvider provider = new RecordingProvider();
        // Stands in for a /sgen reload to PLAYER_CLAIM landing after the second release.
        SpawnClaimRelease job = new SpawnClaimRelease(protectorFor(provider), records,
                line -> { }, () -> provider.releases.size() >= 2 ? "claim-as changed" : null);

        assertTrue(job.runBatch(), "a halted run is finished");

        assertEquals(2, provider.releases.size(), "nothing may be released after the halt");
        assertEquals(2, job.released());
        assertEquals(3, job.skipped());
        assertEquals("claim-as changed", job.haltedBecause());
        assertTrue(job.summary().startsWith("Spawn claim release stopped because claim-as changed"),
                job.summary());
        assertTrue(job.summary().contains("3 skipped"), job.summary());
    }
}
