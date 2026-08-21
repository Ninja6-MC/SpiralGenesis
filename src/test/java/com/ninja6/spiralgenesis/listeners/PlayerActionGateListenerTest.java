package com.ninja6.spiralgenesis.listeners;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
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

    /**
     * Reproduces how AuthMe actually holds a player, which is not by cancelling.
     *
     * <p>Verified against {@code fr.xephi.authme.listener.PlayerListener} in the 5.6.0
     * artifact this project compiles against: {@code onPlayerMove} is annotated
     * {@code HIGHEST} and pins the player with {@code event.setTo(event.getFrom())}. It
     * never calls {@code setCancelled}. {@code ignoreCancelled} therefore does nothing for
     * movement, and the only thing standing between a held player and a burned spiral index
     * is the gate comparing the destination it is finally handed against the origin.
     */
    private static final class PinningLimbo implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onMove(PlayerMoveEvent event) {
            event.setTo(event.getFrom());
        }
    }

    @Test
    @DisplayName("a move pinned with setTo, as AuthMe does it, leaves the player gated")
    void pinnedMoveDoesNotRelease() {
        server.getPluginManager().registerEvents(new PinningLimbo(), plugin);
        PlayerMock player = server.addPlayer("Pinned");
        gate.markPending(player, "JAVA");

        // Uncancelled throughout: the destination is rewritten, not the cancel flag.
        simulateMove(player, false);

        assertTrue(gate.isPending(player.getUniqueId()),
                "a player pinned in place has not proven anything");
        assertEquals(List.of(), released);
    }

    /** A block interaction, the only interact shape that can arrive uncancelled. */
    private PlayerInteractEvent blockInteract(PlayerMock player) {
        Block block = world.getBlockAt(0, 63, 0);
        block.setType(Material.STONE);
        return new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, null, block, BlockFace.UP);
    }

    /**
     * Reproduces OpeNLogin's limbo, which pins like AuthMe but deliberately lets a
     * descending player through.
     *
     * <p>From {@code PlayerGeneralListeners.onPlayerMove}: it returns early when
     * {@code from.getY() > to.getY()}, so gravity is not fought. An unauthenticated player
     * who spawns in air therefore emits real, uncancelled, block-changing move events.
     */
    private static final class FallThroughLimbo implements Listener {
        private boolean holding = true;

        @EventHandler(priority = EventPriority.HIGH)
        public void onMove(PlayerMoveEvent event) {
            if (!holding) {
                return; // Authenticated: the limbo stops interfering entirely.
            }
            if (event.getFrom().getY() > event.getTo().getY()) {
                return; // Descending: left alone, exactly as OpeNLogin leaves it.
            }
            event.setTo(event.getFrom());
        }
    }

    @Test
    @DisplayName("falling through a limbo that permits it does not release the player")
    void fallingDoesNotRelease() {
        server.getPluginManager().registerEvents(new FallThroughLimbo(), plugin);
        PlayerMock player = server.addPlayer("Falling");
        gate.markPending(player, "JAVA");

        // Straight down, several blocks: uncancelled, unpinned, and a real block change.
        for (int y = 80; y > 70; y--) {
            server.getPluginManager().callEvent(new PlayerMoveEvent(player,
                    new Location(world, 0.5, y, 0.5), new Location(world, 0.5, y - 1, 0.5)));
        }

        assertTrue(gate.isPending(player.getUniqueId()),
                "gravity is not evidence that anyone authenticated");
        assertEquals(List.of(), released);
    }

    @Test
    @DisplayName("walking out of that same limbo releases once it lets go")
    void walkingReleasesOnceLimboReleases() {
        FallThroughLimbo limbo = new FallThroughLimbo();
        server.getPluginManager().registerEvents(limbo, plugin);
        PlayerMock player = server.addPlayer("Walking");
        gate.markPending(player, "JAVA");

        // Level ground while held: the limbo pins it, so the gate must stay shut.
        simulateMove(player, false);
        assertEquals(List.of(), released, "still held while the limbo is pinning");

        // The player authenticates and the limbo stops interfering.
        limbo.holding = false;
        simulateMove(player, false);

        assertEquals(List.of("Walking"), released, "the same move now proves they are free");
    }

    @Test
    @DisplayName("an uncancelled block interaction releases the player")
    void uncancelledInteractReleases() {
        PlayerMock player = server.addPlayer("Clicker");
        gate.markPending(player, "JAVA");

        server.getPluginManager().callEvent(blockInteract(player));

        assertEquals(List.of("Clicker"), released);
    }

    @Test
    @DisplayName("a cancelled block interaction leaves the player gated")
    void cancelledInteractDoesNotRelease() {
        PlayerMock player = server.addPlayer("Blocked");
        gate.markPending(player, "JAVA");

        PlayerInteractEvent event = blockInteract(player);
        event.setCancelled(true);
        server.getPluginManager().callEvent(event);

        assertTrue(gate.isPending(player.getUniqueId()));
        assertEquals(List.of(), released);
    }

    @Test
    @DisplayName("clicking air never releases, because Bukkit reports it as cancelled")
    void airInteractCannotRelease() {
        PlayerMock player = server.addPlayer("Waving");
        gate.markPending(player, "JAVA");

        // PlayerInteractEvent sets useInteractedBlock to DENY whenever there is no block,
        // and isCancelled() reads that field, so an air click is indistinguishable from one
        // a plugin vetoed. Recorded as a test because it bounds what the interact trigger
        // can do: only block interactions can ever open the gate.
        server.getPluginManager().callEvent(new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_AIR, null, null, null));

        assertTrue(gate.isPending(player.getUniqueId()));
        assertEquals(List.of(), released);
    }

    @Test
    @DisplayName("a player allocated externally is dropped without a second release")
    void forgetPreventsLaterRelease() {
        PlayerMock player = server.addPlayer("Manual");
        gate.markPending(player, "JAVA");

        // What /sgen allocate does: it allocates the player itself, then tells the gate to
        // stop watching, so their next step does not trigger a redundant second attempt.
        gate.forget(player.getUniqueId());
        simulateMove(player, false);

        assertFalse(gate.isPending(player.getUniqueId()));
        assertEquals(List.of(), released, "the gate must not fire for an already-handled player");
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
