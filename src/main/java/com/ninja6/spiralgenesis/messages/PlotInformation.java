package com.ninja6.spiralgenesis.messages;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.config.ClaimOwnership;
import com.ninja6.spiralgenesis.protection.ClaimInspection;
import com.ninja6.spiralgenesis.protection.ClaimResult;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Shared presentation for self-service, operator inspection and event notifications. */
public final class PlotInformation {
    private final SpiralGenesisPlugin plugin;
    public PlotInformation(SpiralGenesisPlugin plugin) { this.plugin = plugin; }
    public PlotMessages messages() { return new PlotMessages(plugin.getConfig()); }

    public void show(Player player) {
        if (plugin.storageFailureNotice() != null) {
            messages().send(player, "storage-unavailable", Map.of());
            return;
        }
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        if (record == null) {
            messages().send(player, "no-plot", Map.of());
            return;
        }
        plugin.inspectPlot(record, player.getUniqueId(), claim ->
                schedule(player, () -> {
                    if (!current(player, record)) { return; }
                    sendPlot(player, record, true);
                    sendClaim(player, claim);
                }));
    }

    /** Event deduplication: callers emit once after a successful write and the claim step. */
    public void notify(Player player, Location spawn, boolean moved, ClaimResult result) {
        if (player == null || (!moved && !result.changed())) { return; }
        schedule(player, () -> {
            if (!player.isOnline() || plugin.storageFailureNotice() != null) { return; }
            StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
            if (record == null || !sameBlock(record, spawn)) { return; }
            boolean plotNotice = moved && plugin.getConfig().getBoolean("messages.allocation-notices", true);
            boolean claimNotice = result.changed() && plugin.getConfig().getBoolean("messages.claim-notices", true);
            if (!plotNotice && !claimNotice) { return; }
            plugin.inspectPlot(record, player.getUniqueId(), claim ->
                    schedule(player, () -> {
                        if (!current(player, record)) { return; }
                        if (plotNotice) { sendPlot(player, record, false); }
                        // Folia and absent providers have no automatic claim text.
                        if (claimNotice && claim.status() != ClaimInspection.Status.UNAVAILABLE) {
                            sendClaim(player, claim);
                        }
                    }));
        });
    }

    private void schedule(Player player, Runnable action) {
        try {
            player.getScheduler().run(plugin, task -> {
                try { action.run(); }
                catch (Throwable failure) {
                    plugin.getLogger().log(java.util.logging.Level.FINE, "Could not deliver plot information", failure);
                }
            }, null);
        } catch (Throwable retired) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "Plot recipient is unavailable", retired);
        }
    }

    private boolean current(Player player, StoredSpawn record) {
        return player.isOnline() && plugin.storageFailureNotice() == null
                && Objects.equals(record, plugin.getDataStorage().getRecord(player.getUniqueId()));
    }

    public static boolean sameBlock(StoredSpawn record, Location location) {
        return location != null && location.getWorld() != null
                && record.worldName().equals(location.getWorld().getName())
                && blockLocation(record).getBlockX() == location.getBlockX()
                && blockLocation(record).getBlockY() == location.getBlockY()
                && blockLocation(record).getBlockZ() == location.getBlockZ();
    }

    public static Location blockLocation(StoredSpawn record) {
        return new Location(null, record.x(), record.y(), record.z());
    }

    private void sendPlot(Player player, StoredSpawn record, boolean distance) {
        Location blocks = blockLocation(record);
        Map<String, String> values = new HashMap<>();
        values.put("world", record.worldName());
        values.put("x", Integer.toString(blocks.getBlockX()));
        values.put("y", Integer.toString(blocks.getBlockY()));
        values.put("z", Integer.toString(blocks.getBlockZ()));
        messages().send(player, "plot", values);
        messages().send(player, sameBlock(record, player.getPotentialBedLocation())
                ? "respawn-plot" : "respawn-other", values);
        if (distance) {
            Location here = player.getLocation();
            if (here.getWorld() != null && record.worldName().equals(here.getWorld().getName())) {
                values.put("distance", Long.toString(Math.round(Math.hypot(here.getX() - record.x(),
                        here.getZ() - record.z()))));
                messages().send(player, "distance", values);
            } else {
                messages().send(player, "different-world", values);
            }
        }
    }

    public void sendClaim(CommandSender sender, ClaimInspection claim) {
        sendClaim(sender, claim, sender.getName());
    }

    public void sendClaim(CommandSender sender, ClaimInspection claim, String playerName) {
        if (claim.status() != ClaimInspection.Status.FOUND) {
            messages().send(sender, claim.status() == ClaimInspection.Status.NONE
                    ? "no-claim" : "unavailable", Map.of("player", playerName));
            return;
        }
        Map<String, String> values = Map.of("player", playerName, "width", Long.toString(claim.width()),
                "depth", Long.toString(claim.depth()), "min_x", Integer.toString(claim.minX()),
                "max_x", Integer.toString(claim.maxX()), "min_z", Integer.toString(claim.minZ()),
                "max_z", Integer.toString(claim.maxZ()));
        messages().send(sender, "bounds", values);
        messages().send(sender, claim.build() ? "build" : "no-build", values);
        messages().send(sender, claim.manage() ? "manage" : "no-manage", values);
        messages().send(sender, claim.ownership() == ClaimOwnership.ADMIN_CLAIM ? "admin"
                : claim.owner() ? "owner" : "trusted", values);
        messages().send(sender, "outside", values);
    }
}
