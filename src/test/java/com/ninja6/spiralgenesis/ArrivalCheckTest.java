package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCell;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one re-check of a new plot, about five seconds after the player first reaches it.
 *
 * <p>Scheduled on the player's own scheduler when the teleport onto the plot completes, and
 * nothing else: no task for a teleport that did not complete, and none after it. When it
 * runs, a player still at the plot has it re-checked through the manager, which reads it on
 * the thread owning its chunk, and an unsafe one is moved by the in-cell repair.
 */
class ArrivalCheckTest {

    private ServerMock server;
    private WorldMock world;
    private SpiralGenesisPlugin plugin;
    private CheckingManager manager;

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
            // As in RepairRespawnPointTest: the storage flush task cannot be cancelled here.
            MockBukkit.unmock();
        }
    }

    /**
     * A manager whose re-check answers with a fixed verdict and whose in-cell search answers
     * with a fixed replacement, recording what each was asked.
     */
    private static final class CheckingManager extends SpawnManager {

        private final Location replacement;
        private boolean safe;
        private final List<Location> revalidated = new ArrayList<>();
        private int searches;

        private CheckingManager(JavaPlugin plugin, World world, PluginConfig config,
                                Location replacement) {
            super(plugin, world, config);
            this.replacement = replacement;
        }

        @Override
        public CompletableFuture<Boolean> revalidate(Location stored) {
            revalidated.add(stored.clone());
            return CompletableFuture.completedFuture(safe);
        }

        @Override
        public CompletableFuture<LocationResult> findSafeSpawnInCell(SpiralCell cell, UUID owner,
                                                                     Location failed) {
            searches++;
            return CompletableFuture.completedFuture(new LocationResult(
                    replacement, cell.index(), 0, 0, 63, 1, 1, false, Map.of()));
        }
    }

    /** Replaces the bound manager, as RepairRespawnPointTest does. */
    private static void bind(SpiralGenesisPlugin plugin, SpawnManager manager) {
        try {
            Field field = SpiralGenesisPlugin.class.getDeclaredField("spawnManager");
            field.setAccessible(true);
            field.set(plugin, manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Location plot() {
        return new Location(world, 10.5, 64, 10.5);
    }

    private Location repaired() {
        return new Location(world, 44.5, 66, 44.5);
    }

    private void load() {
        plugin = MockBukkit.load(SpiralGenesisPlugin.class);
        manager = new CheckingManager(plugin, world, plugin.getPluginConfig(), repaired());
        bind(plugin, manager);
    }

    /** A player recorded at the plot, standing on it, with the plot as their respawn point. */
    private InlinePlayerMock arrived() {
        InlinePlayerMock player = new InlinePlayerMock(server, "Newcomer");
        server.addPlayer(player);
        assertTrue(plugin.getDataStorage().setSpawn(player.getUniqueId(), plot(), 3, 0, 0,
                "Newcomer", "JAVA"));
        player.setRespawnLocation(plot(), true);
        player.teleport(plot());
        return player;
    }

    private static void assertSameBlock(Location expected, Location actual) {
        assertEquals(expected.getWorld(), actual.getWorld());
        assertEquals(expected.getBlockX(), actual.getBlockX());
        assertEquals(expected.getBlockY(), actual.getBlockY());
        assertEquals(expected.getBlockZ(), actual.getBlockZ());
    }

    private Location recorded(InlinePlayerMock player) {
        return plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation();
    }

    @Test
    @DisplayName("The re-check waits five seconds, and runs once")
    void theCheckIsDelayedAndRunsOnce() {
        load();
        InlinePlayerMock player = arrived();
        int before = player.delayedTasks();

        plugin.scheduleArrivalCheck(player, plot());

        assertEquals(before + 1, player.delayedTasks(), "one task for the arrival");
        assertEquals(100L, player.delays.get(player.delays.size() - 1),
                "5 seconds at 20 ticks a second");
        assertTrue(manager.revalidated.isEmpty(), "nothing is read before the delay passes");

        player.runDelayed();
        player.runDelayed();

        assertEquals(1, manager.revalidated.size(), "one re-check, and nothing after it");
        assertSameBlock(plot(), manager.revalidated.get(0));
    }

    @Test
    @DisplayName("A plot still safe five seconds on is left alone")
    void aSafePlotIsKept() {
        load();
        manager.safe = true;
        InlinePlayerMock player = arrived();

        plugin.scheduleArrivalCheck(player, plot());
        player.runDelayed();

        assertEquals(1, manager.revalidated.size());
        assertEquals(0, manager.searches, "nothing to repair");
        assertSameBlock(plot(), recorded(player));
        assertSameBlock(plot(), player.getLocation());
    }

    @Test
    @DisplayName("A player still on a plot that has become unsafe is moved by the in-cell repair")
    void anUnsafePlotIsRepairedWithThePlayerOnIt() {
        load();
        InlinePlayerMock player = arrived();

        plugin.scheduleArrivalCheck(player, plot());
        player.runDelayed();

        assertEquals(1, manager.searches, "the in-cell repair searched the cell");
        assertSameBlock(repaired(), recorded(player));
        assertSameBlock(repaired(), player.respawnPoint);
        assertSameBlock(repaired(), player.getLocation());
    }

    @Test
    @DisplayName("A player carried a block or two off the point by flowing water still counts as at it")
    void aPlayerNearThePointIsStillAtIt() {
        load();
        InlinePlayerMock player = arrived();
        player.teleport(plot().add(2, -1, -2));

        plugin.scheduleArrivalCheck(player, plot());
        player.runDelayed();

        assertSameBlock(repaired(), player.getLocation());
    }

    @Test
    @DisplayName("A player who has walked away is not re-checked or moved")
    void aPlayerWhoWalkedAwayIsLeftAlone() {
        load();
        InlinePlayerMock player = arrived();
        Location away = plot().add(20, 0, 0);
        player.teleport(away);

        plugin.scheduleArrivalCheck(player, plot());
        player.runDelayed();

        assertTrue(manager.revalidated.isEmpty(), "the death re-check still covers the plot");
        assertSameBlock(plot(), recorded(player));
        assertSameBlock(away, player.getLocation());
    }

    @Test
    @DisplayName("A plot changed since the teleport is not re-checked on the old one's behalf")
    void aChangedPlotIsLeftAlone() {
        load();
        InlinePlayerMock player = arrived();
        plugin.scheduleArrivalCheck(player, plot());
        Location moved = new Location(world, 80.5, 64, 80.5);
        assertTrue(plugin.getDataStorage().setSpawn(player.getUniqueId(), moved, 3, 0, 0,
                "Newcomer", "JAVA"));

        player.runDelayed();

        assertTrue(manager.revalidated.isEmpty());
        assertSameBlock(moved, recorded(player));
    }

    @Test
    @DisplayName("A first allocation schedules the re-check once its teleport completes, and not otherwise")
    void allocationSchedulesTheCheckOnlyForACompletedTeleport() {
        ArrivalHintsTest.HintPlugin hints = MockBukkit.loadWith(ArrivalHintsTest.HintPlugin.class,
                getClass().getResourceAsStream("/plugin.yml"));
        hints.getConfig().set("allocation.action-timeout-seconds", 0);
        hints.saveConfig();
        hints.reload();

        InlinePlayerMock arriving = new InlinePlayerMock(server, "Arriving");
        server.addPlayer(arriving);
        int before = arriving.delayedTasks();
        hints.allocateNow(arriving, "JAVA");
        assertEquals(before + 1, arriving.delayedTasks(), "one re-check for the new plot");

        InlinePlayerMock held = new InlinePlayerMock(server, "Held");
        server.addPlayer(held);
        held.teleportSucceeds = false;
        int heldBefore = held.delayedTasks();
        hints.allocateNow(held, "JAVA");
        assertEquals(heldBefore, held.delayedTasks(),
                "a player who never reached the plot has nothing to re-check yet");
    }
}
