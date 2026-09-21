package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.listeners.PlayerSpawnListener;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when the respawn point a player died with no longer leads anywhere.
 *
 * <p>Folia never fires {@code PlayerRespawnEvent} for a death respawn. What it does fire,
 * inside the respawn and only when the point failed, is {@code PlayerSetSpawnEvent} with
 * cause {@code PLAYER_RESPAWN} and a null location. These tests drive that event, as the
 * server does, rather than calling into the plugin directly: an earlier version decided
 * at death time and lost the race against the respawn, and tests that completed inline
 * could not see it.
 *
 * <p>A working bed never produces that event, so it is never touched; the guards below
 * hold that line for the one place the plugin still writes a respawn point at death.
 */
class RespawnFallbackTest {

    private ServerMock server;
    private WorldMock world;
    private QuietManager manager;

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
     * has no {@code getPotentialBedLocation}. On the server the first resolves the point
     * and returns null for a bed that is gone, while honouring a forced point; the second
     * returns the stored coordinates without looking at any block. Both confirmed with
     * javap against CraftPlayer and CraftHumanEntity in paper 1.20.4 and folia 1.21.11.
     */
    static final class RespawnPlayer extends InlinePlayerMock {

        Location point;
        boolean forced;
        final java.util.List<Location> teleports = new ArrayList<>();
        /** On the death screen: set by a death, cleared once a respawn places them. */
        boolean dead;
        /** Tasks for the player's own thread, run by {@link #place} as the server would. */
        private final java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();

        RespawnPlayer(ServerMock server, String name) {
            super(server, name);
        }

        /**
         * The respawn has placed the player, so their scheduler runs again. Tasks queued
         * while running are run as well, as they would be on later ticks.
         */
        void place() {
            dead = false;
            tick();
        }

        /**
         * Runs the player's queued tasks without placing them, as their region does on
         * every tick while they sit on the death screen.
         */
        void tick() {
            Runnable next;
            while ((next = queued.poll()) != null) {
                next.run();
            }
        }

        @Override
        public boolean isDead() {
            return dead;
        }

        @Override
        public EntityScheduler getScheduler() {
            return new EntityScheduler() {
                @Override
                public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay) {
                    queued.add(run);
                    return true;
                }

                @Override
                public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task,
                                         Runnable retired) {
                    queued.add(() -> task.accept(null));
                    return new DoneTask(plugin);
                }

                @Override
                public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                                Runnable retired, long delayTicks) {
                    return run(plugin, task, retired);
                }

