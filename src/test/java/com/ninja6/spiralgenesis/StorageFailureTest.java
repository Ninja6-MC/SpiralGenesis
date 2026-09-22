package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the plugin does while {@code data.yml} cannot be read, and how it gets out of it.
 *
 * <p>The failure this guards against is quiet: an unreadable file used to load as an empty
 * one, so the spiral restarted at index zero and every returning player was allocated again
 * into cells other players already held. The storage refusing is covered in
 * {@code YamlDataStorageTest}; these cover what the rest of the plugin does about it - that
 * joining players wait in the one "allocation unavailable" hold rather than being allocated,
 * that nothing moves anyone's respawn point, that an operator is told, and that a reload
 * which reads the file brings everyone back.
 *
 * <p>The file is broken after enable and picked up by a reload, because MockBukkit only
 * reveals the data folder once the plugin exists. Enable and reload call the same load, so
 * the state is the same one a server starting on a broken file is in.
 */
class StorageFailureTest {

    /** Not YAML at all: an unclosed flow mapping, as a hand edit or a torn restore leaves. */
    private static final String UNPARSEABLE = "current-spiral-index: 12\nplayers: {\n  broken: [\n";

    private ServerMock server;
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
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

    /**
     * Allocation and the in-cell search stubbed, and counted.
     *
     * <p>The counts are the point: while storage is failed neither may be reached at all,
     * so a test that only checked storage afterwards would pass on a path that allocated
     * and then had its write refused.
     */
    public static class CountingPlugin extends SpiralGenesisPlugin {

        final AtomicInteger allocations = new AtomicInteger();
        final AtomicInteger searches = new AtomicInteger();

        @Override
        CompletableFuture<SpawnManager.AllocationOutcome> allocateSpawn(IntSupplier indexSupplier) {
            allocations.incrementAndGet();
            int index = indexSupplier.getAsInt();
            Location where = new Location(Bukkit.getWorlds().get(0), index * 16, 64, 0);
            return CompletableFuture.completedFuture(new SpawnManager.LocationResult(
                    where, index, 0, 0, 63, 1, 1, false, Map.of()));
        }

        @Override
        CompletableFuture<SpawnManager.LocationResult> searchInCell(int index) {
            searches.incrementAndGet();
            return new CompletableFuture<>();
        }

        @Override
        boolean runForPlayer(Player player, Runnable action, Runnable retired) {
            action.run();
            return true;
        }
    }

