package com.ninja6.spiralgenesis.commands;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Administrative /sgen command suite for SpiralGenesis.
 */
public class SpiralCommand implements CommandExecutor, TabCompleter {

    private final SpiralGenesisPlugin plugin;

    public SpiralCommand(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("spiralgenesis.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to execute SpiralGenesis commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "setcenter" -> handleSetCenter(sender, args);
            case "setspawn" -> handleSetSpawn(sender, args);
            case "reassign" -> handleReassign(sender, args);
            case "tp" -> handleTp(sender, args);
            case "info" -> handleInfo(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleSetCenter(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Console must provide coordinates: /sgen setcenter <x> <z>");
                return;
            }
            int x = player.getLocation().getBlockX();
            int z = player.getLocation().getBlockZ();
            plugin.getPluginConfig().setOriginX(x);
            plugin.getPluginConfig().setOriginZ(z);
            plugin.getConfig().set("origin.x", x);
            plugin.getConfig().set("origin.z", z);
            plugin.saveConfig();
            sender.sendMessage(ChatColor.GREEN + "Spiral center set to your current position: (" + x + ", " + z + ")");
        } else if (args.length == 3) {
            try {
                int x = Integer.parseInt(args[1]);
                int z = Integer.parseInt(args[2]);
                plugin.getPluginConfig().setOriginX(x);
                plugin.getPluginConfig().setOriginZ(z);
                plugin.getConfig().set("origin.x", x);
                plugin.getConfig().set("origin.z", z);
                plugin.saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Spiral center set to: (" + x + ", " + z + ")");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid coordinate numbers.");
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen setcenter OR /sgen setcenter <x> <z>");
        }
    }

    private void handleSetSpawn(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen setspawn <player> [x y z]");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or offline.");
            return;
        }

        Location loc;
        if (args.length >= 5) {
            try {
                double x = Double.parseDouble(args[2]);
                double y = Double.parseDouble(args[3]);
                double z = Double.parseDouble(args[4]);
                loc = new Location(target.getWorld(), x, y, z);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid coordinates.");
                return;
            }
        } else if (sender instanceof Player player) {
            loc = player.getLocation();
        } else {
            sender.sendMessage(ChatColor.RED + "Console must specify x y z coordinates.");
            return;
        }

        plugin.getDataStorage().setSpawn(target.getUniqueId(), loc, -1, 0, 0, target.getName(), "MANUAL");
        target.setRespawnLocation(loc, true);
        sender.sendMessage(ChatColor.GREEN + "Set spawn for " + target.getName() + " to: " +
                loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
    }

    private void handleReassign(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen reassign <player>");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or offline.");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Reallocating fresh safe spiral plot for " + target.getName() + "...");
        int nextIndex = plugin.getDataStorage().getCurrentIndex();

        plugin.getSpawnManager().allocateNextSafeSpawn(nextIndex).thenAccept(res -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getDataStorage().setCurrentIndex(res.index() + 1);
                plugin.getDataStorage().setSpawn(target.getUniqueId(), res.location(), res.index(), res.gridU(), res.gridV(), target.getName(), "REASSIGN");
                target.setRespawnLocation(res.location(), true);
                target.teleportAsync(res.location()).thenAccept(success -> {
                    sender.sendMessage(ChatColor.GREEN + "Successfully reassigned " + target.getName() + " to index #" +
                            res.index() + " at (" + res.location().getBlockX() + ", " + res.location().getBlockY() + ", " + res.location().getBlockZ() + ")");
                });
            });
        });
    }

    private void handleTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /sgen tp.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen tp <player>");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID uuid = target.getUniqueId();
        if (!plugin.getDataStorage().hasSpawn(uuid)) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " has no recorded genesis spawn.");
            return;
        }

        Location loc = plugin.getDataStorage().getSpawn(uuid);
        player.teleportAsync(loc).thenAccept(success -> {
            player.sendMessage(ChatColor.GREEN + "Teleported to " + args[1] + "'s genesis spawn.");
        });
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen info <player>");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID uuid = target.getUniqueId();
        if (!plugin.getDataStorage().hasSpawn(uuid)) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " has no recorded genesis spawn.");
            return;
        }

        Location loc = plugin.getDataStorage().getSpawn(uuid);
        sender.sendMessage(ChatColor.GOLD + "=== SpiralGenesis Info: " + args[1] + " ===");
        sender.sendMessage(ChatColor.YELLOW + "UUID: " + ChatColor.WHITE + uuid);
        sender.sendMessage(ChatColor.YELLOW + "Spawn Location: " + ChatColor.WHITE +
                loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + " (" + (loc.getWorld() != null ? loc.getWorld().getName() : "world") + ")");
    }

    private void handleReload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(ChatColor.GREEN + "SpiralGenesis configuration and storage reloaded successfully.");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SpiralGenesis Admin Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/sgen setcenter [x z]" + ChatColor.WHITE + " - Sets the global origin.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen setspawn <player> [x y z]" + ChatColor.WHITE + " - Sets a player's spawn.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen reassign <player>" + ChatColor.WHITE + " - Allocates fresh spiral plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen tp <player>" + ChatColor.WHITE + " - Teleports to a player's plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen info <player>" + ChatColor.WHITE + " - Inspects player's genesis plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen reload" + ChatColor.WHITE + " - Reloads configuration.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("spiralgenesis.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = Arrays.asList("setcenter", "setspawn", "reassign", "tp", "info", "reload");
            List<String> matches = new ArrayList<>();
            for (String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    matches.add(sub);
                }
            }
            return matches;
        }

        if (args.length == 2 && Arrays.asList("setspawn", "reassign", "tp", "info").contains(args[0].toLowerCase())) {
            List<String> playerNames = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    playerNames.add(p.getName());
                }
            }
            return playerNames;
        }

        return Collections.emptyList();
    }
}
