package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import com.ninja6.spiralgenesis.listeners.PlayerActionGateListener;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import java.lang.reflect.Field;
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
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player who disconnects while their terrain scan is still running.
 *
 * <p>The scan reserves the spiral index when it starts, so the plot it finds is theirs: it
 * is recorded against them even though they are gone, and they are placed on it when they
 * return, without a second index being reserved for them.
 */
class DisconnectedAllocationTest {

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

    /** How the next call to the player scheduler behaves. */
    enum Schedule {
        /** Runs the task, as for a player still connected. */
        RUN,
        /** Runs the retired callback instead, as for a player who left before it ran. */
        RETIRE,
        /** Refuses the task outright, as the scheduler does for an entity already retired. */
        REFUSE
    }

    /** The refused-write fixture, with a scheduler that can report a disconnect. */
    public static class DisconnectingPlugin extends RefusedWriteTest.RefusingPlugin {

        /** Applied to the next scheduling only, then reset to {@link Schedule#RUN}. */
        volatile Schedule next = Schedule.RUN;

        /** Run once, just before the next retired callback, then cleared. */
        volatile Runnable beforeRetire;

        /** Run once, just before the next task that is run, then cleared. */
        volatile Runnable beforeRun;

        /** Set to leave the next scan unfinished until {@link #finishScan} is called. */
        volatile boolean deferNextScan;

        private CompletableFuture<SpawnManager.AllocationOutcome> unfinished;
        private int unfinishedIndex;

        @Override
        CompletableFuture<SpawnManager.AllocationOutcome> allocateSpawn(IntSupplier indexSupplier) {
            if (!deferNextScan) {
                return super.allocateSpawn(indexSupplier);
            }
            deferNextScan = false;
            unfinishedIndex = indexSupplier.getAsInt();
            unfinished = new CompletableFuture<>();
            return unfinished;
        }

        /** Finishes the deferred scan, at the point its index says, as the others do. */
        void finishScan() {
            Location where = new Location(Bukkit.getWorlds().get(0), unfinishedIndex * 16 + 0.5,
                    64, 0.5);
            unfinished.complete(new SpawnManager.LocationResult(
                    where, unfinishedIndex, 0, 0, 63, 1, 1, false, Map.of()));
        }

        @Override
        boolean runForPlayer(Player player, Runnable action, Runnable retired) {
            Schedule mode = next;
            next = Schedule.RUN;
            switch (mode) {
                case RETIRE -> {
                    Runnable hook = beforeRetire;
                    beforeRetire = null;
                    if (hook != null) {
                        hook.run();
                    }
                    retired.run();
                    return true;
                }
                case REFUSE -> {
                    return false;
                }
                default -> {
                    Runnable hook = beforeRun;
                    beforeRun = null;
                    if (hook != null) {
                        hook.run();
                    }
                    action.run();
                    return true;
                }
            }
        }
    }

    private DisconnectingPlugin load() {
        return load("FIRST_ACTION");
    }

