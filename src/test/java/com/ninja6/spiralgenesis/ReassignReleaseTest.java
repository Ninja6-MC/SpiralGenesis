package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.CellReserver;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.protection.ProtectionProvider;
import com.ninja6.spiralgenesis.protection.RecordingProvider;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A reassignment dropped because its player left hands its cell back.
 *
 * <p>The cell the scan settled on stays reserved until it is written or released. A
 * reassignment that is never applied writes nothing, so unless it releases the cell, that
 * ground stays closed to every other centre until a restart.
 */
class ReassignReleaseTest {

    private ServerMock server;

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
            // cancel; see AllocationOwnershipTest.
            MockBukkit.unmock();
        }
    }

    /** How the target's entity scheduler treats the reassignment's task. */
    enum Departure {
        /** The task runs, and finds the player gone. */
        OFFLINE_IN_TASK,
        /** The player is retired before the task runs. */
        RETIRED,
        /** The scheduler refuses the task outright. */
        REFUSED
    }

    /** A player whose scheduler departs the way the test says. */
    static final class LeavingPlayer extends InlinePlayerMock {

        private final Departure departure;

        /** Set once the reassignment is about to run; the join before it is ordinary. */
        volatile boolean armed;

        /** Set as the task starts, for {@link Departure#OFFLINE_IN_TASK}. */
        private volatile boolean gone;

        LeavingPlayer(ServerMock server, String name, Departure departure) {
            super(server, name);
            this.departure = departure;
        }

        @Override
        public boolean isOnline() {
            return !gone && super.isOnline();
        }

        @Override
        public EntityScheduler getScheduler() {
            EntityScheduler inline = super.getScheduler();
            return new EntityScheduler() {
                @Override
                public boolean execute(Plugin plugin, Runnable run, Runnable retired,
                                       long delay) {
                    return inline.execute(plugin, run, retired, delay);
                }

                @Override
                public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task,
                                         Runnable retired) {
                    if (!armed) {
                        return inline.run(plugin, task, retired);
                    }
                    return switch (departure) {
                        case OFFLINE_IN_TASK -> {
                            gone = true;
                            yield inline.run(plugin, task, retired);
                        }
                        case RETIRED -> {
                            retired.run();
                            yield inline.run(plugin, ignored -> { }, null);
                        }
                        case REFUSED -> null;
                    };
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
    }

    /** Reserves through the storage it is given, as the real scan does, without a chunk. */
    private static final class StubSpawnManager extends SpawnManager {

        private final PluginConfig config;

        /** The cell the last reassignment settled on. */
        private SpiralCell reserved;

        private StubSpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
            this.config = config;
        }

        @Override
        public CompletableFuture<AllocationOutcome> allocateNextSafeSpawn(CellReserver cells) {
            SpiralCell cell = cells.reserve(config);
            reserved = cell;
            Location at = new Location(Bukkit.getWorlds().get(0), cell.centreX() + 0.5, 64,
                    cell.centreZ() + 0.5);
            return CompletableFuture.completedFuture(new LocationResult(at, cell.index(),
                    cell.grid()[0], cell.grid()[1], 63, 1, 1, false, Map.of(),
                    cell.centre().id()));
        }
    }

    public static class ReleasePlugin extends SpiralGenesisPlugin {

        final ProtectionProvider provider = new RecordingProvider();
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
    }

    @ParameterizedTest
    @EnumSource(Departure.class)
    void droppedReassignmentReleasesItsCell(Departure departure) {
        ReleasePlugin plugin = MockBukkit.loadWith(ReleasePlugin.class,
                getClass().getResourceAsStream("/plugin.yml"));
        PluginConfig config = plugin.getPluginConfig();
        LeavingPlayer player = new LeavingPlayer(server, "Bob", departure);
        server.addPlayer(player);
        player.armed = true;

        server.executeConsole("sgen", "reassign", "Bob").assertSucceeded();

        assertFalse(plugin.getDataStorage().hasSpawn(player.getUniqueId()),
                "nothing is recorded for a player who left");
        // Half a cell east of the cell the dropped reassignment settled on, so this centre's
        // cell 0 covers half of it and nothing else in flight. Were that cell still
        // reserved, index 0 would be skipped.
        SpiralCell dropped = plugin.stub.reserved;
        assertNotNull(dropped, "the reassignment must have reserved a cell");
        SpiralCell after = plugin.getDataStorage().reserveCell(
                dropped.centreX() + config.getCellSize() / 2, dropped.centreZ(),
                config.getCellSize());
        assertNotNull(after);
        assertEquals(0, after.index(), "the dropped cell must no longer block other centres");
    }
}
