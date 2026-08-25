package com.ninja6.spiralgenesis.listeners;

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

public class PlayerSpawnListener implements Listener {

    private final SpiralGenesisPlugin plugin;
    private final PlayerActionGateListener gate;

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
     * <p>Never reached on Folia; see {@link #onPlayerDeath}.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

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
