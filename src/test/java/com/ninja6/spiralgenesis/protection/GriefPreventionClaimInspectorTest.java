package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.ClaimOwnership;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.TestClaims;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class GriefPreventionClaimInspectorTest {
    private WorldMock world;
    private final UUID player = UUID.randomUUID();
    @BeforeEach void setup() { world = MockBukkit.mock().addSimpleWorld("world"); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private ClaimInspection inspect(Claim claim) {
        return new GriefPreventionClaimInspector(Logger.getAnonymousLogger(), ignored -> claim)
                .inspect(new Location(world, -0.5, 300, -0.5), player);
    }

    @Test void readsActualBoundsAndSeparateNamedLevels() {
        Claim claim = TestClaims.claim(world, -7, -9, 5, 3, null, List.of(player.toString()), 1);
        ClaimInspection first = inspect(claim);
        assertEquals(ClaimInspection.Status.FOUND, first.status());
        assertEquals(13, first.width());
        assertEquals(-7, first.minX());
        assertEquals(ClaimOwnership.ADMIN_CLAIM, first.ownership());
        assertTrue(first.build());
        assertFalse(first.manage());
        claim.setPermission(player.toString(), ClaimPermission.Manage);
        assertTrue(inspect(claim).manage());
        assertEquals(21, inspect(TestClaims.claim(world, -10, -10, 10, 10,
                player, List.of(), 2)).width());
    }

    @Test void publicTrustAndForeignClaimsAreNotThePlayers() {
        assertEquals(ClaimInspection.Status.NONE, inspect(TestClaims.claim(world, -5, -5, 5, 5,
                null, List.of("public"), 1)).status());
        assertEquals(ClaimInspection.Status.NONE, inspect(TestClaims.claim(world, -5, -5, 5, 5,
                UUID.randomUUID(), List.of(), 2)).status());
        assertEquals(ClaimInspection.Status.NONE, inspect(null).status());
    }

    @Test void subdivisionUsesItsBoundsAndInheritanceRules() {
        Claim parent = TestClaims.claim(world, -10, -10, 10, 10, UUID.randomUUID(),
                List.of(player.toString()), 1);
        Claim child = TestClaims.claim(world, -2, -2, 2, 2, null, List.of(), 2);
        child.parent = parent;
        parent.children.add(child);
        assertEquals(5, inspect(child).width());
        assertEquals(ClaimOwnership.PLAYER_CLAIM, inspect(child).ownership());
        assertFalse(inspect(child).owner());
        assertTrue(inspect(child).build());
        child.setSubclaimRestrictions(true);
        assertEquals(ClaimInspection.Status.NONE, inspect(child).status());
    }

    @Test void ownerAndUnavailableAreDistinct() {
        assertTrue(inspect(TestClaims.claim(world, -5, -5, 5, 5, player, List.of(), 1)).owner());
        ClaimInspector inspector = new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> { throw new NoSuchMethodError("API moved"); });
        assertEquals(ClaimInspection.Status.UNAVAILABLE,
                inspector.inspect(new Location(world, 0, 0, 0), player).status());
        assertEquals(ClaimInspection.Status.UNAVAILABLE, inspector.inspect(null, player).status());
        assertEquals(ClaimInspection.Status.UNAVAILABLE,
                ProtectionProviders.createClaimInspector(MockBukkit.createMockPlugin())
                        .inspect(new Location(world, 0, 0, 0), player).status());
    }

    @Test void edgesAreDrawnOnlyForAClaimOfThePlayers() {
        Player viewer = MockBukkit.getMock().addPlayer();
        List<Claim> drawn = new ArrayList<>();
        Claim[] at = {TestClaims.claim(world, -5, -5, 5, 5, null,
                List.of(viewer.getUniqueId().toString()), 1)};
        ClaimInspector inspector = new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> at[0], () -> Material.STICK, (p, claim) -> drawn.add(claim));
        Location spawn = new Location(world, 0.5, 64, 0.5);
        inspector.showEdges(spawn, viewer);
        assertEquals(List.of(at[0]), drawn);
        at[0] = TestClaims.claim(world, -5, -5, 5, 5, UUID.randomUUID(), List.of("public"), 2);
        inspector.showEdges(spawn, viewer);
        at[0] = null;
        inspector.showEdges(spawn, viewer);
        assertEquals(1, drawn.size());

        ClaimInspector throwing = new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> { throw new NoClassDefFoundError("visualization moved"); },
                () -> Material.STICK, (p, claim) -> drawn.add(claim));
        assertDoesNotThrow(() -> throwing.showEdges(spawn, viewer));
        ClaimInspector failingDraw = new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> TestClaims.claim(world, -5, -5, 5, 5, viewer.getUniqueId(), List.of(), 3),
                () -> Material.STICK, (p, claim) -> { throw new IllegalStateException("off thread"); });
        assertDoesNotThrow(() -> failingDraw.showEdges(spawn, viewer));
        assertDoesNotThrow(() -> ClaimInspector.UNAVAILABLE.showEdges(spawn, viewer));
    }

    @Test void investigationToolIsTheConfiguredOne() {
        assertEquals("stick", new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> null, () -> Material.STICK, (p, claim) -> { }).investigationTool());
        assertEquals("golden hoe", new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> null, () -> Material.GOLDEN_HOE, (p, claim) -> { }).investigationTool());
        assertNull(new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> null, () -> null, (p, claim) -> { }).investigationTool());
        assertNull(new GriefPreventionClaimInspector(Logger.getAnonymousLogger(),
                ignored -> null, () -> { throw new NoSuchFieldError("moved"); },
                (p, claim) -> { }).investigationTool());
        // GriefPrevention is not running in the suite, so the live reader finds no instance.
        assertNull(new GriefPreventionClaimInspector(Logger.getAnonymousLogger(), ignored -> null)
                .investigationTool());
        assertNull(ClaimInspector.UNAVAILABLE.investigationTool());
    }
}