    private DisconnectingPlugin load(String trigger) {
        DisconnectingPlugin plugin = MockBukkit.loadWith(DisconnectingPlugin.class,
                getClass().getResourceAsStream("/plugin.yml"));
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("allocation.action-timeout-seconds", 0);
        yaml.set("allocation.trigger", trigger);
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

    /** The plugin's gate. It has no getter, and a test is no reason to add one. */
    private static PlayerActionGateListener gate(SpiralGenesisPlugin plugin) {
        try {
            Field field = SpiralGenesisPlugin.class.getDeclaredField("actionGate");
            field.setAccessible(true);
            return (PlayerActionGateListener) field.get(plugin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private InlinePlayerMock join(String name) {
        InlinePlayerMock player = new InlinePlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    /** The same player joining again, as the new entity a rejoin is. */
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

    private List<LogRecord> loggedContaining(String fragment) {
        return logged.stream()
                .filter(r -> r.getMessage() != null && r.getMessage().contains(fragment))
                .toList();
    }

    /** Asserts the plot was recorded for a player who is gone, and nothing else was done. */
    private StoredSpawn assertRecordedWhileAway(DisconnectingPlugin plugin, InlinePlayerMock leaver,
                                                Location standing) {
        UUID uuid = leaver.getUniqueId();
        StoredSpawn record = plugin.getDataStorage().getRecord(uuid);
        assertNotNull(record, "the plot found for a player who left must be recorded");
        assertEquals(0, record.index(), "the index reserved for them is the one recorded");
        assertEquals(1, plugin.getDataStorage().getCurrentIndex(),
                "one index was reserved, and it is held rather than burned");
        assertTrue(plugin.isPlacementOwed(uuid), "they are owed a placement on return");
        assertNull(leaver.respawnPoint, "no respawn point is set on a player who is gone");
        assertEquals(standing, leaver.getLocation(), "no teleport for a player who is gone");
        assertTrue(plugin.provider.reservations.isEmpty(),
                "no claim is made off the player's thread");
        assertEquals(1, loggedContaining("Leaver disconnected before plot #0").size(),
                "the deferred placement is logged once");
        return record;
    }

    /** Asserts a returning player was placed on the plot recorded while they were away. */
    private void assertPlacedOnReturn(DisconnectingPlugin plugin, InlinePlayerMock returning,
                                      StoredSpawn record) {
        Location plot = record.toLocation();
        assertEquals(plot, returning.respawnPoint, "their respawn point is the plot");
        assertEquals(plot, returning.getLocation(), "they are teleported onto the plot");
        assertEquals(1, plugin.provider.reservations.size(), "the plot is claimed once");
        assertFalse(plugin.isPlacementOwed(returning.getUniqueId()),
                "the placement is spent once made");
        assertEquals(0, plugin.getDataStorage().getRecord(returning.getUniqueId()).index(),
                "they keep the index reserved for them");
        assertEquals(1, plugin.getDataStorage().getCurrentIndex(),
                "returning does not reserve a second index");
        assertEquals(1, loggedContaining("Teleported returning player Leaver to plot #0").size());
    }

    @Test
    @DisplayName("a player who leaves before their plot is applied keeps it and is placed on it on return")
    void disconnectBeforeTaskKeepsPlot() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        Location standing = leaver.getLocation();

        // Retired with the entity: the connection is gone before the quit reaches anything.
        leaver.dropConnection();
        plugin.next = Schedule.RETIRE;
        move(leaver);
        StoredSpawn record = assertRecordedWhileAway(plugin, leaver, standing);

        leaver.disconnect();
        InlinePlayerMock returning = rejoin(leaver);
        // Placed at the moment an unassigned player would be allocated: the default trigger
        // waits for their first uncancelled action.
        assertTrue(plugin.provider.reservations.isEmpty(),
                "nothing is placed before the gate has let them go");
        move(returning);
        assertPlacedOnReturn(plugin, returning, record);

        // An ordinary returning player from here on: already on their plot, so never moved.
        returning.disconnect();
        InlinePlayerMock again = rejoin(returning);
        Location elsewhere = new Location(world, 300.5, 70, -40.5);
        again.teleport(elsewhere);
        move(again);
        assertEquals(elsewhere, again.getLocation(), "an ordinary return moves nobody");
        assertNull(again.respawnPoint, "an ordinary return sets no respawn point");
        assertEquals(1, plugin.provider.reservations.size(), "an ordinary return claims nothing");
        assertEquals(1, plugin.getDataStorage().getCurrentIndex());
    }

    @Test
    @DisplayName("a task the scheduler refuses for a departed player still records their plot")
    void refusedTaskKeepsPlot() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        Location standing = leaver.getLocation();

        // Retired with the entity: the connection is gone before the quit reaches anything.
        leaver.dropConnection();
        plugin.next = Schedule.REFUSE;
        move(leaver);
        StoredSpawn record = assertRecordedWhileAway(plugin, leaver, standing);

        leaver.disconnect();
        InlinePlayerMock returning = rejoin(leaver);
        move(returning);
        assertPlacedOnReturn(plugin, returning, record);
    }

    @Test
    @DisplayName("a task that runs after its player disconnected records the plot and moves nobody")
    void taskForDisconnectedPlayerKeepsPlot() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        Location standing = leaver.getLocation();

        // Gone by the time the task runs, without the quit having reached anything yet.
        leaver.dropConnection();
        move(leaver);
        StoredSpawn record = assertRecordedWhileAway(plugin, leaver, standing);

        leaver.disconnect();
        InlinePlayerMock returning = rejoin(leaver);
        move(returning);
        assertPlacedOnReturn(plugin, returning, record);
    }

    @Test
    @DisplayName("a player who rejoined while their scan ran is placed as soon as it is recorded")
    void rejoinDuringScanIsPlaced() {
        DisconnectingPlugin plugin = load("ON_JOIN");
        InlinePlayerMock[] returning = new InlinePlayerMock[1];
        // Back before the scan finishes: their join finds no record yet, and its own
        // allocation returns because this one is still in flight.
        plugin.beforeRetire = () -> {
            InlinePlayerMock leaver = (InlinePlayerMock) server.getPlayer("Leaver");
            leaver.disconnect();
            returning[0] = rejoin(leaver);
            assertFalse(plugin.getDataStorage().hasSpawn(leaver.getUniqueId()));
        };
        plugin.next = Schedule.RETIRE;
        InlinePlayerMock leaver = join("Leaver");

        assertNotNull(returning[0], "the fixture should have rejoined the player");
        StoredSpawn record = plugin.getDataStorage().getRecord(leaver.getUniqueId());
        assertNotNull(record);
        assertPlacedOnReturn(plugin, returning[0], record);
        assertNull(leaver.respawnPoint, "the entity that left is never touched");
    }

    @Test
    @DisplayName("a player who rejoined during the scan and is still gated is placed only on release")
    void rejoinDuringScanWaitsForTheGate() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        UUID uuid = leaver.getUniqueId();
        InlinePlayerMock[] returning = new InlinePlayerMock[1];
        plugin.beforeRetire = () -> {
            leaver.disconnect();
            returning[0] = rejoin(leaver);
            assertTrue(gate(plugin).isPending(uuid), "the rejoin is waiting on the gate");
        };
        plugin.next = Schedule.RETIRE;
        leaver.dropConnection();
        move(leaver);

        assertTrue(plugin.getDataStorage().hasSpawn(uuid), "the plot is recorded");
        assertTrue(plugin.isPlacementOwed(uuid), "the placement waits for the gate");
        assertNull(returning[0].respawnPoint, "no respawn point before the gate lets go");
        assertTrue(plugin.provider.reservations.isEmpty(), "no claim before the gate lets go");

        move(returning[0]);
        assertPlacedOnReturn(plugin, returning[0], plugin.getDataStorage().getRecord(uuid));
    }

    @Test
    @DisplayName("a rejoin found before its join event reached the gate is still placed only on release")
    void rejoinFoundBeforeTheGateWaitsForIt() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        UUID uuid = leaver.getUniqueId();
        InlinePlayerMock[] returning = new InlinePlayerMock[1];
        // The Folia ordering: the departed entity's thread finds the new session connected
        // but not yet pending, and the join event puts it in the gate before the placement
        // task runs on the new session's thread.
        plugin.beforeRetire = () -> {
            leaver.disconnect();
            returning[0] = rejoin(leaver);
            gate(plugin).forget(uuid);
            plugin.beforeRun = () -> gate(plugin).markPending(returning[0], "JAVA");
        };
        plugin.next = Schedule.RETIRE;
        leaver.dropConnection();
        move(leaver);

        assertNull(plugin.beforeRun, "the placement task should have been scheduled");
        assertTrue(gate(plugin).isPending(uuid));
        assertTrue(plugin.isPlacementOwed(uuid), "a gated player keeps the mark");
        assertNull(returning[0].respawnPoint, "no respawn point before the gate lets go");
        assertTrue(plugin.provider.reservations.isEmpty(), "no claim before the gate lets go");

        move(returning[0]);
        assertPlacedOnReturn(plugin, returning[0], plugin.getDataStorage().getRecord(uuid));
    }

