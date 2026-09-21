package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a death does to a respawn point that no longer matches the plot.
 *
 * <p>Folia never fires {@code PlayerRespawnEvent} for a death respawn, so the death-time
 * re-check is the only place a player whose bed was broken can be sent back to their plot.
 * The same re-check must never touch a bed that still works: a player who sleeps at a base
 * away from their plot would otherwise lose that bed on every death, on both platforms.
 */
class RespawnFallbackTest {

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
            // cancel. Swallowed for the reason AllocationOwnershipTest documents: the
            // exception extends TestAbortedException, so letting it out of teardown reports
            // every test in the class as skipped rather than run.
            MockBukkit.unmock();
        }
    }

    /**
     * A player whose respawn point behaves as the server's does.
     *
     * <p>MockBukkit returns the stored point from {@code getRespawnLocation} unchecked and
     * has no {@code getPotentialBedLocation} at all. On the server the first resolves the
     * point and returns null for a bed that is gone, while honouring a forced point; the
     * second returns the stored coordinates without looking at any block. Both confirmed
     * with javap against CraftPlayer and CraftHumanEntity in paper 1.20.4 and folia 1.21.11.
     */
    static final class RespawnPlayer extends InlinePlayerMock {

        Location point;
        boolean forced;

        RespawnPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void setRespawnLocation(Location location, boolean force) {
            point = location == null ? null : location.clone();
            forced = force;
        }

        @Override
        public Location getPotentialBedLocation() {
            return point == null ? null : point.clone();
        }

        @Override
        public Location getRespawnLocation() {
            if (point == null) {
                return null;
            }
            boolean bed = Tag.BEDS.isTagged(point.getBlock().getType());
            return bed || forced ? point.clone() : null;
        }
    }

    /** A manager whose re-check always finds the plot safe, without loading a chunk. */
    private static final class SafePlotManager extends SpawnManager {

        private SafePlotManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
        }

        @Override
        public CompletableFuture<Boolean> revalidate(Location stored) {
            return CompletableFuture.completedFuture(true);
        }
    }

    /** Resolves the respawn point inline; MockBukkit has no async chunks or regions. */
    public static class RespawnPlugin extends SpiralGenesisPlugin {

        @Override
        CompletableFuture<Boolean> respawnPointHolds(Player player, Location point) {
            return CompletableFuture.completedFuture(player.getRespawnLocation() != null);
        }
    }

    private RespawnPlugin load() {
        RespawnPlugin plugin = MockBukkit.loadWith(RespawnPlugin.class,
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
        bind(plugin, new SafePlotManager(plugin, world, plugin.getPluginConfig()));
        return plugin;
    }

    /**
     * Replaces the bound manager. The field has no setter, and adding one to production
     * code for a test would widen the class every reload path has to reason about.
     */
    private static void bind(SpiralGenesisPlugin plugin, SpawnManager manager) {
        try {
            Field field = SpiralGenesisPlugin.class.getDeclaredField("spawnManager");
            field.setAccessible(true);
            field.set(plugin, manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private RespawnPlayer join(RespawnPlugin plugin, Location plot) {
        RespawnPlayer player = new RespawnPlayer(server, "Bob");
        server.addPlayer(player);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), plot, 3, 0, 0, "Bob", "JAVA");
        // Allocation forces the respawn point onto the plot.
        player.setRespawnLocation(plot, true);
        return player;
    }

    /** Places a bed and sleeps in it, which replaces the forced point with an unforced one. */
    private Location sleepInBed(RespawnPlayer player) {
        Location bed = new Location(world, 200, 70, 200);
        bed.getBlock().setType(Material.RED_BED);
        player.setRespawnLocation(bed, false);
        return bed;
    }

    private void die(RespawnPlugin plugin, Player player) {
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record);
        // What PlayerSpawnListener.onPlayerDeath calls.
        plugin.repairSpawn(player, record, true);
    }

    private static void assertAt(Location expected, Location actual) {
        assertNotNull(actual, "the player must still have a respawn point");
        assertEquals(expected.getBlockX(), actual.getBlockX());
        assertEquals(expected.getBlockY(), actual.getBlockY());
        assertEquals(expected.getBlockZ(), actual.getBlockZ());
    }

    @Test
    @DisplayName("a player whose bed was broken respawns at their plot, not world spawn")
    void brokenBedFallsBackToThePlot() {
        RespawnPlugin plugin = load();
        Location plot = new Location(world, 10, 64, 10);
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);
        bed.getBlock().setType(Material.AIR);

        die(plugin, player);

        assertAt(plot, player.getRespawnLocation());
        assertTrue(player.forced, "the plot is not a bed, so it only holds as a forced point");
    }

    @Test
    @DisplayName("a player whose bed still stands keeps it after dying")
    void workingBedIsKept() {
        RespawnPlugin plugin = load();
        Location plot = new Location(world, 10, 64, 10);
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);

        die(plugin, player);

        assertAt(bed, player.getRespawnLocation());
        assertEquals(false, player.forced, "the bed must be left exactly as the player set it");
    }

    @Test
    @DisplayName("a forced respawn point set elsewhere, such as by /spawnpoint, is kept")
    void forcedPointElsewhereIsKept() {
        RespawnPlugin plugin = load();
        Location plot = new Location(world, 10, 64, 10);
        RespawnPlayer player = join(plugin, plot);
        Location elsewhere = new Location(world, -300, 80, 40);
        player.setRespawnLocation(elsewhere, true);

        die(plugin, player);

        assertAt(elsewhere, player.getRespawnLocation());
    }

    @Test
    @DisplayName("a player left with no respawn point at all is pointed back at their plot")
    void missingPointFallsBackToThePlot() {
        RespawnPlugin plugin = load();
        Location plot = new Location(world, 10, 64, 10);
        RespawnPlayer player = join(plugin, plot);
        player.setRespawnLocation(null, false);

        die(plugin, player);

        assertAt(plot, player.getRespawnLocation());
    }
}
