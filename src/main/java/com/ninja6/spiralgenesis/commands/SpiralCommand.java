package com.ninja6.spiralgenesis.commands;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.manager.SpawnSimulator;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Administrative /sgen command suite for SpiralGenesis.
 */
public class SpiralCommand implements CommandExecutor, TabCompleter {

    /**
     * Upper bound on {@code /sgen simulate}. Each sample generates chunks, so an unbounded
     * count typed on a live server would be a self-inflicted outage.
     */
    private static final int MAX_SIMULATE_SAMPLES = 500;

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
            case "allocate" -> handleAllocate(sender, args);
            case "reassign" -> handleReassign(sender, args);
            case "tp" -> handleTp(sender, args);
            case "info" -> handleInfo(sender, args);
            case "simulate" -> handleSimulate(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * Records a spiral origin change. Moving the centre does not move anyone already
     * allocated, so an admin reading the log later needs to know when it happened to
     * explain why two players' plots do not line up on the same grid.
     */
    private void logCenterChange(CommandSender sender, int x, int z) {
        plugin.getLogger().info(sender.getName() + " set the spiral center to (" + x + ", " + z
                + "). Players already allocated keep their existing spawns.");
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
            logCenterChange(sender, x, z);
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
                logCenterChange(sender, x, z);
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
        plugin.getLogger().info(sender.getName() + " manually set spawn for " + target.getName()
                + " to (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                + ") in world '" + loc.getWorld().getName() + "'. The spiral index is unchanged.");
    }

    /**
     * Allocates a player who is being held by the action gate.
     *
     * <p>Exists to be run from a login plugin's own "commands to execute on login" config,
     * which every such plugin has. That makes SpiralGenesis usable behind a login plugin it
     * cannot read - nLogin and JPremium are closed source, and one suppressing actions below
     * the Bukkit event layer would produce no uncancelled action to gate on - without
     * SpiralGenesis depending on, or knowing about, that plugin.
     *
     * <p>Distinct from {@code reassign}, which discards an existing plot and burns a fresh
     * spiral index. This only allocates a player who does not have one, so wiring it to
     * every login is safe: returning players are a no-op rather than being relocated away
     * from whatever they have built.
     */
    private void handleAllocate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen allocate <player>");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or offline.");
            return;
        }

        if (plugin.getDataStorage().hasSpawn(target.getUniqueId())) {
            sender.sendMessage(ChatColor.YELLOW + target.getName()
                    + " already has a plot; nothing to do. Use /sgen reassign to move them.");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Allocating " + target.getName() + "...");
        plugin.getLogger().info(sender.getName() + " released " + target.getName()
                + " from the allocation gate manually.");
        plugin.allocateNow(target, "JAVA");
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

        SpawnManager spawnManager = plugin.getSpawnManager();
        if (spawnManager == null) {
            sender.sendMessage(ChatColor.RED + "Spiral world is unavailable; cannot reassign. Check the console for details.");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Reallocating fresh safe spiral plot for " + target.getName() + "...");

        spawnManager.allocateNextSafeSpawn(plugin.getDataStorage()::reserveNextIndex).thenAccept(res -> {
            // Player state must be touched on the thread owning that player: the entity
            // scheduler on Folia, the main thread on Paper.
            target.getScheduler().run(plugin, task -> {
                if (!target.isOnline()) {
                    sender.sendMessage(ChatColor.RED + target.getName() + " went offline before reassignment completed.");
                    return;
                }
                plugin.getDataStorage().setSpawn(target.getUniqueId(), res.location(), res.index(), res.gridU(), res.gridV(), target.getName(), "REASSIGN");
                target.setRespawnLocation(res.location(), true);
                target.teleportAsync(res.location()).thenAccept(success -> {
                    sender.sendMessage(ChatColor.GREEN + "Successfully reassigned " + target.getName() + " to index #" +
                            res.index() + " at (" + res.location().getBlockX() + ", " + res.location().getBlockY() + ", " + res.location().getBlockZ() + ")");
                    // Logged after the teleport resolves so the line reflects what actually
                    // happened; the spawn itself is already recorded either way.
                    plugin.getLogger().info(sender.getName() + " reassigned " + target.getName()
                            + " to plot #" + res.index() + " (grid " + res.gridU() + ", " + res.gridV()
                            + ") at (" + res.location().getBlockX() + ", " + res.location().getBlockY()
                            + ", " + res.location().getBlockZ() + ")"
                            + (Boolean.TRUE.equals(success) ? "" : " - spawn recorded, but the teleport did not complete"));
                });
            }, () -> sender.sendMessage(ChatColor.RED + target.getName()
                    + " went offline before reassignment completed."));
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to reassign " + target.getName(), ex);
            sender.sendMessage(ChatColor.RED + "Reassignment failed; check the console for details.");
            return null;
        });
    }

    /**
     * Resolves a player name to a UUID without blocking the server thread.
     *
     * <p>{@code Bukkit.getOfflinePlayer(String)} can issue a synchronous profile lookup
     * against Mojang's API, so it is not usable from a command handler. Online players and
     * previously recorded assignments cover every name this command can act on.
     */
    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return plugin.getDataStorage().findByName(name);
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

        UUID uuid = resolveUuid(args[1]);
        if (uuid == null || !plugin.getDataStorage().hasSpawn(uuid)) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " has no recorded genesis spawn.");
            return;
        }

        Location loc = plugin.getDataStorage().getSpawn(uuid);
        if (loc == null) {
            sender.sendMessage(ChatColor.RED + "That plot's world is not currently loaded.");
            return;
        }
        player.teleportAsync(loc).thenAccept(success -> {
            player.sendMessage(ChatColor.GREEN + "Teleported to " + args[1] + "'s genesis spawn.");
        });
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen info <player>");
            return;
        }

        UUID uuid = resolveUuid(args[1]);
        StoredSpawn record = uuid == null ? null : plugin.getDataStorage().getRecord(uuid);
        if (record == null) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " has no recorded genesis spawn.");
            return;
        }

        // Reported from the stored record so info still works when the world is unloaded.
        sender.sendMessage(ChatColor.GOLD + "=== SpiralGenesis Info: " + args[1] + " ===");
        sender.sendMessage(ChatColor.YELLOW + "UUID: " + ChatColor.WHITE + uuid);
        sender.sendMessage(ChatColor.YELLOW + "Client: " + ChatColor.WHITE + record.clientType());
        sender.sendMessage(ChatColor.YELLOW + "Spiral Index: " + ChatColor.WHITE + record.index()
                + ChatColor.GRAY + " (grid " + record.gridU() + ", " + record.gridV() + ")");
        sender.sendMessage(ChatColor.YELLOW + "Spawn Location: " + ChatColor.WHITE +
                (int) record.x() + ", " + (int) record.y() + ", " + (int) record.z()
                + " (" + record.worldName() + ")");
    }

    /**
     * Runs allocation repeatedly against the live world and reports the aggregate, without
     * involving a player or touching the live spiral index.
     *
     * <p>This is the only way to exercise the terrain rules without a game client, so it is
     * also what CI drives to keep the safety thresholds honest against generated terrain.
     */
    private void handleSimulate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen simulate <count>");
            return;
        }

        int samples;
        try {
            samples = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Count must be a number.");
            return;
        }
        if (samples < 1 || samples > MAX_SIMULATE_SAMPLES) {
            sender.sendMessage(ChatColor.RED + "Count must be between 1 and " + MAX_SIMULATE_SAMPLES + ".");
            return;
        }

        SpawnManager spawnManager = plugin.getSpawnManager();
        if (spawnManager == null) {
            sender.sendMessage(ChatColor.RED + "Spiral world is unavailable; cannot simulate.");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Simulating " + samples
                + " allocations against live terrain; this generates chunks and may take a while...");

        SpawnSimulator.run(spawnManager, samples).thenAccept(report -> {
            // Logged rather than only messaged: the console log is what CI greps, and the
            // sender may well be the console anyway.
            plugin.getLogger().info(report.toSummaryLine());
            plugin.getLogger().info(report.toRejectionLine());

            reply(sender, () -> {
                sender.sendMessage(ChatColor.GOLD + "=== SpiralGenesis Simulation ===");
                sender.sendMessage(ChatColor.YELLOW + "Allocations: " + ChatColor.WHITE
                        + report.completed() + "/" + report.samples());
                sender.sendMessage(ChatColor.YELLOW + "Indices per spawn: " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.2f", report.indicesPerSpawn())
                        + ChatColor.GRAY + " (1.00 is ideal; high means cells are being rejected)");
                sender.sendMessage(ChatColor.YELLOW + "Candidates probed: " + ChatColor.WHITE
                        + report.candidatesProbed());
                sender.sendMessage(ChatColor.YELLOW + "Fallbacks: " + ChatColor.WHITE + report.fallbacks());
                sender.sendMessage(ChatColor.YELLOW + "Surface Y range: " + ChatColor.WHITE
                        + report.minSurfaceY() + " to " + report.maxSurfaceY());
                sender.sendMessage(ChatColor.YELLOW + "Rejections: " + ChatColor.WHITE
                        + (report.rejections().isEmpty() ? "none" : report.rejections().toString()));
            });
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Spawn simulation failed", ex);
            reply(sender, () -> sender.sendMessage(
                    ChatColor.RED + "Simulation failed; check the console for details."));
            return null;
        });
    }

    /**
     * Delivers a reply on the thread that owns the sender.
     *
     * <p>Allocation callbacks resolve on whichever region thread finished the last probe,
     * which on Folia is generally not the region owning the admin who typed the command,
     * and Player API rejects calls from a foreign region. The console sender has no owning
     * region, so it is written to directly.
     */
    private void reply(CommandSender sender, Runnable send) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> send.run(), null);
        } else {
            send.run();
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(ChatColor.GREEN + "SpiralGenesis configuration and storage reloaded successfully.");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SpiralGenesis Admin Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/sgen setcenter [x z]" + ChatColor.WHITE + " - Sets the global origin.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen setspawn <player> [x y z]" + ChatColor.WHITE + " - Sets a player's spawn.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen allocate <player>" + ChatColor.WHITE + " - Places a player held by the allocation gate.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen reassign <player>" + ChatColor.WHITE + " - Allocates fresh spiral plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen tp <player>" + ChatColor.WHITE + " - Teleports to a player's plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen info <player>" + ChatColor.WHITE + " - Inspects player's genesis plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen simulate <count>" + ChatColor.WHITE + " - Measures allocation against live terrain.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen reload" + ChatColor.WHITE + " - Reloads configuration.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("spiralgenesis.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = Arrays.asList("setcenter", "setspawn", "allocate", "reassign", "tp", "info", "simulate", "reload");
            List<String> matches = new ArrayList<>();
            for (String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    matches.add(sub);
                }
            }
            return matches;
        }

        if (args.length == 2 && Arrays.asList("setspawn", "allocate", "reassign", "tp", "info").contains(args[0].toLowerCase())) {
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