    @Test
    @DisplayName("a write refused while the rejoined player is gated leaves them to the gate")
    void refusedWriteWithGatedRejoinIsNotHeld() {
        DisconnectingPlugin plugin = load();
        plugin.getDataStorage().save();
        String healthy = readData(plugin);
        InlinePlayerMock leaver = join("Leaver");
        UUID uuid = leaver.getUniqueId();
        InlinePlayerMock[] returning = new InlinePlayerMock[1];
        plugin.beforeRetire = () -> {
            leaver.disconnect();
            returning[0] = rejoin(leaver);
        };
        plugin.storage.beforeNextWrite = () -> {
            writeData(plugin, UNPARSEABLE);
            plugin.getDataStorage().load();
            assertTrue(plugin.getDataStorage().isFailed(), "the fixture should have failed storage");
        };
        plugin.next = Schedule.RETIRE;
        leaver.dropConnection();
        move(leaver);

        assertFalse(plugin.getDataStorage().hasSpawn(uuid), "a refused write records nothing");
        assertFalse(plugin.isPlacementOwed(uuid));
        assertFalse(gate(plugin).isHeld(uuid), "a gated player is not held");
        assertTrue(gate(plugin).isPending(uuid), "the gate is still waiting on them");
        assertEquals(1, loggedContaining("will be allocated after their first uncancelled action")
                .size());

        // A recovering reload resumes held players only, so it allocates nobody yet.
        writeData(plugin, healthy);
        plugin.reload();
        assertFalse(plugin.getDataStorage().hasSpawn(uuid), "nothing before they have acted");
        assertNull(returning[0].respawnPoint);

        move(returning[0]);
        StoredSpawn record = plugin.getDataStorage().getRecord(uuid);
        assertNotNull(record, "their first action allocates them");
        assertEquals(0, record.index(), "the refused index is handed out again, not burned");
        assertEquals(record.toLocation(), returning[0].respawnPoint);
        assertEquals(1, plugin.provider.reservations.size());
    }

