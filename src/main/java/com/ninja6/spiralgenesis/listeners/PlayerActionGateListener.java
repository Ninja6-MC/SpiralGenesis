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
 * plugin. None of them share an API, but all of them suppress what an unauthenticated
 * player does, so suppression is the only signal common to all of them.
 *
 * <p>They suppress in two different ways and both are read here, because reading only the
 * first would leave the gate open on the most common login plugin of all:
 *
 * <ul>
 *   <li><b>Cancelling.</b> Handled by listening at {@link EventPriority#MONITOR} with
 *       {@code ignoreCancelled = true}, which reads the verdict after every other plugin
 *       has voted. AuthMe cancels interaction and item drops this way, at {@code LOWEST}.
 *   <li><b>Rewriting the outcome.</b> AuthMe's move handler runs at {@code HIGHEST} and
 *       pins the player with {@code event.setTo(event.getFrom())}, never touching the
 *       cancel flag. Such an event arrives here uncancelled, so {@link #onMove} compares
 *       the destination against the origin rather than trusting that it was delivered.
 * </ul>
 *
 * <p>The inference is deliberately one-directional. An action that survives intact proves
 * nobody is holding the player; a suppressed one proves only that <em>something</em>
 * objected, which may be an anti-cheat or a region plugin rather than a login plugin. That
 * is why several unrelated actions are watched and any one of them releases: they would all
 * have to be suppressed at once for the gate to stay shut, and
 * {@code action-timeout-seconds} bounds even that.
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
     * Drops a player from the gate without allocating them.
     *
     * <p>For a caller that is allocating the player itself, so the gate does not later fire
     * a second, redundant release for an action they take afterwards.
     */
    public void forget(UUID uuid) {
        pending.remove(uuid);
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
     * Movement is the primary signal: it is the one action a player cannot avoid producing
     * once they are free.
     *
     * <p>Three things about this test are load-bearing, and all come from reading how login
     * plugins actually implement limbo rather than from assuming they cancel:
     *
     * <ul>
     *   <li><b>The destination is compared, not trusted.</b> AuthMe 5.6.0 and OpeNLogin
     *       both pin a held player with {@code event.setTo(event.getFrom())} and never
     *       touch the cancel flag - OpeNLogin's source says why, it avoids a "too many
     *       packets" disconnect that {@code Player.teleport} causes. Such an event reaches
     *       this handler uncancelled, and only re-reading {@code getTo} reveals the pin.
     *       LibreLogin does cancel, which {@code ignoreCancelled} already handles.
     *   <li><b>Descending movement never counts.</b> OpeNLogin returns early without
     *       pinning whenever the player is descending, and its check has no block-column
     *       test, so a held player falling <em>with horizontal drift</em> emits genuine
     *       uncancelled block changes. Ignoring the Y axis alone does not close that, since
     *       such a move does change X or Z; the descent itself has to suppress the release.
     *       AuthMe is stricter here - it exempts only straight-down movement - but the
     *       weaker of the two is what this has to survive.
     * </ul>
     *
     * <p>Block granularity rather than raw coordinates, because the event also fires on
     * head rotation and limbo implementations generally permit looking around.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (pending.isEmpty()) {
            return;
        }
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) {
            return;
        }
        // Losing height means gravity may be doing the work rather than the player, and at
        // least one limbo implementation declines to interfere with a falling player at all.
        // Waiting for a move that is level or climbing costs a held player nothing: once
        // they are free, walking produces those continuously.
        if (to.getY() < from.getY()) {
            return;
        }
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        release(event.getPlayer(), "moved");
    }

    /**
     * Only block interactions can open the gate. {@link PlayerInteractEvent} denies its
     * block result whenever there is no block, and {@code isCancelled} reads that field, so
     * a click on air is indistinguishable from one a plugin vetoed and never arrives here.
     */
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
