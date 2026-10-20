package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.ClaimOwnership;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.CellReserver;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.protection.ClaimInspection;
import com.ninja6.spiralgenesis.protection.ProtectionProvider;
import com.ninja6.spiralgenesis.protection.RecordingProvider;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arrival hint: which arrivals start it, how long it stays, what ends it, and that the
 * claim text, tip and edges follow only from an inspected claim of the player's.
 */
class ArrivalHintsTest {
    private ServerMock server;
    private WorldMock world;
    private HintPlugin plugin;

    /** Allocates and reassigns without a chunk, and records what the hint asks for. */
    private static final class StubSpawnManager extends SpawnManager {
        private final PluginConfig config;

        StubSpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
            this.config = config;
        }

        @Override
        public CompletableFuture<AllocationOutcome> allocateNextSafeSpawn(CellReserver cells) {
            SpiralCell cell = cells.reserve(config);
            Location at = new Location(Bukkit.getWorlds().get(0), cell.centreX() + 0.5, 64,
                    cell.centreZ() + 0.5);
            return CompletableFuture.completedFuture(new LocationResult(at, cell.index(),
                    cell.grid()[0], cell.grid()[1], 63, 1, 1, false, Map.of(),
                    cell.centre().id()));
        }

        @Override
        public SpawnVerdict verifyStoredSpawn(Location stored) {
            return SpawnVerdict.USABLE;
        }

