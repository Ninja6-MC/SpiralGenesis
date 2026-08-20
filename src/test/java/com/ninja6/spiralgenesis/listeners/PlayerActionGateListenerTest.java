package com.ninja6.spiralgenesis.listeners;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allocation gate, exercised through the real Bukkit event pipeline.
 *
 * <p>These tests are the reason the gate keys off cancellation rather than off a login
 * plugin's API: a cancelled {@link PlayerMoveEvent} is indistinguishable to the listener
 * from one AuthMe cancelled, so limbo can be reproduced here with no third-party plugin,
 * no database and no game client. That is coverage the AuthMe adapter has never had.
 *
 * <p>The gate is constructed with a recording callback rather than the plugin's own
 * allocator: what is under test is when the gate opens, not what allocation then does,
 * and MockBukkit implements neither async chunk loading nor the region schedulers.
 */
class PlayerActionGateListenerTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;
    private List<String> released;
    private PlayerActionGateListener gate;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("SpiralGenesisTest");
        world = server.addSimpleWorld("world");
        released = new ArrayList<>();
        // Timeout disabled: the backstop needs Paper's entity scheduler, which MockBukkit
        // does not implement, and every case here is about the action path.
        gate = new PlayerActionGateListener(plugin, (player, type) -> released.add(player.getName()), () -> 0);
        server.getPluginManager().registerEvents(gate, plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Fires a move of one block, optionally pre-cancelled as a login plugin would. */
    private void simulateMove(PlayerMock player, boolean cancelled) {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 1, 64, 0);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        event.setCancelled(cancelled);
        server.getPluginManager().callEvent(event);
    }

    @Test
    @DisplayName("a cancelled move leaves the player gated")
    void cancelledMoveDoesNotRelease() {
        PlayerMock player = server.addPlayer("Gated");
        gate.markPending(player, "JAVA");

        simulateMove(player, true);

        assertTrue(gate.isPending(player.getUniqueId()), "player should still be waiting");
        assertEquals(List.of(), released, "no allocation should have been triggered");
    }

    @Test
    @DisplayName("an uncancelled move releases the player")
    void uncancelledMoveReleases() {
        PlayerMock player = server.addPlayer("Freed");
        gate.markPending(player, "JAVA");

        simulateMove(player, false);

        assertFalse(gate.isPending(player.getUniqueId()));
        assertEquals(List.of("Freed"), released);
    }

    @Test
    @DisplayName("limbo then login: cancelled moves gate, the first uncancelled one allocates")
    void releasesOnlyAfterLimboEnds() {
        PlayerMock player = server.addPlayer("LoggingIn");
        gate.markPending(player, "JAVA");

        for (int i = 0; i < 20; i++) {
            simulateMove(player, true);
        }
        assertEquals(List.of(), released, "still in limbo");

        simulateMove(player, false);
        assertEquals(List.of("LoggingIn"), released, "allocated once authenticated");
    }

    @Test
    @DisplayName("head rotation alone does not release")
    void rotationDoesNotRelease() {
        PlayerMock player = server.addPlayer("LookingAround");
        gate.markPending(player, "JAVA");

        // Same block, different yaw: what a limbo implementation typically still permits.
        Location from = new Location(world, 0.5, 64, 0.5, 0f, 0f);
        Location to = new Location(world, 0.6, 64, 0.6, 90f, 0f);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));

        assertTrue(gate.isPending(player.getUniqueId()));
        assertEquals(List.of(), released);
    }

    @Test
    @DisplayName("release happens once, however many actions follow")
    void releasesAtMostOnce() {
        PlayerMock player = server.addPlayer("Busy");
        gate.markPending(player, "JAVA");

        simulateMove(player, false);
        simulateMove(player, false);
        simulateMove(player, false);

        assertEquals(List.of("Busy"), released, "allocation must not be triggered twice");
    }

    @Test
    @DisplayName("an ungated player's movement triggers nothing")
    void unmarkedPlayerIsIgnored() {
        PlayerMock player = server.addPlayer("Established");

        simulateMove(player, false);

        assertEquals(List.of(), released);
    }

    @Test
    @DisplayName("quitting while gated drops the player without allocating")
    void quitClearsPending() {
        PlayerMock player = server.addPlayer("LeftEarly");
        gate.markPending(player, "JAVA");

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, "left"));

        assertFalse(gate.isPending(player.getUniqueId()));
        assertEquals(List.of(), released);
    }

    @Test
    @DisplayName("gated players are tracked independently")
    void playersAreIndependent() {
        PlayerMock first = server.addPlayer("First");
        PlayerMock second = server.addPlayer("Second");
        gate.markPending(first, "JAVA");
        gate.markPending(second, "JAVA");

        simulateMove(first, false);

        assertEquals(List.of("First"), released);
        assertTrue(gate.isPending(second.getUniqueId()), "the other player stays gated");
    }
}
