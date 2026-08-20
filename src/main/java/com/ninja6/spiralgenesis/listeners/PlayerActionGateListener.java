package com.ninja6.spiralgenesis.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * Holds a player's allocation until they do something no other plugin cancelled.
 *
 * <p>Exists to gate allocation behind authentication without naming an authentication
 * plugin. Every login plugin in this ecosystem enforces its limbo by cancelling what an
 * unauthenticated player does, and none of them share an API, so the cancellation is the
 * only signal common to all of them. Listening at {@link EventPriority#MONITOR} with
 * {@code ignoreCancelled = true} reads the verdict after every other plugin has voted: if
 * anything is still holding the player, these handlers never run.
 *
 * <p>The inference is deliberately one-directional. An uncancelled action proves nobody is
 * holding the player; a cancelled one proves only that <em>something</em> objected, which
 * may be an anti-cheat or a region plugin rather than a login plugin. That is why several
 * unrelated actions are watched and any one of them releases: they would all have to be
 * suppressed at once for the gate to stay shut, and {@code action-timeout-seconds} bounds
 * even that.
 */
public class PlayerActionGateListener implements Listener {

    private final JavaPlugin plugin;
    private final BiConsumer<Player, String> onRelease;
    private final IntSupplier timeoutSeconds;

    /** Players awaiting a first action, mapped to the client type to allocate them as. */
    private final Map<UUID, String> pending = new ConcurrentHashMap<>();

    /**
     * @param onRelease invoked with the player and their client type once the gate opens;
     *                  must be idempotent, since a login-plugin adapter may have allocated
     *                  the same player already
     * @param timeoutSeconds allocate anyway after this long, or 0 to wait indefinitely.
     *                       Read per player rather than captured, so {@code /sgen reload}
     *                       takes effect without re-registering the listener.
     */
    public PlayerActionGateListener(JavaPlugin plugin, BiConsumer<Player, String> onRelease,
                                    IntSupplier timeoutSeconds) {
        this.plugin = plugin;
        this.onRelease = onRelease;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Registers a player as awaiting their first action, and arms the timeout.
     */
    public void markPending(Player player, String clientType) {
        if (pending.putIfAbsent(player.getUniqueId(), clientType) != null) {
            return;
        }
        int timeout = timeoutSeconds.getAsInt();
        if (timeout > 0) {
            armTimeout(player, timeout);
        }
    }

    /** Whether this player is still waiting on the gate. Package-private for tests. */
    boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    /**
     * Opens the gate for a player, at most once.
     *
     * <p>Removing before invoking the callback rather than after is what makes this safe to
     * call from several event handlers on different threads: on Folia a move and an
     * interact can be processed concurrently, and only the thread that wins the removal
     * proceeds.
     */
    private void release(Player player, String why) {
        String clientType = pending.remove(player.getUniqueId());
        if (clientType == null) {
            return;
        }
        plugin.getLogger().fine("Allocation gate opened for " + player.getName() + " (" + why + ").");
        onRelease.accept(player, clientType);
    }

    /**
     * Schedules the backstop.
     *
     * <p>Uses the player's own scheduler rather than a global task: it is the thread that
     * owns this player on Folia, and it drops the task automatically when they disconnect,
     * which is why no cleanup is needed on quit beyond forgetting the map entry.
     *
     * <p>The timeout can fire while a login plugin is still holding the player, which is
     * the very thing the gate exists to prevent, so it logs at warning rather than
     * silently. An administrator seeing it repeatedly has a login plugin whose limbo this
     * gate cannot read, and should switch the trigger to ON_JOIN or report it.
     */
    private void armTimeout(Player player, int timeout) {
        player.getScheduler().runDelayed(plugin, task -> {
            if (!pending.containsKey(player.getUniqueId())) {
                return;
            }
            plugin.getLogger().warning("No uncancelled action from " + player.getName() + " within "
                    + timeout + "s; allocating anyway. If a login plugin is installed, it may "
                    + "still be holding this player.");
            release(player, "timeout");
        }, null, timeout * 20L);
    }

    /**
     * Movement is the primary signal: it is what every login plugin cancels, and the one
     * action a player cannot avoid producing once they are free.
     *
     * <p>Compared at block granularity because the event also fires on head rotation, and
     * limbo implementations generally permit looking around while pinning position. Testing
     * the block the player occupies rather than any coordinate change keeps this trigger
     * aligned with what those plugins actually cancel.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (pending.isEmpty()) {
            return;
        }
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null
                || (to.getBlockX() == from.getBlockX()
                && to.getBlockY() == from.getBlockY()
                && to.getBlockZ() == from.getBlockZ())) {
            return;
        }
        release(event.getPlayer(), "moved");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (pending.isEmpty()) {
            return;
        }
        release(event.getPlayer(), "interacted");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (pending.isEmpty()) {
            return;
        }
        release(event.getPlayer(), "dropped an item");
    }

    /**
     * Forgets a player who left before acting. Their index was never reserved, so nothing
     * is leaked by dropping them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
