package com.ninja6.spiralgenesis.listeners;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.config.AllocationTrigger;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerSpawnListener implements Listener {

    private final SpiralGenesisPlugin plugin;
    private final PlayerActionGateListener gate;
    /**
     * Players for whom {@code PlayerRespawnEvent} fired since their last death. Cleared on
     * death, set by {@link #onPlayerRespawn}, consumed by {@link #onRespawnPointLost}.
     */
    private final Set<UUID> respawnEventSeen = ConcurrentHashMap.newKeySet();

    public PlayerSpawnListener(SpiralGenesisPlugin plugin, PlayerActionGateListener gate) {
        this.plugin = plugin;
        this.gate = gate;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Returning players are the common case and have nothing to allocate. Gating them
        // would arm a timeout per join and, for anyone who joins and then stands still,
        // fire the backstop warning about a limbo that is not holding them and a login
        // plugin that may not exist.
        if (plugin.getDataStorage().hasSpawn(player.getUniqueId())) {
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

        // A player with no respawn point at all is sent to world spawn by Folia without any
        // event to intercept: its respawn only calls setRespawnPosition, and so only fires
        // onRespawnPointLost, when there was a point and it failed. So that case is fixed
        // here, while the death is being handled on the player's own thread, which is
        // before any respawn packet can be processed. getPotentialBedLocation reads the
        // stored point without touching a block, so it is safe here where
        // getRespawnLocation, which resolves the point in whatever region holds it, is not.
        if (player.getPotentialBedLocation() == null) {
            Location fallback = respawnFallback(player);
            if (fallback != null) {
                player.setRespawnLocation(fallback, true);
            }
        }
    }

    /**
     * Sends a player whose bed or anchor has gone back to their plot, at the moment the
     * server finds out.
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
     * <p>Where each platform stands, from their bytecode: Folia (1.21.11) fires this inside
     * {@code ServerPlayer.respawn}, from the chunk-load callback that runs on the region
     * owning the old respawn point, after the player has been removed from their world, and
     * never fires {@code PlayerRespawnEvent} for a death. Paper fires both, in an order that
     * differs by version: this first on 1.20.4, the respawn event first on 1.21.11. That is
     * why the check for the respawn event is made in the deferred task rather than here.
     *
     * <p>Only the event and thread-safe calls are touched here, since on Folia this thread
     * owns neither the player nor, necessarily, their plot.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRespawnPointLost(PlayerSetSpawnEvent event) {
        if (event.getCause() != PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN
                || event.getLocation() != null) {
            return;
        }
        Player player = event.getPlayer();
        Location fallback = respawnFallback(player);
        if (fallback == null) {
            return;
        }
        event.setLocation(fallback);
        event.setForced(true);

        UUID uuid = player.getUniqueId();
        player.getScheduler().run(plugin, task -> {
            if (respawnEventSeen.remove(uuid) || !player.isOnline() || player.isDead()) {
                return;
            }
            plugin.getLogger().info(player.getName() + "'s respawn point no longer resolved;"
                    + " restored it to their plot and moved them there.");
            player.teleportAsync(fallback);
        }, null);
    }

    /**
     * Where a player whose respawn point has failed should respawn instead, or null to
     * leave the server's own choice, which is world spawn.
     *
     * <p>The one place that decision is made, for both {@link #onRespawnPointLost} and the
     * no-point case in {@link #onPlayerDeath}. Today it is the stored plot, unchanged. A
     * case that needs a different answer - a plot obstructed by the player's own build,
     * which fails Folia's forced-point check and arrives through the same event - belongs
     * here rather than in either caller.
     */
    private Location respawnFallback(Player player) {
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        Location spawn = record == null ? null : record.toLocation();
        return spawn == null || spawn.getWorld() == null ? null : spawn;
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

        event.setRespawnLocation(spawn);
        if (verdict == SpawnManager.SpawnVerdict.UNVERIFIED) {
            plugin.repairSpawn(player, record, true);
        }
    }
}
