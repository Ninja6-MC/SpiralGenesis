package com.ninja6.spiralgenesis.commands;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import com.ninja6.spiralgenesis.manager.CellReserver;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.manager.SpawnSimulator;
import com.ninja6.spiralgenesis.protection.SpawnProtectionBackfill;
import com.ninja6.spiralgenesis.protection.SpawnProtector;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /**
     * Sent when the plot could not be judged either way. The teleport still happens:
     * a check that failed is not a reason to strand an admin who asked to go.
     */
    private static final String UNCHECKED =
            "That plot could not be re-checked; you are arriving without knowing.";

    /**
     * The one argument on {@code reassign} that lets it delete anything.
     *
     * <p>A trailing literal token, because that is how this command already reads arguments -
     * a lowercase word compared case-insensitively, the same shape as every subcommand name.
     * No dashes and no {@code --} prefix: nothing in {@code /sgen} parses one today, and
     * introducing a second argument grammar for a single flag would mean every later
     * argument has to pick a side.
     *
     * <p>It is spelled out rather than defaulted the other way round because of what it
     * does. Every other path in this plugin leaves an old spawn claim standing; this is the
     * only one that removes one, and an operator has to have typed the word.
     */
    private static final String RELEASE_FLAG = "release";

    /** Subcommands that depend on stored records, and are refused while storage is failed. */
    private static final Set<String> STORAGE_SUBCOMMANDS =
            Set.of("setspawn", "allocate", "reassign", "protect", "tp", "info");

    private final SpiralGenesisPlugin plugin;

    public SpiralCommand(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(SpiralGenesisPlugin.ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to execute SpiralGenesis commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        // Every subcommand that reads or writes a player's record is refused while data.yml
        // could not be read. The records are gone from memory, so these would report every
        // player as unallocated, or record a spawn that nothing can save. Reload is how the
        // state is cleared, and setcenter and simulate touch no record.
        String storageNotice = plugin.storageFailureNotice();
        if (storageNotice != null && STORAGE_SUBCOMMANDS.contains(sub)) {
            sender.sendMessage(ChatColor.RED + storageNotice);
            return true;
        }
        switch (sub) {
            case "setcenter" -> handleSetCenter(sender, args);
            case "setspawn" -> handleSetSpawn(sender, args);
            case "allocate" -> handleAllocate(sender, args);
            case "reassign" -> handleReassign(sender, args);
            case "protect" -> handleProtect(sender, args);
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

        // Read before the write, because the write replaces it and the old point is what
        // says where the claim that is about to go stale is standing.
        StoredSpawn previous = plugin.getDataStorage().getRecord(target.getUniqueId());
        Location oldSpawn = previous == null ? null : previous.toLocation();

        // Gated on the write rather than on the storage check made before this command ran:
        // a reload that fails to read data.yml can land in between, and a refused write
        // records nothing for the respawn point and the claim below to agree with.
        if (!plugin.getDataStorage().setSpawn(target.getUniqueId(), loc, -1, 0, 0, target.getName(), "MANUAL")) {
            sender.sendMessage(ChatColor.RED + "Spawn for " + target.getName()
                    + " was not changed: " + refusedWriteNotice());
            return;
        }
        target.setRespawnLocation(loc, true);
        sender.sendMessage(ChatColor.GREEN + "Set spawn for " + target.getName() + " to: " +
                loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        plugin.getLogger().info(sender.getName() + " manually set spawn for " + target.getName()
                + " to (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                + ") in world '" + loc.getWorld().getName() + "'. The spiral index is unchanged.");

        // A command handler runs on the server thread, which is the thread the provider
        // requires and the thread the spawn was just written from, so the claim goes in
        // directly rather than through a scheduler hop that would only separate the two.
        plugin.getSpawnProtector().protect(target.getUniqueId(), loc, "sgen setspawn");
        // No release flag here, unlike reassign, and it is the argument grammar that decides
        // that rather than a different answer to the same question. `setspawn` already ends
        // in an optional `[x y z]`, so a trailing literal token after it could not be told
        // apart from a coordinate without inventing the prefixed-flag grammar this command
        // does not have. Leaving the old claim is the default on every path in any case; an
        // operator who wants it gone has their protection plugin's own commands, and the
        // line below tells them exactly where to stand.
        String stale = plugin.getSpawnProtector().handOffStaleClaim(target.getUniqueId(),
                target.getName(), oldSpawn, loc, false, "Remove it with your protection "
                        + "plugin's own commands if it is no longer wanted.");
        if (stale != null) {
            sender.sendMessage(ChatColor.YELLOW + stale);
        }
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

        // Refused here as well as in allocation, so the operator is told why rather than
        // seeing "Allocating" and nothing after it. This command is wired to login hooks, so
        // it must not be the way round the skip; reassign is the deliberate one.
        if (plugin.isPreInstallPlayer(target)) {
            sender.sendMessage(ChatColor.YELLOW + target.getName() + " played on this server"
                    + " before SpiralGenesis was installed, so they are not allocated a plot."
                    + " Use /sgen reassign " + target.getName() + " to give them one.");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Allocating " + target.getName() + "...");
        plugin.getLogger().info(sender.getName() + " released " + target.getName()
                + " from the allocation gate manually.");
        // Asking Floodgate rather than assuming: this command is reachable for a Bedrock
        // player whose detection failed at join, and the client type is written to data.yml
        // permanently.
        String clientType = plugin.getFloodgateHook().isBedrockPlayer(target.getUniqueId())
                ? "BEDROCK" : "JAVA";
        plugin.allocateNow(target, clientType);
    }

    /**
     * Moves a player to a fresh spiral plot.
     *
     * <p>The old spawn claim is left standing by default and named in the output, and the
     * trailing {@link #RELEASE_FLAG} token is the only thing that removes it. That split is
     * the whole design of this command's protection behaviour: a spawn claim is a few blocks
     * of ground on the day it is made and somebody's chest, bed and first night's work by
     * the time anyone reassigns them, so deleting it as an unannounced side effect of a
     * command about somewhere else is not a mistake anybody can undo.
     */
    private void handleReassign(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen reassign <player> [" + RELEASE_FLAG + "]");
            return;
        }

        boolean releaseOld = false;
        if (args.length == 3) {
            if (!args[2].equalsIgnoreCase(RELEASE_FLAG)) {
                // Refused rather than ignored. A typo in the one word whose job is to
                // authorise a deletion must not be read as that word, and silently dropping
                // an argument an operator typed is how they come away believing the old
                // claim was removed when it was not.
                sender.sendMessage(ChatColor.RED + "Unknown argument '" + args[2]
                        + "'. Usage: /sgen reassign <player> [" + RELEASE_FLAG + "]");
                return;
            }
            releaseOld = true;
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

        // Captured here rather than inside the callback: by the time that runs the store
        // has been rewritten, and this is the only record of where the claim about to go
        // stale actually stands.
        StoredSpawn previous = plugin.getDataStorage().getRecord(target.getUniqueId());
        Location oldSpawn = previous == null ? null : previous.toLocation();
        boolean release = releaseOld;

        sender.sendMessage(ChatColor.YELLOW + "Reallocating fresh safe spiral plot for " + target.getName() + "...");

        spawnManager.allocateNextSafeSpawn(CellReserver.of(plugin.getDataStorage())).thenAccept(outcome -> {
            SpawnManager.LocationResult res;
            switch (outcome) {
                case SpawnManager.LocationResult found -> res = found;
                case SpawnManager.BorderExhausted exhausted -> {
                    // Not logged: the scan that gave up has already said so on the console,
                    // once. The operator who asked is owed the reason, and nothing moved.
                    reply(sender, () -> sender.sendMessage(ChatColor.RED + "Could not reassign "
                            + target.getName() + "; they keep their current spawn. "
                            + exhausted.message()));
                    return;
                }
            }
            // Player state must be touched on the thread owning that player: the entity
            // scheduler on Folia, the main thread on Paper.
            ScheduledTask scheduled = target.getScheduler().run(plugin, task -> {
                if (!target.isOnline()) {
                    abandonReassignment(sender, target, res);
                    return;
                }
                // A reload that failed to read data.yml can land during the scan, or between
                // any check made here and the write, so the write's own answer gates
                // everything after it. A refused write records nothing: the old plot is still
                // the player's, so neither claim is touched, and nothing is moved.
                if (!plugin.getDataStorage().setSpawn(target.getUniqueId(), res.location(),
                        res.centre(), res.index(), res.gridU(), res.gridV(), target.getName(),
                        "REASSIGN", false)) {
                    plugin.getLogger().warning("Reassignment of " + target.getName() + " to plot "
                            + res.plotLabel() + " by " + sender.getName() + " was not recorded, because"
                            + " data.yml could not be read when it was written. Nothing was"
                            + " changed.");
                    reply(sender, () -> sender.sendMessage(ChatColor.RED + "Reassignment of "
                            + target.getName() + " was abandoned and nothing was changed: "
                            + refusedWriteNotice()));
                    return;
                }

                // Both protection calls happen here, inside the task that owns the player -
                // the main thread wherever a real provider exists - and not in the teleport
                // callback below, which resolves on whichever thread finished the teleport
                // and would breach the provider's threading contract.
                plugin.getSpawnProtector().protect(target.getUniqueId(), res.location(),
                        "sgen reassign");
                String staleNotice = plugin.getSpawnProtector().handOffStaleClaim(
                        target.getUniqueId(), target.getName(), oldSpawn, res.location(), release,
                        // Deliberately not "re-run with release to remove it", which is what
                        // this said and was not true of anything. `release` acts on the spawn
                        // the player is leaving at the moment it runs, and `reassign` always
                        // moves them, so re-running it would send them on to a third plot and
                        // release the claim around the second - never the square named in the
                        // line this hint is attached to, which is left exactly where it is.
                        // Nothing on this command can reach that square; the protection
                        // plugin's own commands can, which is what `setspawn` already says in
                        // exactly this situation. The second sentence then puts `release`
                        // where it belongs, as something to decide before a reassignment
                        // rather than reached for after one.
                        "Remove it with your protection plugin's own commands if it is no "
                                + "longer wanted. The '" + RELEASE_FLAG + "' argument releases "
                                + "the claim a player is leaving at the moment it runs, so "
                                + "re-running this command with it would move " + target.getName()
                                + " on again and release the claim around the plot they are on "
                                + "now, leaving this one exactly where it is.");
                // Sent from here rather than from the teleport callback below. That callback
                // has no failure branch, so a teleport that fails outright would swallow this
                // line - and when the flag was given, this line is the only report that a
                // claim was deleted. An operator never being told about a deletion because an
                // unrelated teleport failed is not a trade worth making for message ordering.
                // reply() rather than a bare sendMessage for the usual reason: a Player
                // sender has to be written to on their own region thread, and this task is
                // the target's thread, not theirs.
                if (staleNotice != null) {
                    reply(sender, () -> sender.sendMessage(ChatColor.YELLOW + staleNotice));
                }

                target.setRespawnLocation(res.location(), true);
                target.teleportAsync(res.location()).thenAccept(success -> {
                    sender.sendMessage(ChatColor.GREEN + "Successfully reassigned " + target.getName() + " to plot " +
                            res.plotLabel() + " at (" + res.location().getBlockX() + ", " + res.location().getBlockY() + ", " + res.location().getBlockZ() + ")");
                    // Logged after the teleport resolves so the line reflects what actually
                    // happened; the spawn itself is already recorded either way.
                    plugin.getLogger().info(sender.getName() + " reassigned " + target.getName()
                            + " to plot " + res.plotLabel() + " (grid " + res.gridU() + ", " + res.gridV()
                            + ") at (" + res.location().getBlockX() + ", " + res.location().getBlockY()
                            + ", " + res.location().getBlockZ() + ")"
                            + (Boolean.TRUE.equals(success) ? "" : " - spawn recorded, but the teleport did not complete"));
                });
            }, () -> abandonReassignment(sender, target, res));
            // A refused task runs neither callback, and the scheduler refuses only an entity
            // already retired, which is the same departure.
            if (scheduled == null) {
                abandonReassignment(sender, target, res);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to reassign " + target.getName(), ex);
            sender.sendMessage(ChatColor.RED + "Reassignment failed; check the console for details.");
            return null;
        });
    }

    /**
     * Drops a reassignment whose player left before it could be applied.
     *
     * <p>The plot it found is never written, so nothing else ends the cell's reservation:
     * left in flight, it would keep other centres off that ground until a restart. Released
     * here, and the player keeps the plot they had.
     */
    private void abandonReassignment(CommandSender sender, Player target,
                                     SpawnManager.LocationResult res) {
        plugin.getDataStorage().releaseCell(res.centre(), res.index());
        reply(sender, () -> sender.sendMessage(ChatColor.RED + target.getName()
                + " went offline before reassignment completed."));
    }

    /**
     * Claims the spawn square for everyone who was allocated before protection existed.
     *
     * <p>Allocation fires once per player, for a player who has no plot yet, so switching
     * the feature on protects the next person to join and nobody who was already there.
     * Nothing in the ordinary run of the plugin ever revisits them, which leaves this as
     * either a startup pass or a command. It is a command: a startup pass would run on every
     * boot of every server whether it was wanted or not, and it would run at the moment a
     * server can least afford several thousand main-thread claims.
     *
     * <p>Safe to run twice, and safe to run on a live server. The provider answers
     * "already claimed" for a square this has already done, which is counted as skipped, so
     * a second pass is a no-op without any record of the first having happened. The work is
     * spread a few entries per tick by the job itself; see {@code SpawnProtectionBackfill}
     * for why the bound is a clock and not only a count.
     *
     * <p>Named {@code protect} and gated on the existing {@code spiralgenesis.admin}, with
     * no new root command and no new permission node.
     */
    private void handleProtect(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /sgen protect");
            return;
        }

        SpawnProtector protector = plugin.getSpawnProtector();
        if (!protector.isActive()) {
            // The likeliest reason by far is that this server simply does not have spawn
            // protection on, so the message names the two settings and the log rather than
            // reporting a failure.
            sender.sendMessage(ChatColor.RED + "Spawn protection is not active, so there is "
                    + "nothing to backfill. Check protection.enabled and protection.provider "
                    + "in config.yml, and the startup log for which provider was chosen.");
            return;
        }

        if (plugin.isProtectionBackfillRunning()) {
            sender.sendMessage(ChatColor.YELLOW + "A spawn protection backfill is already "
                    + "running; wait for it to report before starting another.");
            return;
        }

        SpawnProtectionBackfill job = plugin.startProtectionBackfill(summary ->
                reply(sender, () -> sender.sendMessage(ChatColor.GREEN + summary)));
        if (job == null) {
            // Either another operator won the race between the check above and the start, or
            // the repeating task could not be scheduled at all. Both are "it did not start",
            // and the console carries the detail for the second.
            sender.sendMessage(ChatColor.YELLOW + "The spawn protection backfill did not "
                    + "start; another may already be running, or scheduling was refused. "
                    + "Check the console.");
            return;
        }
        if (job.total() > 0) {
            sender.sendMessage(ChatColor.YELLOW + "Claiming spawn squares for " + job.total()
                    + " stored spawns, a few per tick. Spawns that are already claimed are "
                    + "skipped, so running this again is safe.");
        }
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

        SpawnManager spawnManager = plugin.getSpawnManager();
        if (spawnManager == null) {
            teleportToPlot(player, loc, args[1], null); // Nothing available to re-check with.
            return;
        }

        // The re-check cannot happen here. Reading a block on Folia is only legal from the
        // thread owning its chunk, and that chunk is usually not even loaded - nobody is
        // standing on the plot, which is why the admin is going. SpawnManager awaits the
        // chunk and only then hops onto its owner, which is the one ordering Folia permits.
        sender.sendMessage(ChatColor.YELLOW + "Checking " + args[1] + "'s plot...");
        CompletableFuture<Boolean> check;
        try {
            check = spawnManager.revalidate(loc);
        } catch (Throwable t) {
            // The re-check requests the chunk before it returns its future, so a throw
            // there escapes before anything is attached to it. Warn and proceed still
            // applies, or the admin is stranded by the check meant to inform them.
            plugin.getLogger().log(Level.SEVERE, "Re-check of " + args[1]
                    + "'s plot failed before it could start.", t);
            teleportToPlot(player, loc, args[1], UNCHECKED);
            return;
        }

        check.whenComplete((safe, ex) -> {
            String warning;
            if (ex != null) {
                plugin.getLogger().log(Level.WARNING, "Could not re-check " + args[1]
                        + "'s plot before teleporting " + player.getName() + " to it.", ex);
                warning = UNCHECKED;
            } else if (Boolean.TRUE.equals(safe)) {
                warning = null;
            } else {
                warning = "That plot is no longer safe to stand on. Going anyway - watch yourself.";
            }
            teleportToPlot(player, loc, args[1], warning);
        });
    }

    /**
     * Sends the admin to a plot, warning first when it did not pass its re-check.
     *
     * <p>Warn and proceed, rather than refuse or relocate. Going to look at a plot that has
     * been flooded, dug out or set on fire is a legitimate reason to run this command, and
     * this is the remedy both the admin guide and the refused-teleport warning name - so
     * blocking it would take the tool away from the situation it exists for. Relocating the
     * admin would be worse: they asked for a specific point, not a nearby safe one.
     *
     * <p>The warning goes out before the teleport rather than after it, so an admin who did
     * not expect a hazard reads it while they can still act on it.
     */
    private void teleportToPlot(Player player, Location loc, String owner, String warning) {
        // Player state belongs to the thread owning that player: the entity scheduler on
        // Folia, the main thread on Paper. The re-check above resolves on the thread owning
        // the plot's region, which is neither.
        player.getScheduler().run(plugin, task -> {
            if (warning != null) {
                player.sendMessage(ChatColor.RED + warning);
            }
            player.teleportAsync(loc).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    player.sendMessage(ChatColor.GREEN + "Teleported to " + owner + "'s genesis spawn.");
                    return;
                }
                // This command is what the admin guide names when a plot teleport is
                // refused, so a refusal here is the one thing its admin most needs told.
                // Reporting success regardless would hide it behind a green line.
                player.sendMessage(ChatColor.RED + "The teleport to " + owner
                        + "'s genesis spawn did not complete; check the console for a plugin"
                        + " cancelling teleports.");
            });
        }, null);
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
        if (record.onSpiral()) {
            SpiralCentre centre = plugin.getDataStorage().getCentre(record.centre());
            sender.sendMessage(ChatColor.YELLOW + "Plot: " + ChatColor.WHITE + record.plotLabel()
                    + ChatColor.GRAY + " (grid " + record.gridU() + ", " + record.gridV() + ")");
            sender.sendMessage(ChatColor.YELLOW + "Spiral Centre: " + ChatColor.WHITE
                    + record.centre() + ChatColor.GRAY + (centre == null
                            ? " (origin not recorded)"
                            : " (origin " + centre.originX() + ", " + centre.originZ()
                                    + ", cell-size " + centre.cellSize() + ")"));
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Plot: " + ChatColor.WHITE
                    + "none; set by hand with setspawn");
        }
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
            if (report.failure() != null) {
                // The run stopped at this sample; everything above covers the ones before it.
                plugin.getLogger().log(Level.SEVERE, "Spawn simulation stopped at sample "
                        + report.failedSample() + " of " + report.samples()
                        + "; the report covers the samples before it", report.failure());
            }

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
                sender.sendMessage(ChatColor.YELLOW + "Border exhausted: " + ChatColor.WHITE
                        + (report.borderExhausted() == 0 ? "none" : String.valueOf(report.borderExhausted())
                                + ChatColor.GRAY + " (first at sample " + report.firstExhaustedSample()
                                + ", scanning from spiral index " + report.firstExhaustedIndex()
                                + "; no plot there fit inside the world border)"));
                if (report.failure() != null) {
                    sender.sendMessage(ChatColor.RED + "Stopped at sample " + report.failedSample()
                            + " of " + report.samples() + ": " + report.failureSummary()
                            + ". Check the console for details.");
                }
            });
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Spawn simulation failed", ex);
            reply(sender, () -> sender.sendMessage(
                    ChatColor.RED + "Simulation failed; check the console for details."));
            return null;
        });
    }

    /**
     * Why a record write was refused, for the operator who asked for it.
     *
     * <p>A write is only ever refused while storage is failed, but a reload that reads the
     * file can clear that before this is read, and the operator is still owed an answer
     * that says the command did nothing.
     */
    private String refusedWriteNotice() {
        String notice = plugin.storageFailureNotice();
        return notice != null ? notice
                : "data.yml could not be read when the record was written. It has been read"
                        + " since, so run the command again.";
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
        // Checked before the green line rather than after it, because storage that is still
        // unreadable means the reload did not do the one thing the operator most likely ran
        // it for. The error goes to the console at SEVERE again as well; the copy of the
        // file is not made a second time.
        String storageNotice = plugin.storageFailureNotice();
        if (storageNotice != null) {
            sender.sendMessage(ChatColor.RED + "Configuration reloaded, but storage is still"
                    + " unavailable. " + storageNotice);
        } else {
            sender.sendMessage(ChatColor.GREEN + "SpiralGenesis configuration and storage reloaded successfully.");
        }
        // Reloading the file and binding a world are two outcomes, and only the first one
        // succeeded here. Reported to the sender rather than to the console alone: the
        // administrator correcting origin.world is the one person who needs to know the
        // correction did not take, and a green "reloaded successfully" on its own reads as
        // confirmation that it did.
        if (plugin.getSpawnManager() == null) {
            sender.sendMessage(ChatColor.RED + "No world is bound: origin.world is '"
                    + plugin.getPluginConfig().getWorldName()
                    + "', which is not a loaded world. Nothing will be allocated until it is.");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SpiralGenesis Admin Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/sgen setcenter [x z]" + ChatColor.WHITE + " - Sets the global origin.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen setspawn <player> [x y z]" + ChatColor.WHITE + " - Sets a player's spawn.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen allocate <player>" + ChatColor.WHITE + " - Places a player held by the allocation gate.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen reassign <player> [" + RELEASE_FLAG + "]"
                + ChatColor.WHITE + " - Allocates fresh spiral plot. '" + RELEASE_FLAG
                + "' also removes the claim around their old spawn; without it the old claim is kept.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen protect" + ChatColor.WHITE
                + " - Claims the spawn square for players allocated before protection was enabled.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen tp <player>" + ChatColor.WHITE + " - Teleports to a player's plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen info <player>" + ChatColor.WHITE + " - Inspects player's genesis plot.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen simulate <count>" + ChatColor.WHITE + " - Measures allocation against live terrain.");
        sender.sendMessage(ChatColor.YELLOW + "/sgen reload" + ChatColor.WHITE + " - Reloads configuration.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(SpiralGenesisPlugin.ADMIN_PERMISSION)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = Arrays.asList("setcenter", "setspawn", "allocate", "reassign", "protect", "tp", "info", "simulate", "reload");
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

        // Completed, but never the only thing offered without being typed towards: the
        // operator still has to produce the word themselves, which is the point of it being
        // a word rather than a default.
        if (args.length == 3 && "reassign".equals(args[0].toLowerCase())
                && RELEASE_FLAG.startsWith(args[2].toLowerCase())) {
            return List.of(RELEASE_FLAG);
        }

        return Collections.emptyList();
    }
}