                @Override
                public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                                    Runnable retired, long initialDelayTicks,
                                                    long periodTicks) {
                    return run(plugin, task, retired);
                }
            };
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

        @Override
        public CompletableFuture<Boolean> teleportAsync(Location location,
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause,
                io.papermc.paper.entity.TeleportFlag... flags) {
            teleports.add(location.clone());
            return super.teleportAsync(location, cause, flags);
        }
    }

    /** A handle for a task that the queue owns; nothing in the plugin reads one. */
    private static final class DoneTask implements ScheduledTask {

        private final Plugin plugin;

        private DoneTask(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return false;
        }

        @Override
        public CancelledState cancel() {
            return CancelledState.ALREADY_EXECUTED;
        }

        @Override
        public ExecutionState getExecutionState() {
            return ExecutionState.FINISHED;
        }
    }

    /**
     * A manager that answers without reading a block: MockBukkit implements neither
     * {@code Block.isPassable}, async chunk loading nor the region schedulers. The in-cell
     * search never finishes, which leaves a repair in flight, as it would be for a while
     * on a real server.
     */
    private static final class QuietManager extends SpawnManager {

        SpawnVerdict verdict = SpawnVerdict.USABLE;
        /** What the asynchronous re-check reports, read when it is asked. */
        boolean plotSafe = true;
        /** Where the lift puts a player on the plot; null means the plot itself is clear. */
        Location lifted;
        /** The column above the plot has no clear, safe position. */
        boolean noStandingPoint;
        /** The in-cell search finds nothing, rather than staying in flight. */
        boolean cellHasNoPoint;

        private QuietManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
        }

        @Override
        public CompletableFuture<Boolean> revalidate(Location stored) {
            return CompletableFuture.completedFuture(plotSafe);
        }

        @Override
        public SpawnVerdict verifyStoredSpawn(Location stored) {
            return verdict;
        }

        @Override
        public CompletableFuture<Location> standingPoint(Location stored) {
            return CompletableFuture.completedFuture(standingPointNow(stored));
        }

        @Override
        public Location standingPointNow(Location stored) {
            if (noStandingPoint) {
                return null;
            }
            return lifted == null ? stored : lifted.clone();
        }

        @Override
        public CompletableFuture<LocationResult> findSafeSpawnInCell(int index) {
            return cellHasNoPoint ? CompletableFuture.completedFuture(null)
                    : new CompletableFuture<>();
        }
    }

    private SpiralGenesisPlugin load() {
        SpiralGenesisPlugin plugin = MockBukkit.load(SpiralGenesisPlugin.class);
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("allocation.action-timeout-seconds", 0);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        plugin.reload();
        manager = new QuietManager(plugin, world, plugin.getPluginConfig());
        bind(plugin, manager);
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

    /** The stored plot. Its blocks are never read: the manager's verdict is stubbed. */
    private Location plot() {
        return new Location(world, 10.5, 64, 10.5);
    }

    private RespawnPlayer join(SpiralGenesisPlugin plugin, Location plot) {
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

    private void die(RespawnPlayer player) {
        player.dead = true;
        server.getPluginManager().callEvent(
                new PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(),
                        new ArrayList<ItemStack>(), 0, (String) null));
    }

    /**
     * What the server does when the point a respawn goes through has failed: fire the
     * event with a null location, then store whatever the event ends up holding.
     */
    private PlayerSetSpawnEvent respawnPointFails(RespawnPlayer player) {
        PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(player,
                PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN, null, false, false, null);
        server.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            player.setRespawnLocation(event.getLocation(), event.isForced());
        }
        return event;
    }

    /** What Paper fires for a death respawn, before or after the event above by version. */
    private PlayerRespawnEvent paperRespawnEvent(RespawnPlayer player) {
        PlayerRespawnEvent respawn = new PlayerRespawnEvent(player,
                world.getSpawnLocation(), false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        server.getPluginManager().callEvent(respawn);
        return respawn;
    }

    private static void assertSameBlock(Location expected, Location actual) {
        assertNotNull(actual, "expected a location at " + expected);
        assertEquals(expected.getWorld(), actual.getWorld());
        assertEquals(expected.getBlockX(), actual.getBlockX());
        assertEquals(expected.getBlockY(), actual.getBlockY());
        assertEquals(expected.getBlockZ(), actual.getBlockZ());
    }

    @Test
    @DisplayName("a broken bed on Folia restores the plot as the respawn point and moves the player there")
    void brokenBedRestoresThePlot() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);
        bed.getBlock().setType(Material.AIR);

        die(player);
        // No PlayerRespawnEvent: Folia never fires one for a death respawn.
        respawnPointFails(player);
        assertTrue(player.teleports.isEmpty(), "nothing may move a player still in transit");
        player.place();

        assertSameBlock(plot, player.point);
        assertTrue(player.forced, "the plot is not a bed, so it only holds as a forced point");
        assertEquals(1, player.teleports.size(), "the respawn in progress already chose"
                + " world spawn, so the player has to be moved: " + player.teleports);
        assertSameBlock(plot, player.teleports.get(0));
    }

    @Test
    @DisplayName("a broken bed on Folia with the plot built over moves the player on top of the build")
    void brokenBedWithBuiltOverPlotLifts() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        Location top = plot.clone().add(0, 1, 0);
        manager.lifted = top;
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);
        bed.getBlock().setType(Material.AIR);

        die(player);
        respawnPointFails(player);
        player.place();

        assertEquals(1, player.teleports.size(), String.valueOf(player.teleports));
        assertSameBlock(top, player.teleports.get(0));
        assertSameBlock(plot, player.point);
    }

    @Test
    @DisplayName("on Folia, a flooded plot the server just rejected is kept as the point, and nobody is moved into it")
    void floodedPlotIsNotEntered() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        // Flooded: the forced plot fails the server's own check, so it is the point that failed.
        manager.plotSafe = false;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        PlayerSetSpawnEvent event = respawnPointFails(player);
        player.place();

        assertSameBlock(plot, event.getLocation());
        assertSameBlock(plot, player.point);
        assertTrue(player.teleports.isEmpty(), "moving them into it is the defect: "
                + player.teleports);
    }

    @Test
    @DisplayName("on Folia, a plot the owner built over lifts them on top of the build")
    void builtOverPlotLiftsThePlayer() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        // Built over: safe, but solid at the feet, so the server declined the forced point
        // and placed them at world spawn. The first clear position is two blocks up.
        Location top = plot.clone().add(0, 2, 0);
        manager.lifted = top;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        PlayerSetSpawnEvent event = respawnPointFails(player);
        assertTrue(player.teleports.isEmpty(), "nothing may move a player still in transit");
        player.place();

        assertEquals(1, player.teleports.size(), "the respawn chose world spawn: "
                + player.teleports);
        assertSameBlock(top, player.teleports.get(0));
        assertSameBlock(plot, event.getLocation());
        assertSameBlock(plot, player.point);
        assertTrue(player.forced);
        assertSameBlock(plot, plugin.getDataStorage().getRecord(player.getUniqueId())
                .toLocation());
    }

    @Test
    @DisplayName("on Folia, a built-over plot with no clear position above it leaves the player where they respawned")
    void builtOverPlotWithNoStandingPointHoldsThePlayer() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        manager.noStandingPoint = true;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        respawnPointFails(player);
        player.place();

        assertTrue(player.teleports.isEmpty(), "there is nowhere safe to put them: "
                + player.teleports);
        assertSameBlock(plot, player.point);
        assertSameBlock(plot, plugin.getDataStorage().getRecord(player.getUniqueId())
                .toLocation());
    }

    @Test
    @DisplayName("on Paper, a built-over plot respawns the player at the lifted position")
    void paperBuiltOverPlotRespawnsLifted() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        Location top = plot.clone().add(0, 2, 0);
        manager.lifted = top;
        RespawnPlayer player = join(plugin, plot);

        // Paper 1.21.11 and later place the player at the event's location exactly, with
        // no suffocation lift of their own, so the lift has to be in the location.
        die(player);
        PlayerRespawnEvent respawn = paperRespawnEvent(player);
        respawnPointFails(player);
        player.place();

        assertSameBlock(top, respawn.getRespawnLocation());
        assertTrue(player.teleports.isEmpty(), "placed correctly, so nothing to move: "
                + player.teleports);
        assertSameBlock(plot, player.point);
        assertSameBlock(plot, plugin.getDataStorage().getRecord(player.getUniqueId())
                .toLocation());
    }

    @Test
    @DisplayName("on Paper, a built-over plot capped with something harmful holds the player at world spawn")
    void paperPlotWithHarmfulTopHoldsAtWorldSpawn() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        // Cobblestone at feet and head with magma on top: the plot is safe, the only clear
        // position above it is not.
        manager.noStandingPoint = true;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        PlayerRespawnEvent respawn = paperRespawnEvent(player);
        respawnPointFails(player);
        player.place();

        assertSameBlock(world.getSpawnLocation(), respawn.getRespawnLocation());
        assertTrue(player.teleports.isEmpty(), String.valueOf(player.teleports));
        assertSameBlock(plot, player.point);
    }

    @Test
    @DisplayName("on Paper, a built-over plot that could not be checked inline is lifted once the player is placed")
    void paperUnverifiedBuiltOverPlotIsLiftedAfterPlacement() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        Location top = plot.clone().add(0, 1, 0);
        manager.lifted = top;
        manager.verdict = SpawnManager.SpawnVerdict.UNVERIFIED;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        PlayerRespawnEvent respawn = paperRespawnEvent(player);
        respawnPointFails(player);
        player.place();

        assertSameBlock(plot, respawn.getRespawnLocation());
        assertEquals(1, player.teleports.size(), String.valueOf(player.teleports));
        assertSameBlock(top, player.teleports.get(0));
    }

    @Test
    @DisplayName("on Paper, an unchecked plot with no clear position above it sends the player to world spawn once placed")
    void paperUnverifiedPlotWithNoStandingPointMovesToWorldSpawn() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        manager.noStandingPoint = true;
        manager.verdict = SpawnManager.SpawnVerdict.UNVERIFIED;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        paperRespawnEvent(player);
        player.place();

        assertEquals(1, player.teleports.size(), String.valueOf(player.teleports));
        assertSameBlock(world.getSpawnLocation(), player.teleports.get(0));
    }

    @Test
    @DisplayName("on Paper, an unchecked plot that is clear is not moved again")
    void paperUnverifiedClearPlotIsNotMoved() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        manager.verdict = SpawnManager.SpawnVerdict.UNVERIFIED;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        PlayerRespawnEvent respawn = paperRespawnEvent(player);
        player.place();

        assertSameBlock(plot, respawn.getRespawnLocation());
        assertTrue(player.teleports.isEmpty(), String.valueOf(player.teleports));
    }

    @Test
    @DisplayName("a plot that is unsafe by the time the player is placed stops the move")
    void plotUnsafeAtTeleportTimeIsNotEntered() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);
        bed.getBlock().setType(Material.AIR);

        die(player);
        respawnPointFails(player);
        manager.plotSafe = false;
        player.place();

        assertTrue(player.teleports.isEmpty(), "the re-check must hold them where the respawn"
                + " placed them: " + player.teleports);
    }

    @Test
    @DisplayName("on Paper 1.21.11, respawn event first, the routed respawn is not moved again")
    void paperNewOrderIsNotMovedTwice() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        // Griefed: the respawn handler holds the player at world spawn while it repairs.
        manager.verdict = SpawnManager.SpawnVerdict.UNSAFE;
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);
        bed.getBlock().setType(Material.AIR);

        die(player);
        PlayerRespawnEvent respawn = paperRespawnEvent(player);
        respawnPointFails(player);
        player.place();

        assertFalse(sameBlock(plot, respawn.getRespawnLocation()),
                "precondition: the respawn handler kept the player off the unsafe plot");
        assertTrue(player.teleports.isEmpty(),
                "moving them onto the plot would undo that: " + player.teleports);
        assertSameBlock(plot, player.point);
    }

    @Test
    @DisplayName("on Paper 1.20.4, respawn event last, the routed respawn is not moved again")
    void paperOldOrderIsNotMovedTwice() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        manager.verdict = SpawnManager.SpawnVerdict.UNSAFE;
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);
        bed.getBlock().setType(Material.AIR);

        die(player);
        respawnPointFails(player);
        PlayerRespawnEvent respawn = paperRespawnEvent(player);
        player.place();

        assertFalse(sameBlock(plot, respawn.getRespawnLocation()),
                "precondition: the respawn handler kept the player off the unsafe plot");
        assertTrue(player.teleports.isEmpty(),
                "the mark is set after the teleport was scheduled, and must still stop it: "
                        + player.teleports);
    }

    @Test
    @DisplayName("a player whose bed still stands keeps it after dying")
    void workingBedIsKept() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);

        // A bed that works never makes the server clear the point, so death is all there is.
        die(player);
        player.place();

        assertSameBlock(bed, player.getRespawnLocation());
        assertFalse(player.forced, "the bed must be left exactly as the player set it");
        assertTrue(player.teleports.isEmpty());
    }

    @Test
    @DisplayName("a forced respawn point set elsewhere, such as by /spawnpoint, is kept")
    void forcedPointElsewhereIsKept() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        RespawnPlayer player = join(plugin, plot);
        Location elsewhere = new Location(world, -300, 80, 40);
        player.setRespawnLocation(elsewhere, true);

        die(player);

        assertSameBlock(elsewhere, player.getRespawnLocation());
        assertTrue(player.teleports.isEmpty());
    }

    @Test
    @DisplayName("a player with no respawn point at all has the plot restored when they die")
    void missingPointIsRestoredAtDeath() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        RespawnPlayer player = join(plugin, plot);
        player.setRespawnLocation(null, false);

        // Folia sends a player with no point to world spawn without firing anything, so
        // death is the only chance and the point has to be in place when it returns.
        die(player);

        assertSameBlock(plot, player.getRespawnLocation());
        assertTrue(player.forced);
    }

    /**
     * Draws the world border in around a box far from the plot, so the plot's whole cell
     * is outside it. The re-check the repair runs is stubbed separately, since the border
     * test at death is the manager's own and reads the world's real border.
     */
    private void shrinkBorderAwayFromPlot() {
        world.getWorldBorder().setCenter(10_000, 10_000);
        world.getWorldBorder().setSize(16);
        manager.plotSafe = false;
        manager.cellHasNoPoint = true;
    }

    @Test
    @DisplayName("on Folia, a plot whose whole cell is outside the border is not respawned onto, and comes back with the border")
    void foliaPlotOutsideTheBorderHoldsAtWorldSpawn() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        RespawnPlayer player = join(plugin, plot);
        shrinkBorderAwayFromPlot();

        // Folia accepts the forced plot on its feet and head blocks and never looks at the
        // border, and fires neither respawn event for it. So the point has to be gone by
        // the time the respawn reads it, which is before the repair can answer.
        die(player);
        assertNull(player.point, "the plot must not be the respawn point for this death");

        // The repair finds nothing in a cell entirely outside the border, while the player
        // is still on the death screen.
        player.tick();
        // No point, so Folia respawns them at world spawn without firing anything.
        player.place();

        assertNull(player.point);
        assertTrue(player.teleports.isEmpty(), String.valueOf(player.teleports));
        assertSameBlock(plot, plugin.getDataStorage().getRecord(player.getUniqueId())
                .toLocation());

        // The border grows back: the next death restores the plot as the point.
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(1000);
        manager.plotSafe = true;
        die(player);

        assertSameBlock(plot, player.point);
        assertTrue(player.forced);
    }

    @Test
    @DisplayName("a plot outside the border does not take away a bed")
    void bedIsKeptWhenThePlotIsOutsideTheBorder() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        RespawnPlayer player = join(plugin, plot);
        Location bed = sleepInBed(player);
        shrinkBorderAwayFromPlot();

        die(player);
        player.tick();
        player.place();

        assertSameBlock(bed, player.point);
        assertFalse(player.forced);
        assertTrue(player.teleports.isEmpty(), String.valueOf(player.teleports));
    }

    @Test
    @DisplayName("on Folia, a repair that finds nothing while the player is dead clears the plot as their point")
    void failedRepairWhileDeadClearsThePlotPoint() {
        SpiralGenesisPlugin plugin = load();
        Location plot = plot();
        // Inside the border, but dug out, and no sampled candidate in the cell passes.
        manager.plotSafe = false;
        manager.cellHasNoPoint = true;
        RespawnPlayer player = join(plugin, plot);

        die(player);
        assertSameBlock(plot, player.point);
        player.tick();

        assertNull(player.point, "a forced point on the plot would put them back in the hole");
        assertTrue(player.teleports.isEmpty(), "nobody on the death screen is teleported: "
                + player.teleports);
        assertSameBlock(plot, plugin.getDataStorage().getRecord(player.getUniqueId())
                .toLocation());
    }

    @Test
    @DisplayName("a player with no plot is left to the server")
    void noPlotLeavesTheEventAlone() {
        load();
        RespawnPlayer player = new RespawnPlayer(server, "Alice");
        server.addPlayer(player);

        PlayerSetSpawnEvent event = respawnPointFails(player);
        player.place();

        assertNull(event.getLocation());
        assertTrue(player.teleports.isEmpty());
    }

    @Test
    @DisplayName("a player who quits after a respawn leaves nothing behind")
    void quitForgetsTheRespawn() {
        SpiralGenesisPlugin plugin = load();
        RespawnPlayer player = join(plugin, plot());

        // A respawn whose point did not fail: nothing consumes the entry it records.
        die(player);
        paperRespawnEvent(player);
        player.place();
        assertTrue(respawnEventSeen(plugin).contains(player.getUniqueId()));

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, "left"));

        assertFalse(respawnEventSeen(plugin).contains(player.getUniqueId()));
    }

    /** The listener's record of routed respawns, which has no accessor to read it by. */
    @SuppressWarnings("unchecked")
    private static Set<UUID> respawnEventSeen(SpiralGenesisPlugin plugin) {
        for (RegisteredListener registered
                : PlayerQuitEvent.getHandlerList().getRegisteredListeners()) {
            if (registered.getPlugin() == plugin
                    && registered.getListener() instanceof PlayerSpawnListener listener) {
                try {
                    Field field = PlayerSpawnListener.class.getDeclaredField("respawnEventSeen");
                    field.setAccessible(true);
                    return (Set<UUID>) field.get(listener);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("PlayerSpawnListener is not registered for quits");
    }

    private static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
