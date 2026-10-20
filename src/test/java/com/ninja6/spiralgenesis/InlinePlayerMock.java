package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.ServerMock;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
public class InlinePlayerMock extends SessionPlayerMock {

    /**
     * Whether teleports succeed. Set false to reproduce a login plugin holding an
     * unauthenticated player, which is the only way to reach the teleport re-assert.
     */
    public boolean teleportSucceeds = true;

    /** The stored respawn point, as the server keeps it: unresolved, and null when unset. */
    public Location respawnPoint;

    /** Every action bar sent, as plain text, oldest first. */
    public final List<String> actionBars = new ArrayList<>();

    /** Repeating tasks, which run only when {@link #runRepeating} says a period passed. */
    private final List<RepeatingTask> repeating = new ArrayList<>();

    public InlinePlayerMock(ServerMock server, String name) {
        super(server, name);
    }

    /** The same player again, as a new entity: a rejoin keeps the UUID and nothing else. */
    public InlinePlayerMock(ServerMock server, String name, UUID uuid) {
        super(server, name, uuid);
    }

    /**
     * MockBukkit has no {@code getPotentialBedLocation}, which the revalidation repair reads,
     * so the point is kept here and both getters return it unchecked.
     */
    @Override
    public void setRespawnLocation(Location location, boolean force) {
        respawnPoint = location == null ? null : location.clone();
    }

    @Override
    public Location getRespawnLocation() {
        return respawnPoint == null ? null : respawnPoint.clone();
    }

    @Override
    public Location getPotentialBedLocation() {
        return getRespawnLocation();
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
    public void sendActionBar(Component message) {
        actionBars.add(PlainTextComponentSerializer.plainText().serialize(message));
    }

    @Override
    public EntityScheduler getScheduler() {
        return new InlineEntityScheduler();
    }

    /** Runs each live repeating task once, as one period passing. */
    public void runRepeating() {
        for (RepeatingTask task : List.copyOf(repeating)) {
            if (!task.cancelled) {
                task.body.accept(task);
            }
        }
        repeating.removeIf(task -> task.cancelled);
    }

    /** How many repeating tasks are still scheduled. */
    public int repeatingTasks() {
        repeating.removeIf(task -> task.cancelled);
        return repeating.size();
    }

    private static final class RepeatingTask implements ScheduledTask {

        private final Plugin plugin;
        private final Consumer<ScheduledTask> body;
        private boolean cancelled;

        private RepeatingTask(Plugin plugin, Consumer<ScheduledTask> body) {
            this.plugin = plugin;
            this.body = body;
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return true;
        }

        @Override
        public CancelledState cancel() {
            if (cancelled) {
                return CancelledState.CANCELLED_ALREADY;
            }
            cancelled = true;
            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public ExecutionState getExecutionState() {
            return cancelled ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }

    /** A task that has already run. */
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

    /** Runs everything once immediately, on the calling thread, except repeating tasks. */
    private final class InlineEntityScheduler implements EntityScheduler {

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
            RepeatingTask handle = new RepeatingTask(plugin, task);
            repeating.add(handle);
            return handle;
        }
    }
}
