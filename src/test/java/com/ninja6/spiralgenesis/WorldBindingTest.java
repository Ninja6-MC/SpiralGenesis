package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the plugin binds allocation to, and what it says when it cannot.
 *
 * <p>A wrong bind is the expensive failure here: allocation force-overwrites a respawn
 * point and teleports the player, so a spiral carved into whichever world happened to load
 * first cannot be taken back from the people it has already moved. These tests hold the
 * line that {@code origin.world} is matched exactly or nothing is bound at all, and that
 * the operator is told both the name they configured and what is actually loaded.
 *
 * <p>The log is asserted on rather than the field alone, because the field being null is
 * indistinguishable to an administrator from the plugin working - which is exactly the
 * state this behaviour replaced.
 */
class WorldBindingTest {

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
            // cancel. Swallowed for the reason AllocationOwnershipTest documents: the
            // exception extends TestAbortedException, so letting it out of teardown reports
            // every test in the class as skipped rather than run.
            MockBukkit.unmock();
        }
    }

    /** Loads the real plugin against the default config, recording everything it logs. */
    private SpiralGenesisPlugin load() {
        return record(MockBukkit.load(SpiralGenesisPlugin.class));
    }

    /**
     * Loads a plugin variant with the gate's backstop disabled.
     *
     * <p>loadWith, not load: MockBukkit looks for plugin.yml beside the class it is given,
     * and a variant compiles into the test tree rather than next to the resource. Arming
     * the backstop would schedule on the player's own scheduler, which MockBukkit does not
     * implement, and these cases are about the caller path in any event.
     */
    private <T extends SpiralGenesisPlugin> T loadVariant(Class<T> type) {
        T plugin = MockBukkit.loadWith(type, getClass().getResourceAsStream("/plugin.yml"));
        write(plugin, yaml -> yaml.set("allocation.action-timeout-seconds", 0));
        plugin.reload();
        return record(plugin);
    }

    private <T extends SpiralGenesisPlugin> T record(T plugin) {
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

    /** Applies an edit to the plugin's own config file, as an operator would. */
    private void write(SpiralGenesisPlugin plugin, Consumer<YamlConfiguration> edit) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        edit.accept(yaml);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Points {@code origin.world} at {@code name}, without reloading. */
    private void writeWorld(SpiralGenesisPlugin plugin, String name) {
        write(plugin, yaml -> yaml.set("origin.world", name));
    }

    /** Points {@code origin.world} at {@code name} and reloads, as an operator edit would. */
    private void configureWorld(SpiralGenesisPlugin plugin, String name) {
        writeWorld(plugin, name);
        plugin.reload();
    }

    /**
     * Joins a player the way the server does, so the gate marks them pending on the way in.
     *
     * <p>The teleport override is a mock defect, not a seam: MockBukkit's
     * {@code teleportAsync} returns {@code null} instead of a future, and the resulting
     * NullPointerException is swallowed by allocation's own error handling - which would
     * leave this test passing on a path that never finished. AllocationOwnershipTest
     * documents the same defect.
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
        Location from = new Location(server.getWorld("world"), 0, 64, 0);
        Location to = new Location(server.getWorld("world"), 1, 64, 0);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));
    }

    /** Everything the console was told since it was last drained. */
    private List<String> drainConsole() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = console.nextMessage()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private List<String> messagesAt(Level level) {
        return logged.stream()
                .filter(record -> record.getLevel() == level)
                .map(LogRecord::getMessage)
                .toList();
    }

    @Test
    @DisplayName("an unresolvable world binds nothing and is reported at SEVERE")
    void unresolvableWorldIsRefusedLoudly() {
        SpiralGenesisPlugin plugin = load();
        configureWorld(plugin, "survival");

        assertNull(plugin.getSpawnManager(),
                "a name that resolves to no world must not bind another one");

        List<String> severe = messagesAt(Level.SEVERE);
        assertEquals(1, severe.size(), "expected one report, got " + severe);
        assertTrue(severe.get(0).contains("'survival'"),
                "the configured name must be named: " + severe.get(0));
        // Asserted on the segment rather than the bare name: "Configured world '...'"
        // already contains it, so a message that dropped the list would still pass.
        assertTrue(severe.get(0).contains("Loaded worlds: world"),
                "the loaded worlds must be listed: " + severe.get(0));
    }

    @Test
    @DisplayName("a resolvable world binds and names what it bound")
    void resolvableWorldIsBoundAndNamed() {
        SpiralGenesisPlugin plugin = load();
        configureWorld(plugin, "world");

        assertNotNull(plugin.getSpawnManager());
        assertEquals(List.of(), messagesAt(Level.SEVERE));
        assertTrue(messagesAt(Level.INFO).stream().anyMatch(m -> m.contains("bound to world 'world'")),
                "the bound world must appear in the log: " + messagesAt(Level.INFO));
    }

    @Test
    @DisplayName("a world created after enable is picked up by the next re-resolve")
    void lateLoadedWorldBindsOnRetry() {
        SpiralGenesisPlugin plugin = load();
        configureWorld(plugin, "survival");
        assertNull(plugin.getSpawnManager());

        // What a world manager does from its own onEnable, after this plugin's.
        server.addSimpleWorld("survival");
        plugin.initSpawnManager();

        assertNotNull(plugin.getSpawnManager(), "the configured world exists now and must bind");
        assertTrue(messagesAt(Level.INFO).stream().anyMatch(m -> m.contains("bound to world 'survival'")),
                "the late bind must be reported: " + messagesAt(Level.INFO));
    }

    /**
     * Unbinds the world from inside allocation, which is the race no guard can close.
     *
     * <p>{@code handlePlayerFirstJoin} checks the manager and then hands off to a scan that
     * takes several ticks; an administrator reloading onto a bad name in that window used
     * to leave the scan dereferencing a field that had gone null. The override runs that
     * reload at exactly the moment the seam is entered, then calls the real seam.
     */
    public static class UnbindingPlugin extends SpiralGenesisPlugin {

        /** Entries into allocation, whichever caller made them. */
        final AtomicInteger allocationCalls = new AtomicInteger();

        /** Set by the test: what an administrator's reload does. */
        Runnable unbind = () -> { };

        /** Armed for one allocation, so the retry afterwards runs normally. */
        volatile boolean unbindNext;

        @Override
        public void handlePlayerFirstJoin(Player player, String clientType) {
            allocationCalls.incrementAndGet();
            super.handlePlayerFirstJoin(player, clientType);
        }

        @Override
        CompletableFuture<SpawnManager.LocationResult> allocateSpawn(IntSupplier indexSupplier) {
            if (unbindNext) {
                unbindNext = false;
                unbind.run();
                // The real seam, which is what has to cope with the field having gone null.
                return super.allocateSpawn(indexSupplier);
            }
            int index = indexSupplier.getAsInt();
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

    @Test
    @DisplayName("a reload that unbinds mid-allocation allocates nothing and strands nobody")
    void unbindDuringAllocationLeavesThePlayerRetryable() {
        UnbindingPlugin plugin = loadVariant(UnbindingPlugin.class);
        plugin.unbind = () -> configureWorld(plugin, "survival");
        PlayerMock player = join("Racer");
        assertEquals(0, plugin.allocationCalls.get(), "joining should only have gated them");

        plugin.unbindNext = true;
        move(player);

        assertEquals(1, plugin.allocationCalls.get());
        assertNull(plugin.getSpawnManager(), "the reload should have unbound the world");
        assertFalse(plugin.getDataStorage().hasSpawn(player.getUniqueId()),
                "nothing may be allocated once no world is bound");

        // The operator fixes the name, and the player acts again.
        configureWorld(plugin, "world");
        move(player);

        assertEquals(2, plugin.allocationCalls.get(),
                "a player dropped from the gate without being allocated must be put back");
        assertTrue(plugin.getDataStorage().hasSpawn(player.getUniqueId()),
                "the retry must allocate them");
    }

    @Test
    @DisplayName("a reload that binds nothing says so to whoever ran it, every time")
    void failedReloadIsReportedToTheSender() {
        SpiralGenesisPlugin plugin = load();
        writeWorld(plugin, "survival");
        drainConsole();

        server.executeConsole("sgen", "reload").assertSucceeded();
        List<String> first = drainConsole();
        assertTrue(first.stream().anyMatch(line -> line.contains("No world is bound")
                        && line.contains("'survival'")),
                "the sender must be told the bind failed: " + first);
        assertNull(plugin.getSpawnManager());

        // The same name a second time: the once-per-name suppression must not silence the
        // remediation loop the error message itself points the operator at.
        server.executeConsole("sgen", "reload").assertSucceeded();
        List<String> second = drainConsole();
        assertTrue(second.stream().anyMatch(line -> line.contains("No world is bound")),
                "a repeated reload must repeat the answer: " + second);
        assertEquals(2, messagesAt(Level.SEVERE).size(),
                "an operator-initiated reload must re-report: " + messagesAt(Level.SEVERE));
    }

    @Test
    @DisplayName("the report is made once per configured name, not once per retry")
    void repeatedRetriesDoNotFloodTheLog() {
        SpiralGenesisPlugin plugin = load();
        configureWorld(plugin, "survival");

        plugin.initSpawnManager();
        plugin.initSpawnManager();

        assertEquals(1, messagesAt(Level.SEVERE).size(),
                "a retry per join must not repeat the error: " + messagesAt(Level.SEVERE));
    }
}
