package com.ninja6.spiralgenesis.hook;

import org.bukkit.Bukkit;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

/**
 * Adapter hook for GeyserMC / Floodgate Bedrock player detection.
 */
public class FloodgateHook {

    private final boolean installed;

    public FloodgateHook() {
        this.installed = Bukkit.getPluginManager().isPluginEnabled("floodgate");
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isBedrockPlayer(UUID uuid) {
        if (!installed) {
            return false;
        }
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
        } catch (Throwable t) {
            return false;
        }
    }
}
