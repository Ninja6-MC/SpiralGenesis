package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.math.CellArea;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.TestClaims;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim lookup allocation steers around, against real GriefPrevention {@link Claim}
 * objects in an index laid out the way GriefPrevention lays out its own: each top-level
 * claim listed under every chunk it touches.
 */
class GriefPreventionClaimLookupTest {

    private ServerMock server;
    private WorldMock world;
    private final List<LogRecord> logged = new ArrayList<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        logger = Logger.getLogger("GriefPreventionClaimLookupTest." + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** A chunk-keyed index over {@code claims}, counting the chunks it is asked about. */
    private static final class Index implements GriefPreventionClaimLookup.ClaimIndex {
        private final Map<Long, List<Claim>> byChunk = new HashMap<>();
        private final AtomicInteger chunksRead = new AtomicInteger();

        Index(Claim... claims) {
            for (Claim claim : claims) {
                add(claim);
            }
        }

        void add(Claim claim) {
            int minX = claim.getLesserBoundaryCorner().getBlockX() >> 4;
            int maxX = claim.getGreaterBoundaryCorner().getBlockX() >> 4;
            int minZ = claim.getLesserBoundaryCorner().getBlockZ() >> 4;
            int maxZ = claim.getGreaterBoundaryCorner().getBlockZ() >> 4;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    byChunk.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(claim);
                }
            }
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        @Override
        public Collection<Claim> claimsInChunk(int chunkX, int chunkZ) {
            chunksRead.incrementAndGet();
            return byChunk.getOrDefault(key(chunkX, chunkZ), List.of());
        }

        @Override
        public Object monitor() {
            return this;
        }
    }

    private GriefPreventionClaimLookup lookup(Index index) {
        return new GriefPreventionClaimLookup(logger, () -> index);
    }

    /** The 9x9 square a default spawn claim at {@code (x, z)} covers. */
    private static CellArea square(int x, int z) {
        return CellArea.square(x, z, 9);
    }

    @Test
    @DisplayName("a player's own claim is avoided")
    void aPlayersClaimIsAvoided() {
        Claim base = TestClaims.claim(world, 90, 90, 130, 130, UUID.randomUUID(), List.of(), 1);

        assertTrue(lookup(new Index(base)).overlapsClaim(world, square(100, 100)));
    }

    @Test
    @DisplayName("an administrative claim is avoided")
    void anAdminClaimIsAvoided() {
        Claim town = TestClaims.claim(world, -200, -200, 200, 200, null, List.of(), 2);

        assertTrue(town.isAdminClaim());
        assertTrue(lookup(new Index(town)).overlapsClaim(world, square(0, 0)));
    }

    @Test
    @DisplayName("a spawn claim left behind by an earlier allocation is avoided")
    void aLeftoverSpawnClaimIsAvoided() {
        // What ADMIN_CLAIM leaves after /sgen reassign without release: an administrative
        // 9x9 square trusting its old occupant, and no record pointing at it any more.
        UUID previous = UUID.randomUUID();
        Claim leftover = TestClaims.claim(world, 496, 496, 504, 504, null,
                List.of(previous.toString()), 3);

        GriefPreventionClaimLookup lookup = lookup(new Index(leftover));

        // A candidate four blocks off still overlaps, since its own square reaches back
        // over the old one.
        assertTrue(lookup.overlapsClaim(world, square(508, 500)));
        // Nine blocks off, the two squares only touch - the old square ends at 504 and the
        // new one begins at 505.
        assertFalse(lookup.overlapsClaim(world, square(509, 500)));
    }

    @Test
    @DisplayName("a claim in another world is not in the way")
    void anotherWorldsClaimIsIgnored() {
        WorldMock nether = server.addSimpleWorld("world_nether");
        Claim elsewhere = TestClaims.claim(nether, 90, 90, 130, 130, UUID.randomUUID(),
                List.of(), 4);

        assertFalse(lookup(new Index(elsewhere)).overlapsClaim(world, square(100, 100)));
    }

    @Test
    @DisplayName("a claim across a chunk boundary from the candidate is still found")
    void aClaimInTheNextChunkIsFound() {
        // The candidate is in chunk 0; its square reaches x = 19, in chunk 1, where the claim
        // is listed and nowhere else.
        Claim next = TestClaims.claim(world, 19, 0, 40, 10, UUID.randomUUID(), List.of(), 5);

        assertTrue(lookup(new Index(next)).overlapsClaim(world, square(15, 5)));
    }

    @Test
    @DisplayName("the cost is the chunks the square touches, not the claims on the server")
    void costIsBoundedByTheSquare() {
        Index index = new Index();
        for (int i = 0; i < 10_000; i++) {
            int x = 10_000 + (i % 100) * 20;
            int z = 10_000 + (i / 100) * 20;
            index.add(TestClaims.claim(world, x, z, x + 10, z + 10, UUID.randomUUID(),
                    List.of(), i));
        }

        assertFalse(lookup(index).overlapsClaim(world, square(15, 15)));
        // A 9-block square spans at most two chunks on each axis.
        assertTrue(index.chunksRead.get() <= 4, "chunks read: " + index.chunksRead.get());
    }

    @Test
    @DisplayName("GriefPrevention disabled since startup answers not claimed")
    void noIndexMeansNoClaims() {
        assertFalse(new GriefPreventionClaimLookup(logger, () -> null)
                .overlapsClaim(world, square(0, 0)));
    }

    @Test
    @DisplayName("a lookup that throws fails open and is reported once")
    void aFailingLookupFailsOpenOnce() {
        GriefPreventionClaimLookup broken = new GriefPreventionClaimLookup(logger, () -> {
            throw new NoSuchMethodError("DataStore.getClaims");
        });

        assertFalse(broken.overlapsClaim(world, square(0, 0)));
        assertFalse(broken.overlapsClaim(world, square(0, 0)));

        assertEquals(1, logged.size(), "one line, not one per candidate: " + logged);
        assertEquals(Level.WARNING, logged.get(0).getLevel());
    }

    @Test
    @DisplayName("without GriefPrevention there is no claim check")
    void noGriefPreventionMeansNoLookup() {
        Plugin plugin = MockBukkit.createMockPlugin("SpiralGenesisTest");

        assertSame(ClaimLookup.NONE, ProtectionProviders.createClaimLookup(plugin));
    }

    @Test
    @DisplayName("with GriefPrevention installed the check is on, whatever protection says")
    void griefPreventionPresentMeansALookup() {
        Plugin plugin = MockBukkit.createMockPlugin("SpiralGenesisTest");
        // Enabled under GriefPrevention's own name, which is all the selection asks. The
        // factory takes no configuration at all, so protection.enabled cannot switch it off.
        MockBukkit.createMockPlugin(GriefPreventionProtectionProvider.PLUGIN_NAME);

        ClaimLookup lookup = ProtectionProviders.createClaimLookup(plugin);

        assertNotSame(ClaimLookup.NONE, lookup);
        assertInstanceOf(GriefPreventionClaimLookup.class, lookup);
        // This stand-in never published GriefPrevention's API, so there is nothing to read
        // and the answer is "not claimed", without a throw.
        assertFalse(lookup.overlapsClaim(world, square(0, 0)));
    }
}
