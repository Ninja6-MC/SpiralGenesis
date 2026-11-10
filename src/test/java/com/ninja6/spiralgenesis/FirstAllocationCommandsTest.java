package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class FirstAllocationCommandsTest {
    private ServerMock server;
    private WorldMock world;
    private CommandsPlugin plugin;
    private final List<LogRecord> logs = new ArrayList<>();

    public static class CommandsPlugin extends ArrivalHintsTest.HintPlugin {
        final List<String> dispatched = new ArrayList<>();
        boolean succeeds = true;
        boolean throwsOnDispatch;
        boolean onPlayerThread;
        @Override boolean runForPlayer(Player player, Runnable action, Runnable retired) {
            boolean previous = onPlayerThread;
            onPlayerThread = true;
            try { action.run(); }
            finally { onPlayerThread = previous; }
            return true;
        }
        @Override boolean dispatchAllocationCommand(String command) {
            assertTrue(onPlayerThread, "dispatch must run through the player scheduler");
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(
                    getDataFolder().toPath().resolve("data.yml").toFile());
            assertTrue(disk.getConfigurationSection("players").getKeys(false).stream()
                    .anyMatch(id -> disk.getBoolean("players." + id
                            + ".first-allocation-commands-run")), "marker is durable before dispatch");
            dispatched.add(command);
            if (throwsOnDispatch) {
                throw new IllegalStateException("Command unavailable");
            }
            return succeeds;
        }
    }

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.loadWith(CommandsPlugin.class,
                getClass().getResourceAsStream("/plugin.yml"));
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        configure(List.of("kit starter {player}", "record {uuid}"));
    }

    private void configure(List<String> commands) {
        plugin.getConfig().set("allocation.action-timeout-seconds", 0);
        plugin.getConfig().set("allocation.first-allocation-commands", commands);
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

    @Test void substitutesPlaceholdersAndRunsOnlyOnceAcrossRestart() {
        InlinePlayerMock player = join("Newcomer");
        plugin.allocateNow(player, "JAVA");
        assertEquals(List.of("kit starter Newcomer", "record " + player.getUniqueId()),
                plugin.dispatched);
        plugin.allocateNow(player, "JAVA");
        server.executeConsole("sgen", "reassign", "Newcomer").assertSucceeded();
        plugin.getDataStorage().save();
        YamlDataStorage restarted = new YamlDataStorage(plugin);
        restarted.load();
        assertFalse(restarted.beginFirstAllocationCommands(player.getUniqueId()));
        assertEquals(2, plugin.dispatched.size());
        assertEquals(1, logs.stream().filter(r -> r.getMessage().equals(
                "First-allocation commands for Newcomer: dispatched 2 command(s).")).count());
    }

    @Test void emptyListDoesNothingAndReloadPicksUpCommands() {
        configure(List.of());
        InlinePlayerMock empty = join("Empty");
        plugin.allocateNow(empty, "JAVA");
        assertTrue(plugin.dispatched.isEmpty());
        plugin.getDataStorage().save();
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(
                plugin.getDataFolder().toPath().resolve("data.yml").toFile());
        assertFalse(disk.contains("players." + empty.getUniqueId()
                + ".first-allocation-commands-run"));
        configure(List.of("kit {player}"));
        plugin.allocateNow(join("Configured"), "JAVA");
        assertEquals(List.of("kit Configured"), plugin.dispatched);
    }

    @Test void waitsForAsynchronousTeleportCompletionOnThePlayerScheduler() {
        CompletableFuture<Boolean> arrival = new CompletableFuture<>();
        InlinePlayerMock player = new InlinePlayerMock(server, "Asynchronous") {
            @Override public CompletableFuture<Boolean> teleportAsync(Location at,
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause,
                    io.papermc.paper.entity.TeleportFlag... flags) {
                return arrival;
            }
        };
        server.addPlayer(player);
        plugin.allocateNow(player, "JAVA");
        assertTrue(plugin.dispatched.isEmpty());
        assertFalse(plugin.onPlayerThread);
        player.teleport(plugin.getDataStorage().getSpawn(player.getUniqueId()));
        arrival.complete(true);
        assertEquals(2, plugin.dispatched.size());
    }

    @Test void refusedTeleportWaitsForSuccessfulRetry() {
        InlinePlayerMock player = join("Held");
        player.teleportSucceeds = false;
        plugin.allocateNow(player, "JAVA");
        assertTrue(plugin.dispatched.isEmpty());
        player.teleportSucceeds = true;
        server.getPluginManager().callEvent(new PlayerMoveEvent(player,
                new Location(world, 0, 64, 0), new Location(world, 1, 64, 0)));
        assertEquals(2, plugin.dispatched.size());
        plugin.allocateNow(player, "JAVA");
        assertEquals(2, plugin.dispatched.size());
    }

    @Test void returningAndReassignDoNotRunCommands() {
        InlinePlayerMock returning = join("Returning");
        assertTrue(plugin.getDataStorage().setSpawn(returning.getUniqueId(),
                new Location(world, 40.5, 64, 40.5), 1, 0, 0, "Returning", "JAVA", true));
        plugin.allocateNow(returning, "JAVA");
        assertTrue(plugin.dispatched.isEmpty());
        server.executeConsole("sgen", "reassign", "Returning").assertSucceeded();
        assertTrue(plugin.dispatched.isEmpty());
    }

    @Test void arrivalRecheckAndRepairDoNotRunCommands() throws Exception {
        InlinePlayerMock player = join("Repair");
        Location plot = new Location(world, 10.5, 64, 10.5);
        Location repaired = new Location(world, 44.5, 66, 44.5);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), plot, 3, 0, 0, "Repair", "JAVA");
        player.teleport(plot);
        player.setRespawnLocation(plot, true);
        SpawnManager manager = new SpawnManager(plugin, world, plugin.getPluginConfig()) {
            @Override public CompletableFuture<Boolean> revalidate(Location stored) {
                return CompletableFuture.completedFuture(false);
            }
            @Override public CompletableFuture<LocationResult> findSafeSpawnInCell(
                    SpiralCell cell, UUID owner, Location failed) {
                return CompletableFuture.completedFuture(new LocationResult(
                        repaired, cell.index(), 0, 0, 65, 1, 1, false, Map.of()));
            }
        };
        Field field = SpiralGenesisPlugin.class.getDeclaredField("spawnManager");
        field.setAccessible(true);
        field.set(plugin, manager);
        plugin.scheduleArrivalCheck(player, plot);
        player.runDelayed();
        assertEquals(repaired, plugin.getDataStorage().getSpawn(player.getUniqueId()));
        plugin.repairSpawn(player, plugin.getDataStorage().getRecord(player.getUniqueId()), false);
        assertTrue(plugin.dispatched.isEmpty());
    }

    @Test void eachFailedCommandWarnsAndExceptionsDoNotRetry() {
        plugin.succeeds = false;
        plugin.allocateNow(join("Failed"), "JAVA");
        assertEquals(2, logs.stream().filter(r -> r.getMessage()
                .startsWith("First-allocation command failed for Failed:")).count());
        plugin.throwsOnDispatch = true;
        InlinePlayerMock throwing = join("Throwing");
        plugin.allocateNow(throwing, "JAVA");
        plugin.allocateNow(throwing, "JAVA");
        assertEquals(4, plugin.dispatched.size());
        assertEquals(2, logs.stream().filter(r -> r.getMessage()
                .startsWith("First-allocation command failed for Throwing:")).count());
    }

    @Test void unfinishedDurableBatchWarnsAfterRestartAndNeverRunsAgain() {
        InlinePlayerMock player = join("Interrupted");
        plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 10, 64, 10),
                1, 0, 0, "Interrupted", "JAVA");
        assertTrue(plugin.getDataStorage().beginFirstAllocationCommands(player.getUniqueId()));
        YamlDataStorage restarted = new YamlDataStorage(plugin);
        restarted.load();
        assertFalse(restarted.beginFirstAllocationCommands(player.getUniqueId()));
        assertTrue(logs.stream().anyMatch(r -> r.getMessage()
                .startsWith("First-allocation commands for Interrupted")
                && r.getMessage().contains("give the kit by hand")));
    }

    @Test void failedMarkerWriteDispatchesNothing() throws Exception {
        Files.createDirectory(plugin.getDataFolder().toPath().resolve("data.yml.tmp"));
        plugin.allocateNow(join("Unwritable"), "JAVA");
        assertTrue(plugin.dispatched.isEmpty());
        assertTrue(logs.stream().anyMatch(r -> r.getMessage()
                .startsWith("First-allocation commands for Unwritable")
                && r.getMessage().contains("could not be saved")));
        Files.deleteIfExists(plugin.getDataFolder().toPath().resolve("data.yml.tmp"));
    }
}
