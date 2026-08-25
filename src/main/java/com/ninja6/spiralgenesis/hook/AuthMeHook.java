package com.ninja6.spiralgenesis.hook;

import org.bukkit.Bukkit;

/**
 * Adapter hook for AuthMe-Reloaded Java authentication.
 *
 * <p>Presence only. Allocation is driven by {@code AuthMeHookListener} reacting to AuthMe's
 * own login events, and by the generic action gate behind it, so nothing here needs to ask
 * AuthMe about a player's state.
 */
public class AuthMeHook {

    private final boolean installed;

    public AuthMeHook() {
        this.installed = Bukkit.getPluginManager().isPluginEnabled("AuthMe");
    }

    public boolean isInstalled() {
        return installed;
    }
}
