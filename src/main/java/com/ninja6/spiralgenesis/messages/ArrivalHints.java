package com.ninja6.spiralgenesis.messages;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.protection.ClaimInspection;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The action-bar hint shown while a player stands on the plot they have just arrived at.
 *
 * <p>One hint per player: a new arrival replaces a running one. It is re-sent about once a
 * second on the player's own scheduler, stops a short while after the player first leaves
 * the block column they arrived in, and never runs past its cap. Quitting, dying and
 * changing world end it at once.
 *
 * <p>The claim part, the investigation tip and the drawn edges come only from an inspection
 * that found a claim of the player's, so the hint never implies protection they lack.
 */
public final class ArrivalHints implements Listener {
    private static final long PERIOD_TICKS = 20L;

    private final SpiralGenesisPlugin plugin;
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    private static final class Running {
        final ArrivalHint hint;
        final StoredSpawn record;
        final boolean edges;
        volatile Component text;
        volatile ScheduledTask task;

        Running(ArrivalHint hint, StoredSpawn record, boolean edges) {
            this.hint = hint;
            this.record = record;
            this.edges = edges;
        }
    }

    public ArrivalHints(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the hint for an arrival at the player's stored plot. Call once the player is
     * there: after a teleport that completed, or once a respawn has placed them.
     */
    public void arrived(Player player) {
        if (player == null) {
            return;
        }
        schedule(player, () -> begin(player));
    }

    /** Whether a hint is currently running for the player. */
    public boolean isRunning(UUID player) {
        return running.containsKey(player);
    }

    /** Ends the player's hint, if one is running. */
    public void cancel(UUID player) {
        Running hint = running.remove(player);
        if (hint != null) {
            stopTask(hint);
        }
    }

    /** Ends every running hint; for plugin shutdown. */
    public void cancelAll() {
        for (UUID player : List.copyOf(running.keySet())) {
            cancel(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        cancel(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    private void begin(Player player) {
        UUID uuid = player.getUniqueId();
        cancel(uuid);
        FileConfiguration config = plugin.getConfig();
        if (!player.isOnline() || !config.getBoolean("messages.arrival.enabled", true)
                || plugin.storageFailureNotice() != null) {
            return;
        }
        StoredSpawn record = plugin.getDataStorage().getRecord(uuid);
        if (record == null) {
            return;
        }
        Running hint = new Running(new ArrivalHint(player.getLocation(),
                config.getInt("messages.arrival.linger-seconds", 5),
                config.getInt("messages.arrival.max-seconds", 30)),
                record, config.getBoolean("messages.arrival.show-claim-edges", true));
        running.put(uuid, hint);
        plugin.inspectPlot(record, uuid, claim -> schedule(player, () -> show(player, hint, claim)));
    }

    private void show(Player player, Running hint, ClaimInspection claim) {
        UUID uuid = player.getUniqueId();
        if (running.get(uuid) != hint) {
            return;
        }
        boolean theirs = claim.status() == ClaimInspection.Status.FOUND;
        Component text = text(hint.record, claim, theirs);
        if (text == null) {
            stop(uuid, hint);
            return;
        }
        hint.text = text;
        if (!refresh(player, hint)) {
            return;
        }
        if (theirs && hint.edges) {
            plugin.showClaimEdges(hint.record, player);
        }
        ScheduledTask task;
        try {
            task = player.getScheduler().runAtFixedRate(plugin, repeat -> {
                if (!refresh(player, hint)) {
                    repeat.cancel();
                }
            }, () -> running.remove(uuid, hint), PERIOD_TICKS, PERIOD_TICKS);
        } catch (Throwable retired) {
            task = null;
        }
        if (task == null) {
            running.remove(uuid, hint);
            return;
        }
        hint.task = task;
        if (running.get(uuid) != hint) {
            task.cancel();
        }
    }

    /** One refresh of a running hint; false once it has ended. */
    private boolean refresh(Player player, Running hint) {
        UUID uuid = player.getUniqueId();
        if (running.get(uuid) != hint) {
            return false;
        }
        // Storage failing, or the plot being moved by a command, ends the hint: it would
        // otherwise go on naming a plot the player no longer has.
        if (!player.isOnline() || plugin.storageFailureNotice() != null
                || !Objects.equals(hint.record, plugin.getDataStorage().getRecord(uuid))
                || !hint.hint.refresh(player.getLocation())) {
            stop(uuid, hint);
            return false;
        }
        player.sendActionBar(hint.text);
        return true;
    }

    private void stop(UUID uuid, Running hint) {
        running.remove(uuid, hint);
        stopTask(hint);
    }

    private static void stopTask(Running hint) {
        ScheduledTask task = hint.task;
        if (task != null) {
            task.cancel();
        }
    }

    private Component text(StoredSpawn record, ClaimInspection claim, boolean theirs) {
        PlotMessages messages = plugin.plotInformation().messages();
        Location blocks = PlotInformation.blockLocation(record);
        List<String> parts = new ArrayList<>();
        parts.add(messages.format("arrival-plot", Map.of("world", record.worldName(),
                "x", Integer.toString(blocks.getBlockX()), "y", Integer.toString(blocks.getBlockY()),
                "z", Integer.toString(blocks.getBlockZ()))));
        if (theirs) {
            parts.add(messages.format("arrival-claim", Map.of("width", Long.toString(claim.width()),
                    "depth", Long.toString(claim.depth()), "min_x", Integer.toString(claim.minX()),
                    "max_x", Integer.toString(claim.maxX()), "min_z", Integer.toString(claim.minZ()),
                    "max_z", Integer.toString(claim.maxZ()))));
            String tool = plugin.investigationTool();
            if (tool != null && !tool.isBlank()) {
                parts.add(messages.format("arrival-tip", Map.of("tool", tool)));
            }
        }
        parts.removeIf(String::isEmpty);
        if (parts.isEmpty()) {
            return null;
        }
        return LegacyComponentSerializer.legacySection().deserialize(String.join(" ", parts));
    }

    private void schedule(Player player, Runnable action) {
        try {
            player.getScheduler().run(plugin, task -> {
                try {
                    action.run();
                } catch (Throwable failure) {
                    plugin.getLogger().log(Level.FINE, "Could not show the arrival hint", failure);
                }
            }, null);
        } catch (Throwable retired) {
            plugin.getLogger().log(Level.FINE, "Arrival hint recipient is unavailable", retired);
        }
    }
}
