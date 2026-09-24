package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.math.CellArea;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
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
 * The claim lookup allocation and repair steer around, against real GriefPrevention
 * {@link Claim} objects in an index laid out the way GriefPrevention lays out its own: each
 * top-level claim listed under every chunk it touches, and each subdivision only in its
 * parent's children.
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

    // Repair: whose claim a candidate's square reaches into.

    private final UUID player = UUID.randomUUID();

    /** A subdivision of {@code parent}, with no owner of its own, as GriefPrevention keeps one. */
    private Claim subdivide(Claim parent, int lesserX, int lesserZ, int greaterX, int greaterZ,
                            long id) {
        Claim child = TestClaims.claim(world, lesserX, lesserZ, greaterX, greaterZ, null,
                List.of(), id);
        child.parent = parent;
        parent.children.add(child);
        return child;
    }

    @Test
    @DisplayName("repair: the player's own spawn claim under ADMIN_CLAIM is theirs")
    void ownAdminSpawnClaimIsNotForeign() {
        // What claim-as: ADMIN_CLAIM makes: no owner, the player trusted with Build and
        // Manage, as GriefPreventionProtectionProvider grants them.
        Claim spawn = TestClaims.claim(world, 96, 96, 104, 104, null, List.of(), 10);
        spawn.setPermission(player.toString(), ClaimPermission.Build);
        spawn.setPermission(player.toString(), ClaimPermission.Manage);

        assertTrue(spawn.isAdminClaim());
        assertFalse(lookup(new Index(spawn)).overlapsForeignClaim(world, square(100, 100),
                player));
    }

    @Test
    @DisplayName("repair: the player's own spawn claim under PLAYER_CLAIM is theirs")
    void ownPlayerSpawnClaimIsNotForeign() {
        Claim spawn = TestClaims.claim(world, 96, 96, 104, 104, player, List.of(), 11);

        assertFalse(lookup(new Index(spawn)).overlapsForeignClaim(world, square(100, 100),
                player));
    }

    @Test
    @DisplayName("repair: another player's claim is foreign")
    void anotherPlayersClaimIsForeign() {
        Claim neighbour = TestClaims.claim(world, 90, 90, 130, 130, UUID.randomUUID(),
                List.of(), 12);

        GriefPreventionClaimLookup lookup = lookup(new Index(neighbour));

        assertTrue(lookup.overlapsForeignClaim(world, square(100, 100), player));
        // Only the columns it covers: a square clear of it is not affected.
        assertFalse(lookup.overlapsForeignClaim(world, square(80, 80), player));
    }

    @Test
    @DisplayName("repair: a foreign claim trusting the player by name at any level is theirs")
    void namedTrustAtAnyLevelIsNotForeign() {
        for (ClaimPermission level : List.of(ClaimPermission.Access,
                ClaimPermission.Inventory, ClaimPermission.Build, ClaimPermission.Manage)) {
            Claim neighbour = TestClaims.claim(world, 90, 90, 130, 130, UUID.randomUUID(),
                    List.of(), 13);
            neighbour.setPermission(player.toString(), level);

            assertFalse(lookup(new Index(neighbour)).overlapsForeignClaim(world,
                    square(100, 100), player), "trusted with " + level);
        }
    }

    @Test
    @DisplayName("repair: trust in a foreign claim granted to someone else does not count")
    void someoneElsesTrustIsForeign() {
        Claim neighbour = TestClaims.claim(world, 90, 90, 130, 130, UUID.randomUUID(),
                List.of(UUID.randomUUID().toString()), 14);

        assertTrue(lookup(new Index(neighbour)).overlapsForeignClaim(world, square(100, 100),
                player));
    }

    @Test
    @DisplayName("repair: a claim trusting public is foreign, at every level")
    void publicTrustIsForeign() {
        for (ClaimPermission level : List.of(ClaimPermission.Access,
                ClaimPermission.Inventory, ClaimPermission.Build, ClaimPermission.Manage)) {
            Claim town = TestClaims.claim(world, -200, -200, 200, 200, null, List.of(), 15);
            town.setPermission("public", level);

            assertTrue(lookup(new Index(town)).overlapsForeignClaim(world, square(0, 0),
                    player), "public trusted with " + level);
        }
    }

    @Test
    @DisplayName("repair: a subdivision trusting the player decides for itself in a foreign claim")
    void trustedSubdivisionInForeignParent() {
        Claim town = TestClaims.claim(world, -200, -200, 200, 200, null, List.of(), 16);
        Claim lot = subdivide(town, 80, 80, 120, 120, 17);
        lot.setPermission(player.toString(), ClaimPermission.Build);

        GriefPreventionClaimLookup lookup = lookup(new Index(town));

        assertFalse(lookup.overlapsForeignClaim(world, square(100, 100), player),
                "a square wholly inside the trusted subdivision");
        assertTrue(lookup.overlapsForeignClaim(world, square(118, 100), player),
                "a square reaching past it into the town");
        assertTrue(lookup.overlapsForeignClaim(world, square(0, 0), player),
                "a square in the town outside any subdivision");
    }

    @Test
    @DisplayName("repair: a square covered by two trusted subdivisions side by side is theirs")
    void squareSplitAcrossTrustedSubdivisions() {
        Claim town = TestClaims.claim(world, -200, -200, 200, 200, null, List.of(), 18);
        subdivide(town, 80, 80, 99, 120, 19)
                .setPermission(player.toString(), ClaimPermission.Access);
        subdivide(town, 100, 80, 120, 120, 20)
                .setPermission(player.toString(), ClaimPermission.Build);

        assertFalse(lookup(new Index(town)).overlapsForeignClaim(world, square(100, 100),
                player));
    }

    @Test
    @DisplayName("repair: a one-column gap between trusted subdivisions is foreign")
    void gapBetweenTrustedSubdivisions() {
        // Column x = 100 belongs to the town alone.
        Claim town = TestClaims.claim(world, -200, -200, 200, 200, null, List.of(), 27);
        subdivide(town, 80, 80, 99, 120, 28)
                .setPermission(player.toString(), ClaimPermission.Access);
        subdivide(town, 101, 80, 120, 120, 29)
                .setPermission(player.toString(), ClaimPermission.Build);

        GriefPreventionClaimLookup lookup = lookup(new Index(town));

        assertTrue(lookup.overlapsForeignClaim(world, square(100, 100), player),
                "a square spanning the gap");
        assertFalse(lookup.overlapsForeignClaim(world, square(94, 100), player),
                "a square ending at x = 98, inside the western subdivision");
        assertFalse(lookup.overlapsForeignClaim(world, square(105, 100), player),
                "a square starting at x = 101, inside the eastern subdivision");
    }

    @Test
    @DisplayName("repair: a restricted subdivision inside a claim trusting the player is foreign")
    void restrictedSubdivisionInTrustedParent() {
        Claim base = TestClaims.claim(world, 0, 0, 200, 200, UUID.randomUUID(), List.of(), 21);
        base.setPermission(player.toString(), ClaimPermission.Build);
        subdivide(base, 90, 90, 110, 110, 22).setSubclaimRestrictions(true);

        GriefPreventionClaimLookup lookup = lookup(new Index(base));

        assertTrue(lookup.overlapsForeignClaim(world, square(100, 100), player),
                "a subdivision that inherits nothing does not trust the player");
        assertFalse(lookup.overlapsForeignClaim(world, square(50, 50), player),
                "the rest of the claim still does");
    }

    @Test
    @DisplayName("repair: a subdivision inherits its parent's trust unless it restricts it")
    void unrestrictedSubdivisionInheritsTrust() {
        Claim base = TestClaims.claim(world, 0, 0, 200, 200, UUID.randomUUID(), List.of(), 23);
        base.setPermission(player.toString(), ClaimPermission.Access);
        subdivide(base, 90, 90, 110, 110, 24);

        assertFalse(lookup(new Index(base)).overlapsForeignClaim(world, square(100, 100),
                player));
    }

    @Test
    @DisplayName("repair: the owner of a claim owns its subdivisions, restricted or not")
    void ownerOwnsRestrictedSubdivision() {
        Claim base = TestClaims.claim(world, 0, 0, 200, 200, player, List.of(), 25);
        subdivide(base, 90, 90, 110, 110, 26).setSubclaimRestrictions(true);

        assertFalse(lookup(new Index(base)).overlapsForeignClaim(world, square(100, 100),
                player));
    }

    @Test
    @DisplayName("repair: without GriefPrevention no claim is foreign")
    void noClaimPluginMeansNothingForeign() {
        Plugin plugin = MockBukkit.createMockPlugin("SpiralGenesisTest");

        assertSame(ClaimLookup.NONE, ProtectionProviders.createClaimLookup(plugin));
        assertFalse(ClaimLookup.NONE.overlapsForeignClaim(world, square(0, 0), player));
        assertFalse(new GriefPreventionClaimLookup(logger, () -> null)
                .overlapsForeignClaim(world, square(0, 0), player));
    }

    @Test
    @DisplayName("repair: a lookup that throws fails open and is reported once")
    void aFailingForeignLookupFailsOpenOnce() {
        GriefPreventionClaimLookup broken = new GriefPreventionClaimLookup(logger, () -> {
            throw new NoSuchMethodError("Claim.hasExplicitPermission");
        });

        assertFalse(broken.overlapsForeignClaim(world, square(0, 0), player));
        assertFalse(broken.overlapsForeignClaim(world, square(0, 0), player));

        assertEquals(1, logged.size(), "one line, not one per candidate: " + logged);
    }
}