        @Override
        public Location standingPointNow(Location stored) {
            return stored.clone();
        }
    }

    public static class HintPlugin extends SpiralGenesisPlugin {
        ClaimInspection inspection = ClaimInspection.unavailable();
        String tool = "stick";
        final List<String> edges = new ArrayList<>();
        final ProtectionProvider provider = new RecordingProvider();
        StubSpawnManager stub;

        @Override public ProtectionProvider getProtectionProvider() { return provider; }
        @Override public SpawnManager getSpawnManager() {
            if (stub == null) {
                stub = new StubSpawnManager(this, Bukkit.getWorlds().get(0), getPluginConfig());
            }
            return stub;
        }
        @Override boolean runForPlayer(Player player, Runnable action, Runnable retired) {
            action.run();
            return true;
        }
        @Override CompletableFuture<SpawnManager.AllocationOutcome> allocateSpawn(CellReserver cells) {
            return getSpawnManager().allocateNextSafeSpawn(cells);
        }
        @Override public void inspectPlot(StoredSpawn record, UUID owner, Consumer<ClaimInspection> reply) {
            reply.accept(inspection);
        }
        @Override public void showClaimEdges(StoredSpawn record, Player player) {
            edges.add(player.getName());
        }
        @Override public String investigationTool() { return tool; }
    }

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.loadWith(HintPlugin.class, getClass().getResourceAsStream("/plugin.yml"));
        plugin.getConfig().set("allocation.action-timeout-seconds", 0);
        plugin.saveConfig();
        plugin.reload();
    }

    @AfterEach void tearDown() {
        try { MockBukkit.unmock(); }
        catch (UnimplementedOperationException e) { MockBukkit.unmock(); }
    }

    private InlinePlayerMock join(String name) {
        InlinePlayerMock player = new InlinePlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    /** A player recorded at a plot and standing on it, with no hint running yet. */
    private InlinePlayerMock onPlot(String name, Location plot) {
        InlinePlayerMock player = join(name);
        assertTrue(plugin.getDataStorage().setSpawn(player.getUniqueId(), plot, 1, 0, 0, name, "JAVA"));
        player.setRespawnLocation(plot, true);
        player.teleport(plot);
        return player;
    }

    private static ClaimInspection claim() {
        return new ClaimInspection(ClaimInspection.Status.FOUND, -15, -25, 15, 5,
                ClaimOwnership.ADMIN_CLAIM, false, true, false);
    }

    private static String last(InlinePlayerMock player) {
        return player.actionBars.isEmpty() ? "" : player.actionBars.get(player.actionBars.size() - 1);
    }

    /** Moves the player within or out of their column without firing anything. */
    private static void stand(InlinePlayerMock player, double x, double y, double z, float yaw) {
        Location at = player.getLocation();
        player.teleport(new Location(at.getWorld(), x, y, z, yaw, 30));
    }

    @Test void firstAllocationStartsTheHint() {
        InlinePlayerMock player = join("Newcomer");
        plugin.allocateNow(player, "JAVA");
        assertTrue(plugin.arrivalHints().isRunning(player.getUniqueId()));
        Location plot = plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation();
        assertTrue(last(player).startsWith("Your plot " + plot.getBlockX() + ", 64, "), last(player));
        assertEquals(1, player.repeatingTasks());
    }

    @Test void placementOwedAfterDisconnectStartsTheHint() {
        InlinePlayerMock player = join("Returning");
        assertTrue(plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 40.5, 64, 40.5),
                1, 0, 0, "Returning", "JAVA", true));
        plugin.allocateNow(player, "JAVA");
        assertTrue(last(player).contains("40, 64, 40"), String.valueOf(player.actionBars));
    }

    @Test void reassignmentStartsTheHintOnlyOnceTheTeleportCompletes() {
        InlinePlayerMock player = onPlot("Moved", new Location(world, 300.5, 64, 300.5));
        server.executeConsole("sgen", "reassign", "Moved").assertSucceeded();
        Location plot = plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation();
        assertTrue(last(player).contains(plot.getBlockX() + ", 64, " + plot.getBlockZ()),
                String.valueOf(player.actionBars));

        InlinePlayerMock held = onPlot("Held", new Location(world, 600.5, 64, 600.5));
        held.teleportSucceeds = false;
        server.executeConsole("sgen", "reassign", "Held").assertSucceeded();
        assertFalse(plugin.arrivalHints().isRunning(held.getUniqueId()));
        assertTrue(held.actionBars.isEmpty());
    }

    @Test void teleportRetryDoesNotStartTheHint() {
        InlinePlayerMock player = join("Limbo");
        player.teleportSucceeds = false;
        plugin.allocateNow(player, "JAVA");
        assertFalse(plugin.arrivalHints().isRunning(player.getUniqueId()));
        player.teleportSucceeds = true;
        server.getPluginManager().callEvent(new PlayerMoveEvent(player,
                new Location(world, 0, 64, 0), new Location(world, 1, 64, 0)));
        assertFalse(plugin.arrivalHints().isRunning(player.getUniqueId()));
        assertTrue(player.actionBars.isEmpty());
    }

    @Test void respawnRoutedToThePlotStartsItButABedRespawnDoesNot() {
        Location plot = new Location(world, 20.5, 64, 20.5);
        InlinePlayerMock player = onPlot("Respawner", plot);
        server.getPluginManager().callEvent(new PlayerRespawnEvent(player,
                new Location(world, 20.5, 64.1, 20.5), false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        assertTrue(plugin.arrivalHints().isRunning(player.getUniqueId()));

        InlinePlayerMock sleeper = onPlot("Sleeper", new Location(world, 80.5, 64, 80.5));
        Location bed = new Location(world, 120, 64, 120);
        sleeper.setRespawnLocation(bed, false);
        server.getPluginManager().callEvent(new PlayerRespawnEvent(sleeper,
                new Location(world, 120.5, 64.6, 120.5), true, false, PlayerRespawnEvent.RespawnReason.DEATH));
        assertFalse(plugin.arrivalHints().isRunning(sleeper.getUniqueId()));
        assertTrue(sleeper.actionBars.isEmpty());
    }

    @Test void turningAndJumpingKeepItButLeavingTheColumnLingersThenStops() {
        plugin.getConfig().set("messages.arrival.linger-seconds", 3);
        InlinePlayerMock player = onPlot("Walker", new Location(world, 10.5, 64, 10.5));
        plugin.arrivalHints().arrived(player);
        for (int second = 0; second < 10; second++) {
            stand(player, 10.2 + second % 2 * 0.5, 64 + second % 3, 10.9, second * 40f);
            player.runRepeating();
        }
        assertTrue(plugin.arrivalHints().isRunning(player.getUniqueId()));
        assertEquals(11, player.actionBars.size());

        stand(player, 11.5, 64, 10.5, 0);
        player.runRepeating();
        player.runRepeating();
        player.runRepeating();
        assertTrue(plugin.arrivalHints().isRunning(player.getUniqueId()));
        // Back in the column does not restart the wait: the first move started the linger.
        stand(player, 10.5, 64, 10.5, 0);
        player.runRepeating();
        assertFalse(plugin.arrivalHints().isRunning(player.getUniqueId()));
        assertEquals(14, player.actionBars.size());
        assertEquals(0, player.repeatingTasks());
    }

    @Test void capEndsItForAPlayerWhoNeverMoves() {
        plugin.getConfig().set("messages.arrival.max-seconds", 4);
        InlinePlayerMock player = onPlot("Idle", new Location(world, 10.5, 64, 10.5));
        plugin.arrivalHints().arrived(player);
        for (int second = 0; second < 10; second++) {
            player.runRepeating();
        }
        assertEquals(4, player.actionBars.size());
        assertFalse(plugin.arrivalHints().isRunning(player.getUniqueId()));
    }

    @Test void quitDeathAndWorldChangeEndIt() {
        InlinePlayerMock quitter = onPlot("Quitter", new Location(world, 10.5, 64, 10.5));
        plugin.arrivalHints().arrived(quitter);
        server.getPluginManager().callEvent(new PlayerQuitEvent(quitter, "left"));
        assertFalse(plugin.arrivalHints().isRunning(quitter.getUniqueId()));
        assertEquals(0, quitter.repeatingTasks());

        InlinePlayerMock dying = onPlot("Dying", new Location(world, 50.5, 64, 50.5));
        plugin.arrivalHints().arrived(dying);
        plugin.arrivalHints().onDeath(new PlayerDeathEvent(dying,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<ItemStack>(), 0, (String) null));
        assertFalse(plugin.arrivalHints().isRunning(dying.getUniqueId()));
        assertEquals(0, dying.repeatingTasks());

        InlinePlayerMock traveller = onPlot("Traveller", new Location(world, 90.5, 64, 90.5));
        plugin.arrivalHints().arrived(traveller);
        server.getPluginManager().callEvent(new PlayerChangedWorldEvent(traveller, world));
        assertFalse(plugin.arrivalHints().isRunning(traveller.getUniqueId()));
        assertEquals(0, traveller.repeatingTasks());
    }

    @Test void aSecondArrivalReplacesTheFirst() {
        InlinePlayerMock player = onPlot("Twice", new Location(world, 10.5, 64, 10.5));
        plugin.arrivalHints().arrived(player);
        plugin.arrivalHints().arrived(player);
        assertEquals(1, player.repeatingTasks());
        assertEquals(2, player.actionBars.size());
        player.runRepeating();
        assertEquals(3, player.actionBars.size());
    }

    @Test void claimTipAndEdgesOnlyWithAClaimOfTheirs() {
        InlinePlayerMock player = onPlot("Owner", new Location(world, -0.5, 64, -12.5));
        plugin.inspection = claim();
        plugin.tool = "golden hoe";
        plugin.arrivalHints().arrived(player);
        String text = last(player);
        assertTrue(text.contains("-1, 64, -13"), text);
        assertTrue(text.contains("claim 31 x 31, X -15 to 15, Z -25 to 5"), text);
        assertTrue(text.contains("right-click with a golden hoe"), text);
        assertFalse(text.contains("stick"), text);
        assertEquals(List.of("Owner"), plugin.edges);

        for (ClaimInspection other : List.of(ClaimInspection.none(), ClaimInspection.unavailable())) {
            plugin.edges.clear();
            plugin.inspection = other;
            plugin.arrivalHints().arrived(player);
            text = last(player);
            assertEquals("Your plot -1, 64, -13", text);
            assertTrue(plugin.edges.isEmpty());
        }

        plugin.inspection = claim();
        plugin.tool = null;
        plugin.arrivalHints().arrived(player);
        assertFalse(last(player).contains("right-click"), last(player));
        assertTrue(last(player).contains("claim 31 x 31"));
    }

    @Test void switchesAndTemplatesReload() {
        InlinePlayerMock player = onPlot("Configured", new Location(world, 10.5, 64, 10.5));
        plugin.inspection = claim();
        plugin.getConfig().set("messages.arrival.show-claim-edges", false);
        plugin.getConfig().set("messages.arrival-plot", "&aHome {x}/{z}");
        plugin.getConfig().set("messages.arrival-tip", "");
        plugin.getConfig().set("messages.allocation-notices", false);
        plugin.getConfig().set("messages.claim-notices", false);
        plugin.saveConfig();
        plugin.reload();
        plugin.arrivalHints().arrived(player);
        assertTrue(last(player).startsWith("Home 10/10 - claim"), last(player));
        assertFalse(last(player).contains("right-click"));
        assertTrue(plugin.edges.isEmpty());

        plugin.getConfig().set("messages.arrival.enabled", false);
        plugin.saveConfig();
        plugin.reload();
        player.actionBars.clear();
        plugin.arrivalHints().arrived(player);
        assertFalse(plugin.arrivalHints().isRunning(player.getUniqueId()));
        assertTrue(player.actionBars.isEmpty());
    }

    @Test void aMovedPlotEndsTheOldHint() {
        InlinePlayerMock player = onPlot("Shifted", new Location(world, 10.5, 64, 10.5));
        plugin.arrivalHints().arrived(player);
        assertTrue(plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 70.5, 64, 70.5),
                1, 0, 0, "Shifted", "JAVA"));
        player.runRepeating();
        assertFalse(plugin.arrivalHints().isRunning(player.getUniqueId()));
        assertEquals(1, player.actionBars.size());
    }
}
