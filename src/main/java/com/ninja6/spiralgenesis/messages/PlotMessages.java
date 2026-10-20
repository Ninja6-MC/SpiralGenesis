package com.ninja6.spiralgenesis.messages;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.Map;

/** Reloadable legacy-colour text; placeholders are replaced literally, never parsed. */
public final class PlotMessages {
    private static final Map<String, String> DEFAULTS = defaults();
    private final FileConfiguration config;
    public PlotMessages(FileConfiguration config) { this.config = config; }

    public void send(CommandSender sender, String key, Map<String, String> values) {
        String text = format(key, values);
        if (!text.isEmpty()) { sender.sendMessage(text); }
    }

    /** The template with colours applied and placeholders filled, or empty when blank. */
    public String format(String key, Map<String, String> values) {
        String template = config.getString("messages." + key, DEFAULTS.getOrDefault(key, ""));
        if (template == null || template.isBlank()) { return ""; }
        // Translate the template before inserting data so player/world names cannot add colours.
        String text = ChatColor.translateAlternateColorCodes('&', template);
        for (var entry : values.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }

    public static Map<String, String> defaults() {
        return Map.ofEntries(
            Map.entry("plot", "&6Your spawn plot: &f{world} at {x}, {y}, {z}. Use /sgen me to check it."),
            Map.entry("respawn-plot", "&eThis plot is your respawn point. A bed or respawn anchor replaces it."),
            Map.entry("respawn-other", "&eYour current respawn point is not this plot."),
            Map.entry("distance", "&eHorizontal distance: &f{distance} blocks."),
            Map.entry("different-world", "&eYour plot is in another world; distance is unavailable."),
            Map.entry("no-plot", "&eYou have no recorded spawn plot."),
            Map.entry("storage-unavailable", "&cYour plot information is temporarily unavailable. Please try again later."),
            Map.entry("usage", "&eUsage: /sgen me (in game)"),
            Map.entry("help", "&e/sgen me &f- Shows your spawn plot and protection."),
            Map.entry("bounds", "&6Claim: &f{width} x {depth} blocks; X {min_x} to {max_x}, Z {min_z} to {max_z} (inclusive)."),
            Map.entry("build", "&e{player} has build access, including containers and beds."),
            Map.entry("no-build", "&e{player} does not have named build access on this claim."),
            Map.entry("manage", "&e{player} can manage trust here: /trust, /containertrust, /accesstrust and /untrust."),
            Map.entry("no-manage", "&e{player} does not have named permission to manage trust on this claim."),
            Map.entry("admin", "&eThis is an administrative claim. Players cannot resize, subdivide or abandon it."),
            Map.entry("owner", "&e{player} owns this claim. Normal GriefPrevention claim commands apply."),
            Map.entry("trusted", "&eThis claim belongs to another player; {player}'s named access is shown above."),
            Map.entry("outside", "&eThis spawn claim does not protect blocks outside these bounds."),
            Map.entry("no-claim", "&eNo claim owned by or explicitly trusting {player} covers the spawn column."),
            Map.entry("unavailable", "&eProtection information is unavailable."),
            Map.entry("arrival-plot", "&6Your plot &f{x}, {y}, {z}"),
            Map.entry("arrival-claim", "&7- &eclaim {width} x {depth}, X {min_x} to {max_x}, Z {min_z} to {max_z}"),
            Map.entry("arrival-tip", "&7- right-click with a {tool} to inspect claims"));
    }
}
