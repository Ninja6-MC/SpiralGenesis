package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A player mock that can actually be scheduled on and whose teleport can be made to fail.
 *
 * <p>Two MockBukkit gaps, in one place because more than one suite hits both. Its entity
 * scheduler throws rather than scheduling, which is what forced {@code runForPlayer} to
 * become a seam in the first place - but that seam covers allocation only, and the teleport
 * re-assert, the reassign command and the revalidation repair all reach a player's scheduler
 * directly. And its {@code teleportAsync} returns {@code null} instead of a future, so the
 * resulting NullPointerException gets swallowed by the plugin's own error handling and the
 * test passes on a path that never finished.
 */
public class InlinePlayerMock extends PlayerMock {

    /**
     * Whether teleports succeed. Set false to reproduce a login plugin holding an
     * unauthenticated player, which is the only way to reach the teleport re-assert.
     */
    public boolean teleportSucceeds = true;

    public InlinePlayerMock(ServerMock server, String name) {
        super(server, name);
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location location,
                                                    PlayerTeleportEvent.TeleportCause cause,
                                                    TeleportFlag... flags) {
        if (!teleportSucceeds) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.completedFuture(teleport(location, cause));
    }

    @Override
    public EntityScheduler getScheduler() {
        return new InlineEntityScheduler();
    }

    /**
     * A task that has already run. Nothing in the plugin reads anything off one except to
     * cancel a repeating task, and none of the player-scheduled paths repeat.
     */
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

    /** Runs everything immediately, on the calling thread. */
    private static final class InlineEntityScheduler implements EntityScheduler {

        @Override
        public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay) {
            run.run();
            return true;
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired) {
            DoneTask handle = new DoneTask(plugin);
            task.accept(handle);
            return handle;
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
    }
}
