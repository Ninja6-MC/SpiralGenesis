package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        SpiralGenesisPlugin plugin = MockBukkit.load(SpiralGenesisPlugin.class);
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

    /** Points {@code origin.world} at {@code name} and reloads, as an operator edit would. */
    private void configureWorld(SpiralGenesisPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("origin.world", name);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        plugin.reload();
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
        assertTrue(severe.get(0).contains("world"),
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