    /**
     * Loads the variant with the gate's backstop disabled, for the reason WorldBindingTest
     * gives, recording everything it logs.
     */
    private CountingPlugin load() {
        CountingPlugin plugin = MockBukkit.loadWith(CountingPlugin.class,
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

    private void writeData(SpiralGenesisPlugin plugin, String content) {
        try {
            Files.writeString(dataFile(plugin), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Breaks data.yml and loads it, which is exactly what enable does with a file that was
     * broken before the server started.
     *
     * <p>Not through {@link SpiralGenesisPlugin#reload()}: while storage is healthy a reload
     * saves before it loads, so it would write the in-memory records over the broken file
     * and then read those back.
     */
    private void breakStorage(SpiralGenesisPlugin plugin) {
        writeData(plugin, UNPARSEABLE);
        plugin.getDataStorage().load();
        assertTrue(plugin.getDataStorage().isFailed(), "the fixture should have failed storage");
    }

    private List<String> brokenCopies(SpiralGenesisPlugin plugin) {
        try (Stream<Path> files = Files.list(plugin.getDataFolder().toPath())) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("data.yml.broken-"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private InlinePlayerMock join(String name) {
        InlinePlayerMock player = new InlinePlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    /** One uncancelled step, which is what opens the gate for a player still held by it. */
    private void move(Player player) {
        Location from = new Location(server.getWorld("world"), 0, 64, 0);
        Location to = new Location(server.getWorld("world"), 1, 64, 0);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));
    }

    private List<String> drain(ConsoleCommandSenderMock console) {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = console.nextMessage()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private List<String> drain(InlinePlayerMock player) {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = player.nextMessage()) != null) {
            lines.add(line);
        }
        return lines;
    }

    @Test
    @DisplayName("a joining player is held, not allocated, and resumed by a reload that reads the file")
    void joiningPlayerIsHeldUntilRecovery() {
        CountingPlugin plugin = load();
        breakStorage(plugin);
        InlinePlayerMock player = join("Newcomer");

        move(player);
        move(player);

        assertEquals(0, plugin.allocations.get(), "nothing may be allocated while storage is failed");
        assertFalse(plugin.getDataStorage().hasSpawn(player.getUniqueId()));
        assertNull(player.respawnPoint, "the respawn point must not be touched");
        List<String> holds = logged.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .filter(m -> m.contains("Cannot allocate a spawn for Newcomer"))
                .toList();
        assertEquals(1, holds.size(), "the hold is reported once: " + holds);
        assertTrue(holds.get(0).contains("data.yml could not be read"),
                "the hold must give the storage reason: " + holds.get(0));
        assertTrue(holds.get(0).contains("/sgen reload"),
                "the hold must say what releases them: " + holds.get(0));

        writeData(plugin, "current-spiral-index: 5\n");
        plugin.reload();

        assertFalse(plugin.getDataStorage().isFailed());
        assertEquals(1, plugin.allocations.get(), "the reload must resume the held player");
        assertEquals(5, plugin.getDataStorage().getRecord(player.getUniqueId()).index(),
                "they are allocated from the counter the file holds, not from zero");
    }

    @Test
    @DisplayName("a returning player's respawn point is not moved while storage is failed")
    void respawnPointIsNotChanged() {
        CountingPlugin plugin = load();
        InlinePlayerMock player = join("Settler");
        Location plot = new Location(server.getWorld("world"), 10.5, 64, 10.5);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), plot, 3, 0, 0, "Settler", "JAVA");
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        plugin.getDataStorage().save();
        breakStorage(plugin);

        // No respawn point, which is what the death handler would otherwise fill with the plot.
        server.getPluginManager().callEvent(new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<ItemStack>(), 0,
                (String) null));
        assertNull(player.respawnPoint, "death must not set a respawn point from lost records");

        PlayerSetSpawnEvent lost = new PlayerSetSpawnEvent(player,
                PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN, null, false, false, null);
        server.getPluginManager().callEvent(lost);
        assertNull(lost.getLocation(), "a lost respawn point must not be restored");

        // A repair held from before the failure must not start either.
        plugin.repairSpawn(player, record, false);
        assertEquals(0, plugin.searches.get(), "no repair may run while storage is failed");
    }

    @Test
    @DisplayName("an operator is told on join; a player without the permission is not")
    void operatorIsNotifiedOnJoin() {
        CountingPlugin plugin = load();
        breakStorage(plugin);
        String copy = plugin.getDataStorage().getFailure().brokenCopy();

        InlinePlayerMock op = new InlinePlayerMock(server, "Admin");
        // Granted directly rather than through op, which MockBukkit only reads for a player
        // already on the server - too late for the join this is about.
        op.addAttachment(plugin, SpiralGenesisPlugin.ADMIN_PERMISSION, true);
        server.addPlayer(op);
        InlinePlayerMock guest = join("Guest");

        List<String> told = drain(op);
        assertTrue(told.stream().anyMatch(m -> m.contains("could not read data.yml")
                        && m.contains(copy) && m.contains("/sgen reload")),
                "the operator must be told, with the copy named: " + told);
        assertTrue(drain(guest).stream().noneMatch(m -> m.contains("data.yml")),
                "a player without the permission hears nothing about it");
    }

    @Test
    @DisplayName("a reload that still cannot read the file says so, logs again and copies nothing more")
    void failedReloadReportsWithoutCopyingAgain() {
        CountingPlugin plugin = load();
        breakStorage(plugin);
        List<String> copies = brokenCopies(plugin);
        assertEquals(1, copies.size());
        long severeBefore = logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count();
        ConsoleCommandSenderMock console = server.getConsoleSender();
        drain(console);

        server.executeConsole("sgen", "reload").assertSucceeded();

        List<String> reply = drain(console);
        assertTrue(reply.stream().anyMatch(m -> m.contains("storage is still unavailable")
                        && m.contains(copies.get(0))),
                "the reload must report the failure and name the copy: " + reply);
        assertTrue(reply.stream().noneMatch(m -> m.contains("reloaded successfully")),
                "a failed reload must not read as a success: " + reply);
        assertEquals(severeBefore + 1,
                logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count(),
                "the error is reported again");
        assertEquals(copies, brokenCopies(plugin), "the same file must not be copied twice");
        assertTrue(plugin.getDataStorage().isFailed());
    }

    @Test
    @DisplayName("a reload that reads the file clears the state and reports success")
    void successfulReloadClearsTheState() throws IOException {
        CountingPlugin plugin = load();
        breakStorage(plugin);
        writeData(plugin, "current-spiral-index: 3\n");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        drain(console);

        server.executeConsole("sgen", "reload").assertSucceeded();

        assertFalse(plugin.getDataStorage().isFailed());
        assertNull(plugin.storageFailureNotice());
        assertTrue(drain(console).stream().anyMatch(m -> m.contains("reloaded successfully")));
        assertEquals("current-spiral-index: 3\n",
                Files.readString(dataFile(plugin), StandardCharsets.UTF_8),
                "the reload's save must not have written over the repaired file first");
    }

    @Test
    @DisplayName("commands that read or write records are refused while storage is failed")
    void recordCommandsAreRefused() {
        CountingPlugin plugin = load();
        InlinePlayerMock player = join("Target");
        breakStorage(plugin);
        ConsoleCommandSenderMock console = server.getConsoleSender();
        drain(console);

        for (String sub : List.of("setspawn", "allocate", "reassign", "info")) {
            server.executeConsole("sgen", sub, "Target", "1", "64", "1").assertSucceeded();
            List<String> reply = drain(console);
            assertTrue(reply.stream().anyMatch(m -> m.contains("could not read data.yml")),
                    "/sgen " + sub + " must be refused with the storage notice: " + reply);
        }
        assertEquals(0, plugin.allocations.get());
        assertNull(player.respawnPoint, "setspawn must not have moved the respawn point");
        assertEquals(UNPARSEABLE, readData(plugin), "nothing may have been written");
    }

    private String readData(SpiralGenesisPlugin plugin) {
        try {
            return Files.readString(dataFile(plugin), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
