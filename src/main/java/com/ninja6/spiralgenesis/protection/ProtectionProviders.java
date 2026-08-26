package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.config.ProtectionProviderType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * Chooses the protection provider a server actually gets.
 *
 * <p>Deliberately left out of the seam that introduced {@link ProtectionProvider}, because
 * until there was a second implementation there was nothing to choose between. This is the
 * one place that maps a configured {@link ProtectionProviderType} onto an object, and every
 * path that is not a working GriefPrevention ends at {@link NoOpProtectionProvider}: the
 * feature switched off, the provider set to {@code NONE}, the named plugin not installed,
 * Folia, or a constructor that threw.
 *
 * <p>It names no third-party type. The GriefPrevention implementation is only referenced
 * from inside a branch this class has already decided to take, so a server without the
 * plugin never resolves the class and never resolves its imports - which is the whole
 * reason the imports are confined to it.
 *
 * <p>Nothing here warns. A server that has never installed a claim plugin has done nothing
 * wrong, so each of those paths gets one {@code INFO} line at startup and no more - and the
 * one genuinely surprising case, GriefPrevention configured on Folia where it cannot run,
 * gets a sentence explaining itself rather than a silence that reads like a bug. The
 * warnings that do exist belong to the GriefPrevention provider's constructor, which is
 * where the two conditions a server owner has to act on are found: a size below
 * GriefPrevention's minimum, and an API that would not bind.
 */
public final class ProtectionProviders {

    private ProtectionProviders() {
    }

    /**
     * Folia's regionised server class, which exists on Folia and on nothing else. Checked
     * by name rather than by a capability, because there is no capability to ask about:
     * GriefPrevention simply does not declare {@code folia-supported}, so Folia refuses to
     * load it and no amount of configuration will make it appear.
     */
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    /**
     * Builds the provider this server should use, and says which one it picked.
     *
     * @param plugin this plugin, for its logger and for the plugin manager behind it
     * @param config the loaded configuration
     * @return a provider; never {@code null}, and never one that throws on use
     */
    public static ProtectionProvider create(Plugin plugin, PluginConfig config) {
        if (!config.isProtectionEnabled()) {
            // Silent. This is the default install, and it must behave exactly as it did
            // before this feature existed.
            return NoOpProtectionProvider.INSTANCE;
        }

        ProtectionProviderType type = config.getProtectionProvider();
        if (type == ProtectionProviderType.NONE) {
            plugin.getLogger().info("Spawn protection is enabled but its provider is NONE, so "
                    + "nothing will be claimed.");
            return NoOpProtectionProvider.INSTANCE;
        }

        if (isFolia()) {
            // Not a warning. folia-supported: true has to stay honest, and it stays honest
            // by this being a fact about the platform rather than a fault in the server.
            plugin.getLogger().info("Spawn protection is configured for GriefPrevention, which "
                    + "does not run on Folia. Nothing will be claimed, and everything else "
                    + "behaves exactly as it does without this feature.");
            return NoOpProtectionProvider.INSTANCE;
        }

        if (!isGriefPreventionPresent()) {
            plugin.getLogger().info("Spawn protection is configured for GriefPrevention, which is "
                    + "not installed on this server. Nothing will be claimed.");
            return NoOpProtectionProvider.INSTANCE;
        }

        try {
            GriefPreventionProtectionProvider provider = new GriefPreventionProtectionProvider(
                    plugin, config.getClaimOwnership(), config.getProtectionSize());
            if (!provider.isAvailable()) {
                // The constructor has already said why, at WARNING, on every path that
                // reaches here. Fall back rather than keep an object whose every answer is
                // PROVIDER_UNAVAILABLE.
                return NoOpProtectionProvider.INSTANCE;
            }
            if (provider.isConfiguredSizeBelowMinimum()) {
                // The constructor warned that this size will never produce a claim. Saying
                // "spawn protection is on" immediately afterwards would contradict it, so
                // the provider is returned without the line that announces it working.
                return provider;
            }
            plugin.getLogger().info("Spawn protection: GriefPrevention, "
                    + config.getProtectionSize() + "x" + config.getProtectionSize() + " "
                    + config.getClaimOwnership().name() + " around each player's spawn.");
            return provider;
        } catch (Throwable t) {
            // Throwable, not Exception: resolving the class at all is what can throw
            // NoClassDefFoundError, and that must not take the plugin's enable down with
            // it. This is the same reasoning the AuthMe adapter registration uses.
            plugin.getLogger().log(Level.WARNING, "GriefPrevention is installed but could not be "
                    + "bound, so no spawn claims will be created.", t);
            return NoOpProtectionProvider.INSTANCE;
        }
    }

    /**
     * Whether GriefPrevention is installed and enabled.
     *
     * <p>The name is GriefPrevention's own {@code plugin.yml} {@code name:}, and this
     * plugin's {@code softdepend:} carries the same spelling, which is what guarantees it
     * has already enabled by the time this runs.
     */
    private static boolean isGriefPreventionPresent() {
        try {
            return Bukkit.getPluginManager()
                    .isPluginEnabled(GriefPreventionProtectionProvider.PLUGIN_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether this server is Folia, where GriefPrevention cannot be installed at all. */
    static boolean isFolia() {
        try {
            Class.forName(FOLIA_MARKER);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
