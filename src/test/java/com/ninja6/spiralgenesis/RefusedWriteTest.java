package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.protection.ProtectionProvider;
import com.ninja6.spiralgenesis.protection.RecordingProvider;
import com.ninja6.spiralgenesis.storage.DataStorage;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record write refused after every check the caller could make had passed.
 *
 * <p>The window is the one a {@code /sgen reload} that fails to read {@code data.yml} opens
 * on another thread: the caller has already decided storage is readable, and the write is
 * then refused. Nothing a caller checks beforehand can close it, which is why the storage
 * here fails from inside {@code setSpawn} itself, after the caller's last check and before
 * the write is decided. A caller still gated on {@code isFailed()} rather than on the
 * write's answer goes on to move the player.
 */
class RefusedWriteTest {

    /** Not YAML at all, as in StorageFailureTest. */
    private static final String UNPARSEABLE = "current-spiral-index: 12\nplayers: {\n  broken: [\n";

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

    /** Storage that can be made to fail between a caller's checks and its next write. */
    static final class FailingStorage extends YamlDataStorage {

        /** Run once, at the start of the next write, then cleared. */
        volatile Runnable beforeNextWrite;

        FailingStorage(JavaPlugin plugin) {
            super(plugin);
        }

        @Override
        public boolean setSpawn(UUID uuid, Location location, int index, int gridU, int gridV,
                                String playerName, String clientType) {
            Runnable hook = beforeNextWrite;
            beforeNextWrite = null;
            if (hook != null) {
                hook.run();
            }
            return super.setSpawn(uuid, location, index, gridU, gridV, playerName, clientType);
        }
    }

    /** A spawn manager for reassign that hands back a fixed point without a chunk. */
    private static final class StubSpawnManager extends SpawnManager {

        private Location next;

        private StubSpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
        }

