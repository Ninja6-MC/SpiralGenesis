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
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
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
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three settled behaviours that live in the command layer, tested at the level they
 * were specified rather than one below it.
 *
 * <p>{@code /sgen setspawn}, {@code /sgen reassign} with and without its release token, and
 * the revalidation repair each have a defined answer about the claim left behind, and the
 * reassign default is required to be <em>incapable</em> of deleting anything. Asserting that
 * against {@code SpawnProtector} alone would leave the argument parsing that decides which
 * of the two paths runs completely untested, which is exactly where a default could flip.
 */
class SpiralCommandProtectionTest {

    private ServerMock server;
    private WorldMock world;

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
            // cancel. Swallowed for the reason AllocationOwnershipTest documents: this
            // exception extends TestAbortedException, so letting it out of teardown reports
            // every test in the class as skipped rather than run.
            MockBukkit.unmock();
        }
    }

    /** A spawn manager that hands back a fixed point without touching a chunk. */
    private static final class StubSpawnManager extends SpawnManager {

        private Location next;
        private int index = 7;

        private StubSpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
        }

        private SpawnManager.LocationResult result() {
            return new SpawnManager.LocationResult(next, index, 0, 0, 63, 1, 1, false, Map.of());
        }

        @Override
        public CompletableFuture<LocationResult> allocateNextSafeSpawn(IntSupplier indexSupplier) {
            index = indexSupplier.getAsInt();
            return CompletableFuture.completedFuture(result());
        }

        @Override
        public CompletableFuture<LocationResult> findSafeSpawnInCell(int cellIndex) {
            index = cellIndex;
            return CompletableFuture.completedFuture(result());
        }
    }

    /** Injects the provider and the stub search, and nothing else. */
    public static class CommandPlugin extends SpiralGenesisPlugin {

        ProtectionProvider provider = new RecordingProvider();
        StubSpawnManager stub;

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
        public CompletableFuture<SpawnManager.LocationResult> searchInCell(int index) {
            return getSpawnManager().findSafeSpawnInCell(index);
        }
    }

    private CommandPlugin load() {
        CommandPlugin plugin = MockBukkit.loadWith(CommandPlugin.class,
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
        return plugin;
    }

    private InlinePlayerMock join(String name) {
        InlinePlayerMock player = new InlinePlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    /** Everything the console was told since it was last drained. */
    private List<String> drain() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = console.nextMessage()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private boolean saidSomethingContaining(List<String> lines, String fragment) {
        return lines.stream().anyMatch(line -> line.contains(fragment));
    }

    @Test
    @DisplayName("setspawn claims the new point and leaves the old claim, naming where it is")
    void setspawnClaimsAndReportsTheStaleClaim() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        join("Bob");

        server.executeConsole("sgen", "setspawn", "Bob", "10", "64", "10").assertSucceeded();
        drain();

        assertEquals(1, provider.reservations.size());
        assertEquals(10, provider.reservations.get(0).centre().getBlockX());

        // Moving it again is what produces a stale claim to report.
        server.executeConsole("sgen", "setspawn", "Bob", "200", "70", "200").assertSucceeded();
        List<String> said = drain();

        assertEquals(2, provider.reservations.size());
        assertEquals(200, provider.reservations.get(1).centre().getBlockX());
        assertTrue(provider.releases.isEmpty(), "setspawn must never delete a claim");
        assertTrue(saidSomethingContaining(said, "still standing"), said.toString());
        assertTrue(saidSomethingContaining(said, "10, 64, 10"),
                "the operator has to be told where the stale claim is: " + said);
    }

    @Test
    @DisplayName("reassign without the token claims the new plot and deletes nothing")
    void reassignWithoutTheTokenDeletesNothing() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 10, 64, 10),
                1, 0, 0, "Bob", "JAVA");
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 900, 64, 900);

        server.executeConsole("sgen", "reassign", "Bob").assertSucceeded();
        List<String> said = drain();

        assertEquals(1, provider.reservations.size());
        assertEquals(900, provider.reservations.get(0).centre().getBlockX(),
                "the new plot is what gets claimed");
        assertTrue(provider.releases.isEmpty(),
                "the default reassign must be incapable of deleting anything");
        assertTrue(saidSomethingContaining(said, "still standing"), said.toString());
        assertTrue(saidSomethingContaining(said, "release"),
                "the way to remove it belongs in the output: " + said);
    }

    @Test
    @DisplayName("reassign with the release token releases the old claim, and only the old one")
    void reassignWithTheTokenReleasesTheOldClaim() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 10, 64, 10),
                1, 0, 0, "Bob", "JAVA");
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 900, 64, 900);

        server.executeConsole("sgen", "reassign", "Bob", "release").assertSucceeded();
        List<String> said = drain();

        assertEquals(1, provider.reservations.size());
        assertEquals(900, provider.reservations.get(0).centre().getBlockX());
        assertEquals(1, provider.releases.size(), "the token must reach the release");
        assertEquals(10, provider.releases.get(0).centre().getBlockX(),
                "and it must be the old point that is released, not the new one");
        assertTrue(saidSomethingContaining(said, "Released"), said.toString());
    }

    @Test
    @DisplayName("a mistyped third argument is refused rather than ignored")
    void aMistypedTokenIsRefused() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        Location before = new Location(world, 10, 64, 10);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), before, 1, 0, 0, "Bob", "JAVA");

        server.executeConsole("sgen", "reassign", "Bob", "relase");
        List<String> said = drain();

        assertTrue(saidSomethingContaining(said, "Unknown argument"), said.toString());
        assertTrue(provider.reservations.isEmpty(), "nothing should have been reassigned");
        assertTrue(provider.releases.isEmpty(), "and certainly nothing deleted");
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record);
        assertEquals(10, (int) record.x(), "the player must not have been moved");
    }

    @Test
    @DisplayName("a revalidation move claims the point the player actually ends up on")
    void revalidationClaimsTheReplacementPoint() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        Location unsafe = new Location(world, 10, 64, 10);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), unsafe, 3, 0, 0, "Bob", "JAVA");
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 44, 64, 44);
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());

        // confirmFirst false, which is the branch the respawn handler takes when it has
        // already judged the stored point unsafe inline.
        plugin.repairSpawn(player, record, false);

        assertEquals(1, provider.reservations.size());
        assertEquals(44, provider.reservations.get(0).centre().getBlockX(),
                "the player must be protected where they actually spawn, not where they did");
        assertTrue(provider.releases.isEmpty(),
                "revalidation leaves the old claim standing, like every other path");
        assertEquals(44, (int) plugin.getDataStorage().getRecord(player.getUniqueId()).x());
    }
}
