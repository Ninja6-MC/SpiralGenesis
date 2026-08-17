package com.ninja6.spiralgenesis.listeners;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PlayerSpawnListener implements Listener {

    private final SpiralGenesisPlugin plugin;

    public PlayerSpawnListener(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // If Floodgate is installed and player is on Bedrock, allocate spawn immediately on join
        if (plugin.getFloodgateHook().isBedrockPlayer(player.getUniqueId())) {
            plugin.handlePlayerFirstJoin(player, "BEDROCK");
            return;
        }

        // If AuthMe is NOT installed, allocate Java players immediately on join
        if (!plugin.getAuthMeHook().isInstalled()) {
            plugin.handlePlayerFirstJoin(player, "JAVA");
        }
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
