package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import com.ninja6.spiralgenesis.DisconnectedAllocationTest.DisconnectingPlugin;
import com.ninja6.spiralgenesis.listeners.PlayerActionGateListener;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player who played on the server before SpiralGenesis was installed.
 *
 * <p>They are left exactly where they are: no index, no record, no respawn point, no
 * teleport and no claim, and nothing left watching them. What separates them from a player
 * who first joined after the install and left before their plot was placed is the
 * first-played time against the install time, not whether they have played before, which
 * is true of both.
 */
class PreInstallPlayerTest {

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

    /** A player whose server data file has no Bukkit section, as a vanilla server leaves. */
    static final class VanillaFilePlayerMock extends InlinePlayerMock {

        VanillaFilePlayerMock(ServerMock server, String name, UUID uuid) {
            super(server, name, uuid);
        }

        @Override
        public boolean hasPlayedBefore() {
            return true;
        }

        @Override
        public long getLastPlayed() {
            return 0L;
        }
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

    private DisconnectingPlugin load() {
        return load("FIRST_ACTION");
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

    /** Joins a player the server has seen before, first at {@code firstPlayed}. */
    private InlinePlayerMock joinSeenAt(String name, UUID uuid, long firstPlayed) {
        // Sets hasPlayedBefore as well, as the server's player file does.
        server.getPlayerList().setFirstPlayed(uuid, firstPlayed);
        InlinePlayerMock player = new InlinePlayerMock(server, name, uuid);
        server.addPlayer(player);
        return player;
    }

    /** Joins a player who first played a minute before the plugin was installed. */
    private InlinePlayerMock joinPreInstall(SpiralGenesisPlugin plugin, String name) {
        long installed = plugin.getDataStorage().getInstalledAt().toEpochMilli();
        return joinSeenAt(name, UUID.randomUUID(), installed - 60_000L);
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

    private List<LogRecord> skipLines(String name) {
        return logged.stream()
                .filter(r -> r.getMessage() != null && r.getMessage().startsWith(
                        name + " played on this server before SpiralGenesis was installed"))
                .toList();
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

    /** Asserts nothing at all was done to a player, and nothing is left watching them. */
    private void assertUntouched(DisconnectingPlugin plugin, InlinePlayerMock player,
                                 Location standing) {
        UUID uuid = player.getUniqueId();
        assertFalse(plugin.getDataStorage().hasSpawn(uuid), "no plot is recorded");
        assertEquals(0, plugin.getDataStorage().getCurrentIndex(), "no index is reserved");
        assertNull(player.respawnPoint, "their bed or anchor is left alone");
        assertEquals(standing, player.getLocation(), "they are not teleported");
        assertTrue(plugin.provider.reservations.isEmpty(), "nothing is claimed");
        PlayerActionGateListener gate = gate(plugin);
        assertFalse(gate.isPending(uuid), "the gate is not waiting on them");
        assertFalse(gate.isHeld(uuid), "they are not held for a retry");
    }

    @Test
    @DisplayName("a player from before the install is not gated, allocated or moved, and is reported once")
    void preInstallPlayerIsSkipped() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock veteran = joinPreInstall(plugin, "Veteran");
        Location standing = veteran.getLocation();

        assertFalse(gate(plugin).isPending(veteran.getUniqueId()),
                "there is nothing to allocate, so nothing to wait for");
        move(veteran);
        assertUntouched(plugin, veteran, standing);

        List<LogRecord> said = skipLines("Veteran");
        assertEquals(1, said.size(), "the skip is reported: " + said);
        assertEquals(Level.INFO, said.get(0).getLevel());
        assertTrue(said.get(0).getMessage().contains("/sgen reassign Veteran"),
                "the line names the command that places them");

        veteran.disconnect();
        InlinePlayerMock again = rejoin(veteran);
        move(again);
        assertUntouched(plugin, again, standing);
        assertEquals(1, skipLines("Veteran").size(), "reported once, not on every join");
    }

    @Test
    @DisplayName("a player from before the install is skipped under ON_JOIN too")
    void preInstallPlayerIsSkippedOnJoin() {
        DisconnectingPlugin plugin = load("ON_JOIN");
        InlinePlayerMock veteran = joinPreInstall(plugin, "Veteran");

        assertUntouched(plugin, veteran, veteran.getLocation());
        assertEquals(1, skipLines("Veteran").size());
    }

    @Test
    @DisplayName("allocation skips a pre-install player and releases the gate, whatever the caller")
    void allocationSkipsAndReleasesTheGate() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock veteran = joinPreInstall(plugin, "Veteran");
        Location standing = veteran.getLocation();
        // As if a login-plugin adapter or the timeout reached allocation with them still
        // being watched.
        gate(plugin).markPending(veteran, "JAVA");

        plugin.allocateNow(veteran, "JAVA");

        assertUntouched(plugin, veteran, standing);
    }

    @Test
    @DisplayName("a player whose file predates Bukkit on this server is skipped")
    void vanillaPlayerFileIsSkipped() {
        DisconnectingPlugin plugin = load();
        // The server reads their first-played time as this join, since the file has none.
        InlinePlayerMock veteran = new VanillaFilePlayerMock(server, "Veteran", UUID.randomUUID());
        server.addPlayer(veteran);
        assertTrue(veteran.getFirstPlayed() >= plugin.getDataStorage().getInstalledAt()
                .toEpochMilli(), "fixture: the first-played time alone would read as post-install");

        move(veteran);

        assertUntouched(plugin, veteran, veteran.getLocation());
        assertEquals(1, skipLines("Veteran").size());
    }

    @Test
    @DisplayName("a player who first joined after the install and has no plot is still allocated")
    void postInstallPlayerWithoutRecordIsAllocated() {
        DisconnectingPlugin plugin = load();
        long installed = plugin.getDataStorage().getInstalledAt().toEpochMilli();
        // Played before, in the sense hasPlayedBefore means: joined after the install and
        // left before being placed.
        InlinePlayerMock newcomer = joinSeenAt("Newcomer", UUID.randomUUID(), installed + 1_000L);
        assertTrue(newcomer.hasPlayedBefore(), "fixture: the server has seen them before");

        move(newcomer);

        StoredSpawn record = plugin.getDataStorage().getRecord(newcomer.getUniqueId());
        assertNotNull(record, "they are allocated a plot");
        assertEquals(0, record.index());
        assertEquals(record.toLocation(), newcomer.respawnPoint);
        assertEquals(record.toLocation(), newcomer.getLocation());
        assertEquals(1, plugin.provider.reservations.size());
        assertTrue(skipLines("Newcomer").isEmpty(), "they are not reported as skipped");
    }

    @Test
    @DisplayName("a brand-new player is allocated as before")
    void newPlayerIsAllocated() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock fresh = new InlinePlayerMock(server, "Fresh");
        server.addPlayer(fresh);
        assertFalse(fresh.hasPlayedBefore(), "fixture");

        move(fresh);

        assertTrue(plugin.getDataStorage().hasSpawn(fresh.getUniqueId()));
        assertEquals(1, plugin.getDataStorage().getCurrentIndex());
    }