    /** A data.yml holding one plot for this player, as an older or a marked file has it. */
    private static String plotFile(UUID uuid, String extra) {
        return "current-spiral-index: 1\n"
                + "players:\n"
                + "  " + uuid + ":\n"
                + "    name: Leaver\n"
                + "    client: JAVA\n"
                + "    assigned-index: 0\n"
                + "    grid-u: 0\n"
                + "    grid-v: 0\n"
                + "    x: 0.5\n"
                + "    y: 64.0\n"
                + "    z: 0.5\n"
                + "    world: world\n"
                + extra;
    }

    @Test
    @DisplayName("the placement mark is saved with the record, survives a reload, and is cleared once placed")
    void markSurvivesReloadAndIsClearedOnPlacement() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        UUID uuid = leaver.getUniqueId();
        leaver.dropConnection();
        plugin.next = Schedule.RETIRE;
        move(leaver);
        leaver.disconnect();

        plugin.getDataStorage().save();
        assertTrue(readData(plugin).contains("placement-owed: true"),
                "the mark is written with the record: " + readData(plugin));
        // What a restart reads: the file alone, into storage that has never seen the write.
        YamlDataStorage fresh = new YamlDataStorage(plugin);
        fresh.load();
        assertTrue(fresh.getRecord(uuid).placementOwed(), "the mark is read back from the file");

        plugin.reload();
        assertTrue(plugin.isPlacementOwed(uuid), "the mark survives a reload");

