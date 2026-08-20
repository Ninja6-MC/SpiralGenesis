package com.ninja6.spiralgenesis.listeners;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.config.AllocationTrigger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
        // adapter may still allocate them sooner; handlePlayerFirstJoin is idempotent, so
        // whichever path arrives first wins and the other is a no-op.
        gate.markPending(player, "JAVA");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // If player has no bed or anchor, enforce their spiral plot spawn
        if (!event.isBedSpawn() && !event.isAnchorSpawn()) {
            if (plugin.getDataStorage().hasSpawn(player.getUniqueId())) {
                Location spawn = plugin.getDataStorage().getSpawn(player.getUniqueId());
                if (spawn != null && spawn.getWorld() != null) {
                    event.setRespawnLocation(spawn);
                }
            }
        }
    }
}