    @Test
    @DisplayName("a pre-install player who already has a plot keeps it, and one owed a placement is placed")
    void recordWinsOverThePreInstallSkip() {
        DisconnectingPlugin plugin = load();
        long installed = plugin.getDataStorage().getInstalledAt().toEpochMilli();
        UUID settledId = UUID.randomUUID();
        UUID owedId = UUID.randomUUID();
        Location settledPlot = new Location(world, 16.5, 64, 0.5);
        Location owedPlot = new Location(world, 32.5, 64, 0.5);
        // An upgrade: both were allocated by an earlier version, and the second one's plot
        // was recorded after they disconnected.
        plugin.getDataStorage().setSpawn(settledId, settledPlot, 1, 1, 0, "Settled", "JAVA");
        plugin.getDataStorage().setSpawn(owedId, owedPlot, 2, 2, 0, "Owed", "JAVA", true);

        InlinePlayerMock settled = joinSeenAt("Settled", settledId, installed - 60_000L);
        Location elsewhere = new Location(world, 300.5, 70, -40.5);
        settled.teleport(elsewhere);
        move(settled);
        assertEquals(elsewhere, settled.getLocation(), "an ordinary return moves nobody");
        assertNull(settled.respawnPoint);
        assertEquals(1, plugin.getDataStorage().getRecord(settledId).index(), "the plot is kept");

        InlinePlayerMock owed = joinSeenAt("Owed", owedId, installed - 60_000L);
        move(owed);
        assertEquals(owedPlot, owed.respawnPoint, "the owed placement is made");
        assertEquals(owedPlot, owed.getLocation());
        assertFalse(plugin.isPlacementOwed(owedId), "and spent");
        assertEquals(1, plugin.provider.reservations.size(), "the owed plot is claimed once");

        assertTrue(skipLines("Settled").isEmpty() && skipLines("Owed").isEmpty(),
                "a player with a record is never reported as skipped");
        assertEquals(0, plugin.getDataStorage().getCurrentIndex(), "nothing new is reserved");
    }

