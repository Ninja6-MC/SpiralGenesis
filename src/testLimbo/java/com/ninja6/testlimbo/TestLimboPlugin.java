package com.ninja6.testlimbo;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * A stand-in login plugin for the CI smoke test. Never shipped.
 *
 * <p>Exists because the real thing cannot be tested here. Verifying SpiralGenesis against
 * AuthMe would mean downloading it, giving it a database, and driving a Minecraft client;
 * verifying it against nLogin is impossible at any price, because nLogin is closed source.
 * This reproduces what those plugins were observed to do instead, so the behaviour under
 * test is a transcription of real code rather than an assumption about it.
 *
 * <p>Each mode was read out of a specific implementation:
 *
 * <ul>
 *   <li>{@code PIN} - AuthMe 5.6.0. {@code onPlayerMove} at {@code HIGHEST} calls
 *       {@code event.setTo(event.getFrom())} and never sets the cancel flag. It exempts a
 *       move only when the block column is unchanged <em>and</em> the player is not
 *       climbing, so straight-down falling is left alone and drifting is not.
 *   <li>{@code PIN_ALLOW_FALL} - OpeNLogin. The same pin at {@code HIGH}, except it returns
 *       early while the player is descending rather than fighting gravity.
 *   <li>{@code CANCEL} - LibreLogin's Paper platform. {@code onMove} at {@code LOWEST}
 *       cancels outright once it sees the position changed.
 * </ul>
 *
 * <p>Interaction and item drops are cancelled at {@code LOWEST} in every mode, which all
 * three do.
 *
 * <p>Each mode reproduces its plugin's <em>default</em> configuration only. AuthMe's
 * {@code allowMovement} branches are deliberately omitted: with them enabled an
 * unauthenticated player really can walk, so movement stops being evidence of anything and
 * no gate could read that limbo. That is the documented limitation, not a case to cover.
 */
public class TestLimboPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private enum Mode { PIN, PIN_ALLOW_FALL, CANCEL }

    private Mode mode = Mode.PIN;

    /** Whether the limbo is currently holding players, i.e. nobody has "authenticated". */
    private volatile boolean holding = true;

    @Override
    public void onEnable() {
        String configured = getConfig().getString("mode", System.getProperty("testlimbo.mode", "PIN"));
        try {
            mode = Mode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Unknown mode '" + configured + "', using PIN.");
        }
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("testlimbo").setExecutor(this);
        getLogger().info("TESTLIMBO enabled mode=" + mode + " holding=" + holding);
    }

    // ---------------------------------------------------------------------
    // Limbo enforcement
    // ---------------------------------------------------------------------

    /**
     * AuthMe and OpeNLogin: pin without cancelling.
     *
     * <p>The two differ in what they exempt, and the difference is the whole point of
     * having both modes. AuthMe exempts a move only when the block column is unchanged and
     * the player is not climbing. OpeNLogin exempts <em>any</em> descending move with no
     * block-column test at all, so a held player falling with horizontal drift is left
     * entirely alone - which is the case a gate reading only cancellation would miss.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMovePinning(PlayerMoveEvent event) {
        if (!holding || mode == Mode.CANCEL || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();

        if (mode == Mode.PIN_ALLOW_FALL) {
            if (from.getY() > to.getY()) {
                return; // Descending, drift or not: OpeNLogin leaves gravity alone.
            }
        } else if (from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()
                && from.getY() - to.getY() >= 0) {
            return; // Straight down or stationary: what AuthMe declines to fight.
        }
        event.setTo(from);
    }

    /** LibreLogin: cancel outright, at the priority it uses. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMoveCancelling(PlayerMoveEvent event) {
        if (!holding || mode != Mode.CANCEL || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        cancelIfHolding(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        cancelIfHolding(event);
    }

    private void cancelIfHolding(Cancellable event) {
        if (holding) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------------
    // Console interface
    // ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "release" -> {
                holding = false;
                getLogger().info("TESTLIMBO holding=false");
            }
            case "hold" -> {
                holding = true;
                getLogger().info("TESTLIMBO holding=true");
            }
            case "handlers" -> reportHandlers();
            default -> sender.sendMessage("/testlimbo <release|hold|handlers>");
        }
        return true;
    }

    /**
     * Prints who is registered for the gate's events, in the order the server will call
     * them.
     *
     * <p>This is the assertion the smoke test actually cares about. SpiralGenesis reads a
     * cancellation verdict, which is only correct if it runs after everything that could
     * cancel. That ordering is a property of the live server's handler list, not of
     * SpiralGenesis's own source, so it cannot be checked by a unit test and is exactly the
     * kind of thing that breaks silently when a priority is edited.
     */
    private void reportHandlers() {
        report("PlayerMoveEvent", PlayerMoveEvent.getHandlerList());
        report("PlayerInteractEvent", PlayerInteractEvent.getHandlerList());
        report("PlayerDropItemEvent", PlayerDropItemEvent.getHandlerList());
    }

    private void report(String eventName, HandlerList handlers) {
        StringJoiner joiner = new StringJoiner(" ");
        for (RegisteredListener listener : handlers.getRegisteredListeners()) {
            joiner.add(listener.getPlugin().getName() + "@" + listener.getPriority());
        }
        getLogger().info("TESTLIMBO handlers " + eventName + " " + joiner);
    }
}
