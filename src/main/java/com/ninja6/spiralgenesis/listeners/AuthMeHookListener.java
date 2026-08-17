package com.ninja6.spiralgenesis.listeners;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RegisterEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class AuthMeHookListener implements Listener {

    private final SpiralGenesisPlugin plugin;

    public AuthMeHookListener(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAuthMeRegister(RegisterEvent event) {
        Player player = event.getPlayer();
        plugin.handlePlayerFirstJoin(player, "JAVA");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAuthMeLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getDataStorage().hasSpawn(player.getUniqueId())) {
            plugin.handlePlayerFirstJoin(player, "JAVA");
        }
    }
}
