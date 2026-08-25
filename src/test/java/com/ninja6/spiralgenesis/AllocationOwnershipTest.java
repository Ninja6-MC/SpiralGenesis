package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import io.papermc.paper.entity.TeleportFlag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns an allocation, and what that ownership costs the action gate.
 *
 * <p>The pairing under test is the one in {@code takeAllocation}: a caller that claims a
 * player must also stop the gate watching them, and a caller that bails out beforehand must
 * leave them where they were. Getting either half wrong is expensive and silent - a spiral
 * index is never reclaimed, so a second allocation permanently burns one, while a player
 * dropped from the gate without being allocated is never allocated at all.
 *
 * <p>Driving {@code handlePlayerFirstJoin} at all needs the seams this suite overrides:
 * MockBukkit implements neither Paper's entity scheduler (it throws) nor async chunk
 * loading, exactly as {@code SpawnManagerTest.InlineSpawnManager} found. Nothing about the
 * ownership logic itself is stubbed.
 *
 * <p>Callers arrive through {@code allocateNow} rather than through the gate, because
 * {@code PlayerActionGateListener.release} drops a player before invoking the callback: a
 * gate-driven caller is already un-gated on entry and could not show the difference. The
 * command and login-adapter paths are the ones that arrive with the player still held.
 */
class AllocationOwnershipTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        try {
            MockBukkit.unmock();
        } catch (UnimplementedOperationException e) {
            // Disabling the plugin cancels the storage flush task, and MockBukkit cannot
            // cancel a Paper scheduled task. Swallowed deliberately: this exception extends
            // TestAbortedException, so letting it out of teardown would report every test
            // in this class as skipped rather than run - which is precisely how a suite
            // stops being able to go red without anyone noticing.
            //
            // The failed pass got as far as disabling the plugins, and stopped short of
            // clearing the server instance, so a second pass is needed to leave the next
            // test something to mock.
            MockBukkit.unmock();
        }
    }

    /**
     * Loads a plugin variant with the gate's backstop disabled.
     *
     * <p>Arming it would schedule on the player's own scheduler, which MockBukkit does not
     * implement; every case here is about the caller path in any event.
     */
    private <T extends SpiralGenesisPlugin> T load(Class<T> type) {
        // loadWith, not load: MockBukkit looks for plugin.yml beside the class it is given,
        // and these variants compile into the test tree rather than next to the resource.
        T plugin = MockBukkit.loadWith(type, getClass().getResourceAsStream("/plugin.yml"));
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("allocation.action-timeout-seconds", 0);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        plugin.reload();
        return plugin;
    }

    /**
     * Joins a player the way the server does, so the gate marks them pending on the way in.
     *
     * <p>The teleport override is a mock defect, not a seam: MockBukkit's
     * {@code teleportAsync} returns {@code null} instead of a future, and the resulting
     * NullPointerException is swallowed by allocation's own error handling - which would
     * leave these tests passing on a path that never finished.
     */
    private PlayerMock join(String name) {
        PlayerMock player = new PlayerMock(server, name) {
            @Override
            public CompletableFuture<Boolean> teleportAsync(Location location,
                                                            PlayerTeleportEvent.TeleportCause cause,
                                                            TeleportFlag... flags) {
                return CompletableFuture.completedFuture(teleport(location, cause));
            }
        };
        server.addPlayer(player);
        return player;
    }

    /** One uncancelled step, which is what opens the gate for a player still held by it. */
    private void move(PlayerMock player) {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 1, 64, 0);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));
    }

    /**
     * Resolves the two asynchronous seams immediately, and counts every entry into
     * allocation so a redundant one is visible.
     */
    public static class InlinePlugin extends SpiralGenesisPlugin {

        /** Entries into allocation, whichever caller made them. */
        final AtomicInteger allocationCalls = new AtomicInteger();

        /** Runs with an allocation in flight, standing in for a competing caller. */
        Consumer<InlinePlugin> whileAllocating = plugin -> { };

        @Override
        public void handlePlayerFirstJoin(Player player, String clientType) {
            allocationCalls.incrementAndGet();
            super.handlePlayerFirstJoin(player, clientType);
        }

        @Override
        CompletableFuture<SpawnManager.LocationResult> allocateSpawn(IntSupplier indexSupplier) {
            // Consumes an index exactly as a real scan does, so the supplier itself records
            // how many were burned.
            int index = indexSupplier.getAsInt();
            whileAllocating.accept(this);
            Location where = new Location(Bukkit.getWorlds().get(0), index * 16, 64, 0);
            return CompletableFuture.completedFuture(new SpawnManager.LocationResult(
                    where, index, 0, 0, 63, 1, 1, false, Map.of()));
        }

        @Override
        boolean runForPlayer(Player player, Runnable action, Runnable retired) {
            action.run();
            return true;
        }
    }

    /** Reproduces the world-unavailable bail-out, which a loaded world otherwise hides. */
    public static class NoSpawnManagerPlugin extends InlinePlugin {
        @Override
        void initSpawnManager() {
            // Deliberately empty.
        }
    }

    @Test
    @DisplayName("a caller that takes ownership drops the player from the gate")
    void ownershipForgetsFromGate() {
        InlinePlugin plugin = load(InlinePlugin.class);
        PlayerMock player = join("Owner");
        assertEquals(0, plugin.allocationCalls.get(), "joining should only have gated them");

        plugin.allocateNow(player, "JAVA");

        assertTrue(plugin.getDataStorage().hasSpawn(player.getUniqueId()), "should be allocated");
        assertEquals(1, plugin.allocationCalls.get());

        move(player);

        assertEquals(1, plugin.allocationCalls.get(),
                "the gate must not fire for a player another caller has allocated");
    }

    @Test
    @DisplayName("a caller that bails out before taking ownership leaves them gated")
    void bailOutDoesNotStrandPlayer() {
        InlinePlugin plugin = load(NoSpawnManagerPlugin.class);
        PlayerMock player = join("Held");

        plugin.allocateNow(player, "JAVA");

        assertFalse(plugin.getDataStorage().hasSpawn(player.getUniqueId()), "nothing to allocate with");
        assertEquals(1, plugin.allocationCalls.get());

        move(player);

        assertEquals(2, plugin.allocationCalls.get(),
                "a player nobody took ownership of must still be watched by the gate");
    }

    @Test
    @DisplayName("a second caller arriving mid-allocation reserves no second index")
    void racingCallersReserveOneIndex() {
        InlinePlugin plugin = load(InlinePlugin.class);
        PlayerMock player = join("Racer");
        int before = plugin.getDataStorage().getCurrentIndex();

        // AuthMe fires RegisterEvent and LoginEvent for a single fresh registration, so the
        // second caller can arrive while the first scan is still running and storage still
        // reports the player unassigned.
        plugin.whileAllocating = reentrant -> {
            // Fires once: a second caller, not a cascade, and without this a regression in
            // the in-flight guard would recurse instead of reporting the extra index.
            reentrant.whileAllocating = ignored -> { };
            reentrant.allocateNow(player, "JAVA");
        };

        plugin.allocateNow(player, "JAVA");

        assertEquals(2, plugin.allocationCalls.get(), "both callers should have been let in");
        assertEquals(before + 1, plugin.getDataStorage().getCurrentIndex(),
                "a second index would be permanently burned");
        assertTrue(plugin.getDataStorage().hasSpawn(player.getUniqueId()));
    }
}
