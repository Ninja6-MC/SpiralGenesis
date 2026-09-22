package com.ninja6.spiralgenesis.listeners;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.config.AllocationTrigger;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerSpawnListener implements Listener {

    private final SpiralGenesisPlugin plugin;
    private final PlayerActionGateListener gate;
    /**
     * Players for whom {@code PlayerRespawnEvent} fired since their last death. Cleared on
     * death and on quit, set by {@link #onPlayerRespawn}, consumed by
     * {@link #onRespawnPointLost}.
     */
    private final Set<UUID> respawnEventSeen = ConcurrentHashMap.newKeySet();

    public PlayerSpawnListener(SpiralGenesisPlugin plugin, PlayerActionGateListener gate) {
        this.plugin = plugin;
        this.gate = gate;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // An operator joining a server whose storage failed is told in chat, because the
        // SEVERE that reported it scrolled past at startup and nothing else on a working
        // server looks wrong until a new player fails to get a plot. Everyone then carries
        // on below: with no records readable nobody has a spawn, so each player goes through
        // the one "allocation unavailable" hold and is resumed when a reload recovers.
        String storageNotice = plugin.storageFailureNotice();
        if (storageNotice != null && player.hasPermission(SpiralGenesisPlugin.ADMIN_PERMISSION)) {
            player.sendMessage(ChatColor.RED + storageNotice);
        }

        // Returning players are the common case and have nothing to allocate. Gating them
        // would arm a timeout per join and, for anyone who joins and then stands still,
        // fire the backstop warning about a limbo that is not holding them and a login
        // plugin that may not exist.
        //
        // The exception is a player whose plot was recorded after they disconnected, who has
        // never been placed on it. They take the same route an unassigned player does, so
        // they are placed at the moment a new player would be allocated - after the login
        // plugin has let go of them - and handlePlayerFirstJoin places them instead of
        // allocating, because the record is already there.
        if (plugin.getDataStorage().hasSpawn(player.getUniqueId())
                && !plugin.isPlacementOwed(player.getUniqueId())) {
            return;
        }

        // A player from before the plugin was installed is not gated either: there is
        // nothing to allocate them, so there is nothing to wait for.
        if (plugin.skipIfPreInstall(player)) {
            return;
        }

        // Bedrock players are never gated: Floodgate authenticates them against Xbox Live
        // during the connection itself, so there is no limbo to wait out.
        if (plugin.getFloodgateHook().isBedrockPlayer(player.getUniqueId())) {
            plugin.handlePlayerFirstJoin(player, "BEDROCK");
            return;
        }

        if (plugin.getPluginConfig().getAllocationTrigger() == AllocationTrigger.ON_JOIN) {
            plugin.handlePlayerFirstJoin(player, "JAVA");
            return;
        }

        // Held until the player proves nothing is holding them. An installed login plugin
        // adapter may still allocate them sooner, in which case that path drops the player
        // from the gate on its way through handlePlayerFirstJoin, so this one cannot fire
        // afterwards. That hand-off is what keeps the two from each reserving an index.
        gate.markPending(player, "JAVA");
    }

    /**
     * Forgets a player who left. A respawn whose point did not fail leaves its entry in
     * {@link #respawnEventSeen} until the next death, which a player who never comes back
     * does not have. A deferred task that would still read the entry is retired with the
     * player, so nothing is lost by dropping it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        respawnEventSeen.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Starts the plot re-check as soon as the player dies, rather than waiting for them to
     * click respawn.
     *
     * <p>This is the only half of revalidation that works on Folia. Folia's respawn runs
     * through {@code ServerPlayer.respawn}, which computes the position itself and never
     * fires {@link PlayerRespawnEvent} - its {@code PlayerList.respawn}, the method that
     * does fire it, throws {@code UnsupportedOperationException} outright. Confirmed by
     * decompiling folia-1.21.11 and by a live server: not one respawn handler ran. Death,
     * by contrast, is delivered on both platforms.
     *
     * <p>It is also the better moment on Paper. The search runs during the seconds a player
     * spends on the death screen, so a plot that can be repaired in time is usually already
     * fixed by the time {@link #onPlayerRespawn} looks at it, and they respawn straight onto
     * it instead of being parked at world spawn first.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        if (record == null || record.toLocation() == null) {
            return;
        }
        // Confirming first: most deaths have nothing to do with the plot, and the check is
        // one chunk and a handful of block reads.
        //
        // Not filtered on whether this player is actually heading for their plot - a bed
        // sleeper is not, and re-checking their plot loads a chunk for nothing. Filtering on
        // Player.getRespawnLocation() was tried and reverted: it matched the stored plot on
        // Paper and did not on Folia, which silently disabled revalidation there. One chunk
        // load per death is the price of not guessing at that.
        plugin.repairSpawn(player, record, true);

        // A new death respawn starts here; whatever the last one left behind is stale.
        respawnEventSeen.remove(player.getUniqueId());

        // A player with no respawn point at all is sent to the overworld's spawn by Folia
        // without any event to intercept: its respawn only calls setRespawnPosition, and so
        // only fires onRespawnPointLost, when there was a point and it failed. So that case
        // is fixed here, while the death is being handled on the player's own thread, which
        // is before any respawn packet can be processed. getPotentialBedLocation reads the
        // stored point without touching a block, so it is safe here where
        // getRespawnLocation, which resolves the point in whatever region holds it, is not.
        Location point = player.getPotentialBedLocation();

        // A plot left outside a shrunken world border is kept off the respawn point for
        // this death. Folia accepts a forced point on its feet and head blocks alone and
        // never looks at the border, so nothing else would stop it sending the player
        // straight back outside; the repair started above is asynchronous and can lose that
        // race. With no point, both platforms respawn the player at the main world's spawn:
        // Folia always uses the overworld, Paper the main world's respawn dimension, which
        // is the overworld unless an operator moved it. That is the plot world's spawn only
        // while the plot world is the main world. The border needs no block read, so it is
        // judged here, on the player's own thread, before any respawn packet can be
        // processed. Which point counts as the plot is decided as the repair decides it:
        // its block column, since a lift puts the player above it.
        // The record is not touched, and once the border takes the plot back in, the next
        // death restores it as the point below.
        SpawnManager manager = plugin.getSpawnManager();
        Location plot = record.toLocation();
        if (manager != null && !manager.isInsideBorder(plot)) {
            if (SpiralGenesisPlugin.isPlotColumn(point, plot)) {
                player.setRespawnLocation(null, false);
            }
            return;
        }

        if (point == null) {
            Location fallback = respawnFallback(player);
            if (fallback != null) {
                player.setRespawnLocation(fallback, true);
            }
        }
    }

    /**
     * Sends a player whose respawn point did not resolve back to their plot, at the moment
     * the server finds out.
     *
     * <p>Both platforms clear the respawn point with cause {@code PLAYER_RESPAWN} when the
     * point they respawn through no longer resolves, and for nothing else: a bed that is
     * gone or obstructed, an anchor with no charge, a forced point that is blocked. A
     * working bed or anchor never reaches this, so nothing here can take one away.
     *
     * <p>Changing the event's location is what the server stores in place of null, so the
     * plot becomes the forced respawn point again for every later death. It cannot change
     * the respawn already in progress, which has chosen world spawn by then, so the player
     * is also moved once they are placed - unless {@code PlayerRespawnEvent} fired for the
     * same respawn, in which case {@link #onPlayerRespawn} has already routed them and
     * moving them again would undo its decision to hold them off an unsafe plot.
     *
     * <p>The point that failed can be the plot itself. A forced point is declined when its
     * feet or head block is solid or liquid, and the owner building on their own spawn is
     * the ordinary way for that to happen. The plot is still restored as the point then:
     * leaving it cleared would send the player to world spawn on every later death, and on
     * Folia nothing else would put it back. The server declines it again on the next death
     * and this runs again, so each of those deaths passes through world spawn for a moment.
     * The stored record is never changed by any of this.
     *
     * <p>Where each platform stands, from their bytecode: Folia (1.21.11) fires this inside
     * {@code ServerPlayer.respawn}, from the chunk-load callback that runs on the region
     * owning the old respawn point, after the player has been removed from their world, and
     * never fires {@code PlayerRespawnEvent} for a death. Paper fires both, in an order that
     * differs by version: this first on 1.20.4, the respawn event first on 1.21.11. That is
     * why the check for the respawn event is made in the deferred task rather than here.
     *
     * <p>Only the event and thread-safe calls are touched here, since on Folia this thread
     * owns neither the player nor, necessarily, their plot. Nothing here reads a block or
     * the player's own state.
     *
     * <p>The deferred task decides where the player goes, through {@link #placeOnPlot}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRespawnPointLost(PlayerSetSpawnEvent event) {
        if (event.getCause() != PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN
                || event.getLocation() != null) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location fallback = respawnFallback(player);
        if (fallback == null) {
            return;
        }
        event.setLocation(fallback);
        event.setForced(true);

        player.getScheduler().run(plugin, task -> {
            if (respawnEventSeen.remove(uuid)) {
                return;
            }
            placeOnPlot(player, fallback, false);
        }, null);
    }

    /**
     * Moves a player who has just been placed by a respawn to where they can stand on their
     * plot. Called on the player's own thread, once the respawn has placed them.
     *
     * <p>It re-checks the plot first: the plot may have become unsafe since death, and a
     * player held where they are is better off than one moved into lava. The repair
     * started at death moves them once it finds a safe point. A plot that passes is then
     * resolved to {@link SpawnManager#standingPoint}, which is the plot itself or, when it
     * has been built over, the first clear position above it. Neither Folia nor Paper
     * 1.21.11 and later does that lift for a respawn on its own; paper-1.20.4 does, and
     * {@link SpawnManager#isSafeNow} has the detail. Both steps run on the thread owning
     * the plot, reached through the manager, never on this one.
     *
     * <p>When there is no clear, safe position above the plot - built up to the build
     * limit, or capped with something that hurts - there is nothing to repair, since the
     * plot itself is safe. A player placed elsewhere is left there, and one already placed
     * on the plot, inside the build, is moved to world spawn. The plot stays their respawn
     * point either way.
     *
     * @param onPlot whether the respawn placed them on the stored plot itself, as the
     *               Paper path does when the plot could not be checked inline
     */
    private void placeOnPlot(Player player, Location plot, boolean onPlot) {
        SpawnManager manager = plugin.getSpawnManager();
        if (manager == null) {
            if (!onPlot) {
                moveTo(player, plot);
            }
            return;
        }
        manager.revalidate(plot).whenComplete((safe, ex) -> {
            if (ex != null || !Boolean.TRUE.equals(safe)) {
                if (!onPlot) {
                    plugin.getLogger().warning(player.getName() + "'s respawn point no longer"
                            + " resolved and their plot failed its re-check; holding them"
                            + " where they respawned until the repair moves them.");
                }
                return;
            }
            manager.standingPoint(plot).whenComplete((standing, error) -> {
                if (error != null || standing == null) {
                    plugin.getLogger().warning("There is no clear, safe position above "
                            + player.getName() + "'s plot; leaving them "
                            + (onPlot ? "at world spawn." : "where they respawned."));
                    if (onPlot) {
                        Location worldSpawn = plot.getWorld().getSpawnLocation();
                        player.getScheduler().run(plugin, t -> moveTo(player, worldSpawn), null);
                    }
                    return;
                }
                if (onPlot && sameBlock(standing, plot)) {
                    return;
                }
                player.getScheduler().run(plugin, t -> moveTo(player, standing), null);
            });
        });
    }

    /** Moves a player placed by a respawn, on the player's own thread. */
    private void moveTo(Player player, Location target) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        plugin.getLogger().info("Moved " + player.getName() + " after their respawn to ("
                + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ()
                + ").");
        player.teleportAsync(target);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    /**
     * Where a player whose respawn point has failed, or who has none, should respawn
     * instead, or null to leave the server's own choice, which is world spawn.
     *
     * <p>The one place that decision is made, for both {@link #onRespawnPointLost} and the
     * no-point case in {@link #onPlayerDeath}. Any case that needs a different answer
     * belongs here rather than in either caller.
     *
     * <p>The answer is the stored plot whenever there is one, including when the point
     * that failed was the plot itself. Whether the player can be moved there, and to which
     * block of it, depends on blocks, and neither caller may read one: that is settled by
     * the deferred task in {@link #onRespawnPointLost}.
     */
    private Location respawnFallback(Player player) {
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        Location spawn = record == null ? null : record.toLocation();
        if (spawn == null || spawn.getWorld() == null) {
            return null;
        }
        return spawn;
    }

    /**
     * Sends a player back to their plot, but only after re-checking that the plot is still
     * survivable.
     *
     * <p>A plot is validated once, when it is allocated, and the plugin has no claim or
     * protection system: 500-block cells are wide open, so anyone can flood a spawn, pour
     * lava on it or dig out the ground under it. Without a re-check the owner respawns into
     * it, dies, and respawns into it again.
     *
     * <p>The backstop rather than the main event: {@link #onPlayerDeath} normally has the
     * repair running, or finished, by the time this fires. What is left for here is the
     * player who clicks respawn faster than a chunk loads.
     *
     * <p>This event is synchronous and nothing can be awaited inside it, so the re-check is
     * split. What is already resident is judged here, inline and for free; what is not is
     * left alone and corrected by the repair afterwards. Sending every unverifiable respawn
     * to world spawn instead would be a much worse trade - a plot whose chunks have simply
     * unloaded is the ordinary case, and the rare griefed one costs its owner one extra
     * death either way.
     *
     * <p>A plot that passes is not respawned onto as stored but at
     * {@link SpawnManager#standingPoint}, because paper-1.21.11 and later place the player
     * at this event's location exactly, inside whatever the owner built there. When the
     * chunk is resident that is resolved inline; when it is not, the player respawns at
     * the plot and {@link #placeOnPlot} lifts them once they are placed. A plot with no
     * clear, safe position above it holds the player at world spawn.
     *
     * <p>Never reached on Folia <em>for a death respawn</em>; see {@link #onPlayerDeath} for
     * why. It is still reached there when a player leaves the End, because
     * {@code EndPortalBlock} fires this event itself with {@code RespawnReason.END_PORTAL}
     * rather than going through the path Folia stubs out. Verified against folia-1.21.11.
     *
     * <p>That case is handled the same as any other: a player with no bed or anchor is sent
     * to their plot. Whether an End exit should route there is a question nobody has
     * answered deliberately - it falls out of not filtering on
     * {@code event.getRespawnReason()}. It is defensible, since the plot is effectively
     * their home, but it is not a decision anyone recorded.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // Tells onRespawnPointLost that this respawn has already been routed.
        respawnEventSeen.add(player.getUniqueId());

        // A bed or anchor is the player's own choice and outranks their plot.
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        Location spawn = record == null ? null : record.toLocation();
        if (spawn == null || spawn.getWorld() == null) {
            return; // World not loaded; vanilla handling is the only thing left.
        }

        SpawnManager manager = plugin.getSpawnManager();
        if (manager == null) {
            event.setRespawnLocation(spawn);
            return;
        }

        SpawnManager.SpawnVerdict verdict = manager.verifyStoredSpawn(spawn);
        if (verdict == SpawnManager.SpawnVerdict.UNSAFE) {
            // World spawn is a holding position, not the outcome: the repair below moves
            // them onto a safe point inside their own cell as soon as it finds one.
            event.setRespawnLocation(spawn.getWorld().getSpawnLocation());
            plugin.repairSpawn(player, record, false);
            return;
        }

        if (verdict == SpawnManager.SpawnVerdict.USABLE) {
            // Resident, so the same chunk the verdict just read: answered inline.
            Location standing = manager.standingPointNow(spawn);
            if (standing == null) {
                plugin.getLogger().warning("There is no clear, safe position above "
                        + player.getName() + "'s plot; respawning them at world spawn.");
                event.setRespawnLocation(spawn.getWorld().getSpawnLocation());
                return;
            }
            event.setRespawnLocation(standing);
            return;
        }

        // Unverified: nothing can be read here, so they respawn at the plot and are lifted,
        // or repaired, once the chunk has been checked.
        event.setRespawnLocation(spawn);
        plugin.repairSpawn(player, record, true);
        player.getScheduler().run(plugin, task -> placeOnPlot(player, spawn, true), null);
    }
}