    @Test
    @DisplayName("a pre-install player held while storage was failed is skipped on recovery, not allocated")
    void heldPlayerIsSkippedOnRecovery() {
        DisconnectingPlugin plugin = load();
        long installed = plugin.getDataStorage().getInstalledAt().toEpochMilli();
        plugin.getDataStorage().save();
        String healthy = readData(plugin);
        assertTrue(healthy.contains("installed-at"), "fixture: the install time is on disk");

        writeData(plugin, UNPARSEABLE);
        plugin.getDataStorage().load();
        assertTrue(plugin.getDataStorage().isFailed());
        assertNull(plugin.getDataStorage().getInstalledAt(), "unreadable while failed");

        InlinePlayerMock veteran = joinSeenAt("Veteran", UUID.randomUUID(), installed - 60_000L);
        Location standing = veteran.getLocation();
        move(veteran);
        assertTrue(gate(plugin).isHeld(veteran.getUniqueId()),
                "held like anyone else while the install time cannot be read");

        writeData(plugin, healthy);
        plugin.reload();

        assertFalse(plugin.getDataStorage().isFailed());
        assertUntouched(plugin, veteran, standing);
        assertEquals(1, skipLines("Veteran").size());
    }

    @Test
    @DisplayName("sgen reassign places a pre-install player, and sgen allocate says to use it")
    void reassignPlacesAPreInstallPlayer() {
        DisconnectingPlugin plugin = load();
        InlinePlayerMock veteran = joinPreInstall(plugin, "Veteran");
        Location standing = veteran.getLocation();
        drain();

        server.executeConsole("sgen", "allocate", "Veteran").assertSucceeded();
        List<String> reply = drain();
        assertTrue(reply.stream().anyMatch(m -> m.contains("before SpiralGenesis was installed")
                        && m.contains("/sgen reassign Veteran")),
                "allocate explains itself rather than going quiet: " + reply);
        assertUntouched(plugin, veteran, standing);

        plugin.getSpawnManager();
        Location plot = new Location(world, 900.5, 64, 900.5);
        plugin.stub.next = plot;
        server.executeConsole("sgen", "reassign", "Veteran").assertSucceeded();

        StoredSpawn record = plugin.getDataStorage().getRecord(veteran.getUniqueId());
        assertNotNull(record, "reassign records a plot for a player with none");
        assertEquals(0, record.index());
        assertEquals(plot, veteran.respawnPoint);
        assertEquals(plot, veteran.getLocation());
        assertEquals(1, plugin.provider.reservations.size());
    }
}
