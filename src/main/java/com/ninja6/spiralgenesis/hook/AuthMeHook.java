package com.ninja6.spiralgenesis.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Adapter hook for AuthMe-Reloaded Java authentication.
 */
public class AuthMeHook {

    private final boolean installed;

    public AuthMeHook() {
        this.installed = Bukkit.getPluginManager().isPluginEnabled("AuthMe");
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isAuthenticated(Player player) {
        if (!installed) {
            return true;
        }
        try {
            return fr.xephi.authme.api.v3.AuthMeApi.getInstance().isAuthenticated(player);
        } catch (Throwable t) {
            return true;
        }
    }
}
