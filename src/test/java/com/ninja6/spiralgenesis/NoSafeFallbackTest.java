package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.manager.CellReserver;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A join whose scan saw nothing a player can survive on, such as a stretch of river and
 * ocean: nothing is recorded, the player is held at world spawn, and their next join
 * allocates again.
 */
class NoSafeFallbackTest {

    private ServerMock server;
    private WorldMock world;
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

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
            // Disabling the plugin cancels the storage flush task, which MockBukkit cannot
            // cancel. Swallowed for the reason AllocationOwnershipTest documents.
            MockBukkit.unmock();
        }
    }

    /** The refused-write fixture, whose first scan finds nothing safe. */
    public static class UnsafeTerrainPlugin extends RefusedWriteTest.RefusingPlugin {

        /** Scans that resolve to {@link SpawnManager.NoSafeFallback} before one finds a plot. */
        int unsafeScans = 1;

        final AtomicInteger scans = new AtomicInteger();

        /** Each world a player was sent to the spawn of. */
        final List<World> sentToWorldSpawn = new CopyOnWriteArrayList<>();

        @Override
        CompletableFuture<SpawnManager.AllocationOutcome> allocateSpawn(CellReserver cells) {
            if (scans.incrementAndGet() <= unsafeScans) {
                cells.reserve(getPluginConfig());
                return CompletableFuture.completedFuture(
                        new SpawnManager.NoSafeFallback("every candidate is water", 1));
            }
            return super.allocateSpawn(cells);
        }

        /** The real move needs region schedulers MockBukkit does not implement. */
        @Override
        public void sendToWorldSpawn(Player player, World target) {
            sentToWorldSpawn.add(target);
        }
    }

    private UnsafeTerrainPlugin load() {
        UnsafeTerrainPlugin plugin = MockBukkit.loadWith(UnsafeTerrainPlugin.class,
                getClass().getResourceAsStream("/plugin.yml"));
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("allocation.action-timeout-seconds", 0);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        plugin.reload();
        plugin.getLogger().addHandler(new Handler() {
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
        return plugin;
    }

    private InlinePlayerMock join(String name) {
        InlinePlayerMock player = new InlinePlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    private InlinePlayerMock rejoin(InlinePlayerMock previous) {
        InlinePlayerMock player = new InlinePlayerMock(server, previous.getName(),
                previous.getUniqueId());
        server.addPlayer(player);
        return player;
    }

    /** One uncancelled step, which is what opens the gate for a player waiting on it. */
    private void move(Player player) {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 1, 64, 0);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));
    }

    private List<LogRecord> loggedContaining(String fragment) {
        return logged.stream()
                .filter(r -> r.getMessage() != null && r.getMessage().contains(fragment))
                .toList();
    }

    @Test
    @DisplayName("a scan with nothing safe records no plot and holds the player at world spawn")
    void holdsAtWorldSpawnWithoutAPlot() {
        UnsafeTerrainPlugin plugin = load();
        InlinePlayerMock player = join("Swimmer");
        Location standing = player.getLocation();

        move(player);

        assertEquals(1, plugin.scans.get());
        assertFalse(plugin.getDataStorage().hasSpawn(player.getUniqueId()),
                "no hazard plot is recorded");
        assertEquals(List.of(world), plugin.sentToWorldSpawn,
                "held at the bound world's spawn, once");
        assertNull(player.respawnPoint, "no respawn point is forced onto anything");
        assertEquals(standing, player.getLocation(), "never teleported to a candidate");
        assertTrue(plugin.provider.reservations.isEmpty(), "nothing is claimed");
        List<LogRecord> held = loggedContaining("No safe plot was found for Swimmer");
        assertEquals(1, held.size(), "the hold is logged once");
        assertEquals(Level.WARNING, held.get(0).getLevel());
    }

    @Test
    @DisplayName("the next join after a scan with nothing safe allocates again")
    void nextJoinAllocatesAgain() {
        UnsafeTerrainPlugin plugin = load();
        InlinePlayerMock first = join("Swimmer");
        move(first);
        assertFalse(plugin.getDataStorage().hasSpawn(first.getUniqueId()));

        first.disconnect();
        InlinePlayerMock returning = rejoin(first);
        move(returning);

        assertEquals(2, plugin.scans.get(), "the rejoin scans again");
        assertTrue(plugin.getDataStorage().hasSpawn(returning.getUniqueId()),
                "and records the plot that scan found");
        assertEquals(1, plugin.getDataStorage().getRecord(returning.getUniqueId()).index(),
                "past the index the unsafe scan spent");
        assertEquals(1, plugin.sentToWorldSpawn.size(), "held only for the unsafe scan");
    }
}