        @Override
        public CompletableFuture<AllocationOutcome> allocateNextSafeSpawn(IntSupplier indexSupplier) {
            int index = indexSupplier.getAsInt();
            return CompletableFuture.completedFuture(new SpawnManager.LocationResult(
                    next, index, 0, 0, 63, 1, 1, false, Map.of()));
        }
    }

    /** The failing storage, a recording provider, and allocation that touches no chunk. */
    public static class RefusingPlugin extends SpiralGenesisPlugin {

        FailingStorage storage;
        final RecordingProvider provider = new RecordingProvider();
        StubSpawnManager stub;

        @Override
        DataStorage createDataStorage() {
            storage = new FailingStorage(this);
            return storage;
        }

        @Override
        public ProtectionProvider getProtectionProvider() {
            return provider;
        }

        @Override
        public SpawnManager getSpawnManager() {
            if (stub == null) {
                stub = new StubSpawnManager(this, Bukkit.getWorlds().get(0), getPluginConfig());
            }
            return stub;
        }

        @Override
        CompletableFuture<SpawnManager.AllocationOutcome> allocateSpawn(IntSupplier indexSupplier) {
            int index = indexSupplier.getAsInt();
            Location where = new Location(Bukkit.getWorlds().get(0), index * 16 + 0.5, 64, 0.5);
            return CompletableFuture.completedFuture(new SpawnManager.LocationResult(
                    where, index, 0, 0, 63, 1, 1, false, Map.of()));
        }

        @Override
        boolean runForPlayer(Player player, Runnable action, Runnable retired) {
            action.run();
            return true;
        }
    }

    private RefusingPlugin load() {
        RefusingPlugin plugin = MockBukkit.loadWith(RefusingPlugin.class,
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

    private Path dataFile(SpiralGenesisPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve("data.yml");
    }

    private String readData(SpiralGenesisPlugin plugin) {
        try {
            return Files.readString(dataFile(plugin), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeData(SpiralGenesisPlugin plugin, String content) {
        try {
            Files.writeString(dataFile(plugin), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Arms the storage to fail at the start of its next write, the way a reload that cannot
     * read the file does: the file is broken and loaded, and the write then runs against
     * failed storage.
     */
    private void failOnNextWrite(RefusingPlugin plugin) {
        plugin.storage.beforeNextWrite = () -> {
            writeData(plugin, UNPARSEABLE);
            plugin.getDataStorage().load();
            assertTrue(plugin.getDataStorage().isFailed(), "the fixture should have failed storage");
        };
    }

    private InlinePlayerMock join(String name) {
        InlinePlayerMock player = new InlinePlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    /** One uncancelled step, which is what opens the gate for a player waiting on it. */
    private void move(Player player) {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 1, 64, 0);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));
    }

    private List<String> drain() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = console.nextMessage()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private List<LogRecord> loggedContaining(String fragment) {
        return logged.stream()
                .filter(r -> r.getMessage() != null && r.getMessage().contains(fragment))
                .toList();
    }

    @Test
    @DisplayName("a first allocation whose write is refused moves nobody and is retried once storage reads")
    void refusedAllocationMovesNobody() {
        RefusingPlugin plugin = load();

        // A player allocated normally first, so there is a record the refused one could
        // collide with, a claim to compare against, and a file to restore.
        InlinePlayerMock settler = join("Settler");
        move(settler);
        StoredSpawn settled = plugin.getDataStorage().getRecord(settler.getUniqueId());
        assertEquals(0, settled.index());
        assertEquals(1, plugin.provider.reservations.size(), "the control allocation claims");
        plugin.getDataStorage().save();
        String healthy = readData(plugin);

        InlinePlayerMock newcomer = join("Newcomer");
        Location standing = newcomer.getLocation();
        failOnNextWrite(plugin);
        move(newcomer);

        assertFalse(plugin.getDataStorage().hasSpawn(newcomer.getUniqueId()));
        assertNull(newcomer.respawnPoint, "a refused write must not set a respawn point");
        assertEquals(standing, newcomer.getLocation(), "a refused write must not teleport");
        assertEquals(1, plugin.provider.reservations.size(), "a refused write must not claim");
        List<LogRecord> said = loggedContaining("Plot #1 for Newcomer was not recorded");
        assertEquals(1, said.size(), "the refusal is logged once: " + said);
        assertEquals(Level.WARNING, said.get(0).getLevel());
        assertNull(said.get(0).getThrown(), "an expected refusal is plain text, not a trace");
        assertTrue(loggedContaining("Cannot allocate a spawn for Newcomer").isEmpty(),
                "the refusal line replaces the hold's own, rather than adding a second");
        assertTrue(loggedContaining("Assigned & teleported Newcomer").isEmpty(),
                "nothing may report success");

        // The operator restores the file and reloads. A reload resumes held players only, so
        // the newcomer being allocated without acting again is what shows they were held
        // rather than dropped.
        writeData(plugin, healthy);
        plugin.reload();

        assertFalse(plugin.getDataStorage().isFailed());
        assertTrue(plugin.getDataStorage().hasSpawn(newcomer.getUniqueId()),
                "the player must have been held for a retry, not dropped");
        StoredSpawn allocated = plugin.getDataStorage().getRecord(newcomer.getUniqueId());
        assertEquals(1, allocated.index(),
                "the refused index was recorded against nobody, so it is handed out again"
                        + " rather than burned");
        assertEquals(0, plugin.getDataStorage().getRecord(settler.getUniqueId()).index(),
                "the earlier player keeps their index");
        assertEquals(2, plugin.getDataStorage().getCurrentIndex(),
                "the counter follows the records, with nothing left unaccounted for");
        assertEquals(allocated.toLocation(), newcomer.respawnPoint);
        assertEquals(2, plugin.provider.reservations.size(), "the retry claims once");
    }

    @Test
    @DisplayName("a refused write never lets two players share an index")
    void refusedIndexIsNeverShared() {
        RefusingPlugin plugin = load();
        InlinePlayerMock first = join("First");
        failOnNextWrite(plugin);
        move(first);
        assertFalse(plugin.getDataStorage().hasSpawn(first.getUniqueId()));

        // Recovery from a file whose counter had already advanced past the refused index,
        // as it would if a flush landed between the reservation and the failure.
        writeData(plugin, "current-spiral-index: 1\n");
        plugin.reload();
        assertTrue(plugin.getDataStorage().hasSpawn(first.getUniqueId()),
                "the reload resumes the held player");
        InlinePlayerMock second = join("Second");
        move(second);

        int firstIndex = plugin.getDataStorage().getRecord(first.getUniqueId()).index();
        int secondIndex = plugin.getDataStorage().getRecord(second.getUniqueId()).index();
        assertEquals(1, firstIndex, "the resumed player is allocated from the file's counter");
        assertEquals(2, secondIndex);
        assertEquals(2, plugin.getDataStorage().getAllRecords().values().stream()
                        .mapToInt(StoredSpawn::index).distinct().count(),
                "no index may be held by two players");
    }

    @Test
    @DisplayName("a reassign whose write is refused changes nothing and says so")
    void refusedReassignChangesNothing() {
        RefusingPlugin plugin = load();
        InlinePlayerMock bob = join("Bob");
        Location plot = new Location(world, 10.5, 64, 10.5);
        plugin.getDataStorage().setSpawn(bob.getUniqueId(), plot, 3, 0, 0, "Bob", "JAVA");
        bob.setRespawnLocation(plot, true);
        plugin.getDataStorage().save();
        String healthy = readData(plugin);
        Location standing = bob.getLocation();
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 900.5, 64, 900.5);
        drain();

        failOnNextWrite(plugin);
        server.executeConsole("sgen", "reassign", "Bob", "release").assertSucceeded();
        List<String> reply = drain();

        assertTrue(reply.stream().anyMatch(m -> m.contains("Reassignment of Bob was abandoned")
                        && m.contains("could not read data.yml")),
                "the operator must be told nothing happened, and why: " + reply);
        assertTrue(reply.stream().noneMatch(m -> m.contains("Successfully reassigned")),
                "a refused write must not read as a success: " + reply);
        assertEquals(plot, bob.respawnPoint, "the respawn point must stay on the old plot");
        assertEquals(standing, bob.getLocation(), "a refused write must not teleport");
        assertTrue(plugin.provider.reservations.isEmpty(), "the new plot must not be claimed");
        assertTrue(plugin.provider.releases.isEmpty(),
                "the old claim must not be released for a move that was never recorded");
        List<LogRecord> said = loggedContaining("Reassignment of Bob to plot #");
        assertEquals(1, said.size(), "the refusal is logged once: " + said);
        assertNull(said.get(0).getThrown(), "an expected refusal is plain text, not a trace");

        writeData(plugin, healthy);
        plugin.reload();
        assertEquals(3, plugin.getDataStorage().getRecord(bob.getUniqueId()).index(),
                "Bob still holds the plot he had");
    }
}