        InlinePlayerMock returning = rejoin(leaver);
        move(returning);
        assertPlacedOnReturn(plugin, returning, plugin.getDataStorage().getRecord(uuid));
        plugin.getDataStorage().save();
        assertFalse(readData(plugin).contains("placement-owed"),
                "the mark is removed from the file once placed: " + readData(plugin));
        plugin.reload();
        assertFalse(plugin.isPlacementOwed(uuid), "and stays cleared across a reload");
    }

    @Test
    @DisplayName("a marked record read from the file places its player when they next join")
    void markedFilePlacesOnJoin() {
        DisconnectingPlugin plugin = load();
        UUID uuid = UUID.randomUUID();
        writeData(plugin, plotFile(uuid, "    placement-owed: true\n"));
        // Loaded the way startup loads it: reload would save the in-memory state over it.
        plugin.getDataStorage().load();
        assertTrue(plugin.isPlacementOwed(uuid));

        InlinePlayerMock returning = new InlinePlayerMock(server, "Leaver", uuid);
        server.addPlayer(returning);
        move(returning);
        assertPlacedOnReturn(plugin, returning, plugin.getDataStorage().getRecord(uuid));
    }

    @Test
    @DisplayName("a record from a file without the key is not owed a placement")
    void legacyRecordIsNotOwed() {
        DisconnectingPlugin plugin = load();
        UUID uuid = UUID.randomUUID();
        writeData(plugin, plotFile(uuid, ""));
        // Loaded the way startup loads it: reload would save the in-memory state over it.
        plugin.getDataStorage().load();

        assertTrue(plugin.getDataStorage().hasSpawn(uuid));
        assertFalse(plugin.isPlacementOwed(uuid), "a missing key reads as not owed");

        InlinePlayerMock settler = new InlinePlayerMock(server, "Leaver", uuid);
        server.addPlayer(settler);
        Location elsewhere = new Location(world, 300.5, 70, -40.5);
        settler.teleport(elsewhere);
        move(settler);
        assertEquals(elsewhere, settler.getLocation(), "an ordinary return moves nobody");
        assertNull(settler.respawnPoint);
        assertTrue(plugin.provider.reservations.isEmpty());
        plugin.getDataStorage().save();
        assertFalse(readData(plugin).contains("placement-owed"),
                "nothing adds the key to a record that was never owed");
    }

    @Test
    @DisplayName("a rejoin whose read raced the previous session's write does not allocate again")
    void rejoinRacingTheRecordDoesNotAllocateAgain() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        UUID uuid = leaver.getUniqueId();
        plugin.deferNextScan = true;
        move(leaver);
        assertEquals(1, plugin.getDataStorage().getCurrentIndex(), "the scan has its index");

        leaver.disconnect();
        InlinePlayerMock returning = rejoin(leaver);
        // The Folia ordering: the new session reads no record, and before it takes the guard
        // the old session's scan finishes on another region, finds its entity retired,
        // records the plot, places the new session on it and releases the guard.
        plugin.next = Schedule.REFUSE;
        plugin.storage.afterNextHasSpawn = plugin::finishScan;
        move(returning);

        StoredSpawn record = plugin.getDataStorage().getRecord(uuid);
        assertPlacedOnReturn(plugin, returning, record);
        assertTrue(loggedContaining("Assigned & teleported Leaver").isEmpty(),
                "no second allocation may be made");
    }

    @Test
    @DisplayName("a placement whose clear is refused moves nobody")
    void refusedClearMovesNobody() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock leaver = join("Leaver");
        UUID uuid = leaver.getUniqueId();
        leaver.dropConnection();
        plugin.next = Schedule.RETIRE;
        move(leaver);
        leaver.disconnect();
        plugin.getDataStorage().save();
        String marked = readData(plugin);

        InlinePlayerMock returning = rejoin(leaver);
        Location standing = returning.getLocation();
        plugin.storage.beforeNextClear = () -> {
            writeData(plugin, UNPARSEABLE);
            plugin.getDataStorage().load();
            assertTrue(plugin.getDataStorage().isFailed(), "the fixture should have failed storage");
        };
        move(returning);

        assertNull(returning.respawnPoint, "a refused clear must not set a respawn point");
        assertEquals(standing, returning.getLocation(), "a refused clear must not teleport");
        assertTrue(plugin.provider.reservations.isEmpty(), "a refused clear must not claim");
        assertEquals(1, loggedContaining("Plot #0 for Leaver was not placed").size());

        // The mark is still in the file, so the placement is made once storage reads again.
        writeData(plugin, marked);
        plugin.getDataStorage().load();
        assertTrue(plugin.isPlacementOwed(uuid), "the mark survives the refused clear");
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

    /** Records a plot for Leaver while away, and brings them back without placing them. */
    private InlinePlayerMock owedAndBack(DisconnectingPlugin plugin) {
        InlinePlayerMock leaver = join("Leaver");
        leaver.dropConnection();
        plugin.next = Schedule.RETIRE;
        move(leaver);
        leaver.disconnect();
        InlinePlayerMock returning = rejoin(leaver);
        assertTrue(plugin.isPlacementOwed(returning.getUniqueId()));
        return returning;
    }

    @Test
    @DisplayName("a reassign replaces the record and clears the mark")
    void reassignClearsTheMark() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock returning = owedAndBack(plugin);
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 900.5, 64, 900.5);
        drain();
        server.executeConsole("sgen", "reassign", "Leaver").assertSucceeded();
        drain();

        assertEquals(plugin.stub.next,
                plugin.getDataStorage().getRecord(returning.getUniqueId()).toLocation());
        assertFalse(plugin.isPlacementOwed(returning.getUniqueId()));
        plugin.getDataStorage().save();
        assertFalse(readData(plugin).contains("placement-owed"), readData(plugin));
    }

    @Test
    @DisplayName("a setspawn replaces the record and clears the mark")
    void setSpawnClearsTheMark() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock returning = owedAndBack(plugin);
        drain();
        server.executeConsole("sgen", "setspawn", "Leaver", "900", "64", "900").assertSucceeded();
        drain();

        assertEquals(900, plugin.getDataStorage().getRecord(returning.getUniqueId())
                .toLocation().getBlockX());
        assertFalse(plugin.isPlacementOwed(returning.getUniqueId()));
        plugin.getDataStorage().save();
        assertFalse(readData(plugin).contains("placement-owed"), readData(plugin));
    }

    @Test
    @DisplayName("an in-cell repair keeps the mark, and never restores one already cleared")
    void repairKeepsTheMarkWithoutRestoringIt() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock returning = owedAndBack(plugin);
        UUID uuid = returning.getUniqueId();
        StoredSpawn marked = plugin.getDataStorage().getRecord(uuid);

        plugin.repairTo = new Location(world, 4.5, 66, 4.5);
        plugin.repairSpawn(returning, marked, false);
        assertEquals(plugin.repairTo, plugin.getDataStorage().getRecord(uuid).toLocation(),
                "the repair moved the point");
        assertTrue(plugin.isPlacementOwed(uuid), "a repair is no placement, so the mark stays");

        // Placed while a repair started from the marked record is still searching.
        move(returning);
        assertFalse(plugin.isPlacementOwed(uuid));
        plugin.repairTo = new Location(world, 8.5, 66, 8.5);
        plugin.repairSpawn(returning, marked, false);
        assertEquals(plugin.repairTo, plugin.getDataStorage().getRecord(uuid).toLocation());
        assertFalse(plugin.isPlacementOwed(uuid),
                "a repair must not restore a mark cleared since its search started");
    }

    @Test
    @DisplayName("a write refused for a departed player records nothing and owes nothing")
    void refusedWriteWhileAwayRecordsNothing() {
        DisconnectingPlugin plugin = load();
        plugin.getDataStorage().save();
        String healthy = readData(plugin);
        InlinePlayerMock leaver = join("Leaver");
        Location standing = leaver.getLocation();

        plugin.storage.beforeNextWrite = () -> {
            writeData(plugin, UNPARSEABLE);
            plugin.getDataStorage().load();
            assertTrue(plugin.getDataStorage().isFailed(), "the fixture should have failed storage");
        };
        // Retired with the entity: the connection is gone before the quit reaches anything.
        leaver.dropConnection();
        plugin.next = Schedule.RETIRE;
        move(leaver);

        UUID uuid = leaver.getUniqueId();
        assertFalse(plugin.getDataStorage().hasSpawn(uuid), "a refused write records nothing");
        assertFalse(plugin.isPlacementOwed(uuid), "nothing is owed for a plot never recorded");
        assertNull(leaver.respawnPoint, "a refused write must not set a respawn point");
        assertEquals(standing, leaver.getLocation(), "a refused write must not teleport");
        assertTrue(plugin.provider.reservations.isEmpty(), "a refused write must not claim");
        List<LogRecord> said = loggedContaining("Plot #0 for Leaver was not recorded");
        assertEquals(1, said.size(), "the refusal is logged once: " + said);
        assertTrue(said.get(0).getMessage().contains("they left before they could be held"),
                "the line must say they left: " + said.get(0).getMessage());
        assertTrue(loggedContaining("Leaver disconnected before plot #0").isEmpty(),
                "a refused write must not be reported as recorded");

        // Storage recovers, and the player returns to an ordinary first allocation. The
        // refused index was recorded against nobody, so it is handed out again.
        writeData(plugin, healthy);
        plugin.reload();
        leaver.disconnect();
        InlinePlayerMock returning = rejoin(leaver);
        move(returning);
        StoredSpawn record = plugin.getDataStorage().getRecord(uuid);
        assertNotNull(record, "they are allocated on return");
        assertEquals(0, record.index(), "the refused index is handed out again, not burned");
        assertEquals(record.toLocation(), returning.respawnPoint);
        assertEquals(1, plugin.provider.reservations.size());
    }
}
