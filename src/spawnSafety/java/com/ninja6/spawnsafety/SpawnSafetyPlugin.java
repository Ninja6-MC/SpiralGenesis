package com.ninja6.spawnsafety;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Mutates only the disposable CI world and checks public server state. Never shipped. */
public final class SpawnSafetyPlugin extends JavaPlugin implements Listener {
    static final UUID BOT_ID = UUID.nameUUIDFromBytes("OfflinePlayer:GateProbe".getBytes(StandardCharsets.UTF_8));
    private static final UUID FOREIGN = UUID.fromString("cf39ee3f-2927-4558-b9af-685a78e787ba");
    private World world;
    private LiveClaims claims;
    private String mode;
    private int deaths;
    private int damage;
    private int expectedDeaths;
    private boolean observing;
    private int initialX;
    private int initialZ;
    private int storedX;
    private int storedZ;

    @Override
    public void onEnable() {
        getCommand("spawnsafety").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            return true;
        }
        String step = args.length == 0 ? "missing" : args[0];
        try {
            switch (step) {
                case "prepare" -> prepare(args[1]);
                case "allocation" -> checkAllocation();
                case "repair" -> prepareRepair();
                case "repaired" -> checkRepair();
                case "fallback" -> prepareFallback();
                case "kill" -> kill();
                case "held" -> checkFallback();
                default -> throw new IllegalStateException("Unknown fixture step " + step);
            }
            getLogger().info("SPAWNSAFETY PASS " + step + " mode=" + mode
                    + " deaths=" + deaths + " damage=" + damage);
        } catch (Exception | LinkageError error) {
            getLogger().severe("SPAWNSAFETY FAIL " + step + ": " + error);
        }
        return true;
    }

    private void prepare(String selected) {
        require(selected.equals("own") || selected.equals("trusted") || selected.equals("none"), "Unknown mode");
        mode = selected;
        world = Bukkit.getWorld("world");
        require(world != null, "Missing world");
        world.setTime(6000);
        world.setStorm(false);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule minecraft:spawn_mobs false");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule minecraft:fire_spread_radius_around_player 0");
        // Four candidates in a 64-block cell. Clear above all floors so generated trees
        // and terrain cannot make a claimed point accidentally fail a terrain check.
        platform(0, 0);
        platform(16, 0);
        platform(16, 16);
        platform(0, 16);
        platform(80, 0);
        world.setSpawnLocation(80, 100, 0);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule minecraft:respawn_radius 0");
        boolean installed = Bukkit.getPluginManager().isPluginEnabled("GriefPrevention");
        require(installed == !mode.equals("none"), "Optional GP presence differs from requested mode");
        if (installed) {
            claims = new LiveClaims();
            // The centre is unclaimed, but the left edge of its 9x9 spawn square touches
            // a foreign claim. The next candidate is an administrative claim. Both must
            // be skipped even with automatic spawn protection disabled.
            claims.create(world, -9, 0, FOREIGN, null);
            claims.create(world, 16, 0, null, null);
            claims.checkAllocationPremises(world);
        }
        require(!YamlConfiguration.loadConfiguration(recordFile()).contains("players." + BOT_ID),
                "Reused player data would bypass allocation");
    }

    private void platform(int centreX, int centreZ) {
        for (int x = centreX - 9; x <= centreX + 9; x++) {
            for (int z = centreZ - 9; z <= centreZ + 9; z++) {
                for (int y = 100; y < world.getMaxHeight(); y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
                world.getBlockAt(x, 99, z).setType(Material.STONE, false);
            }
        }
    }

    private void checkAllocation() throws Exception {
        Player bot = bot();
        require(bot.getGameMode() == GameMode.SURVIVAL, "Bot must take real survival damage");
        initialX = mode.equals("none") ? 0 : 16;
        initialZ = mode.equals("none") ? 0 : 16;
        checkAt(initialX, 100, initialZ);
        checkRecord(initialX, initialZ);
        if (claims != null) {
            claims.checkSquare(new Location(world, initialX, 100, initialZ), false);
        }
        storedX = initialX;
        storedZ = initialZ;
        observing = true;
        damage = 0;
        deaths = 0;
        expectedDeaths = 0;
    }

    private void prepareRepair() {
        require(claims != null, "Claim repair requires real GP");
        // The edge-claimed centre and administrative second candidate remain safe terrain;
        // the assigned third candidate becomes lava. The fourth is the permitted control.
        require(bot().teleport(new Location(world, 80.5, 100, 0.5)), "Cannot stage bot before repair");
        ruin(initialX, initialZ);
        claims.create(world, 0, 16, mode.equals("own") ? BOT_ID : FOREIGN,
                mode.equals("trusted") ? BOT_ID : null);
    }

    private void checkRepair() throws Exception {
        checkAt(0, 100, 16);
        checkRecord(0, 16);
        claims.checkSquare(new Location(world, 0, 100, 16), true);
        checkObservation();
        storedX = 0;
        storedZ = 16;
    }

    private void prepareFallback() {
        // Every configured in-cell repair candidate is lethal. The stored world-spawn
        // point is inside a four-block stone cap with a safe floor above it.
        require(bot().teleport(new Location(world, 80.5, 104, 0.5)), "Cannot stage bot before fallback");
        ruin(0, 0);
        ruin(16, 0);
        ruin(16, 16);
        ruin(0, 16);
        for (int x = 77; x <= 83; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 100; y <= 103; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE, false);
                }
            }
        }
        require(world.getBlockAt(80, 100, 0).getType().isSolid(), "World spawn was not obstructed");
    }

    private void ruin(int x, int z) {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                world.getBlockAt(x + dx, 99, z + dz).setType(Material.LAVA, false);
            }
        }
    }

    private void kill() {
        expectedDeaths++;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:kill GateProbe");
    }

    private void checkFallback() throws Exception {
        checkAt(80, 104, 0);
        checkRecord(storedX, storedZ);
        checkObservation();
        require(world.getBlockAt(80, 100, 0).getType().isSolid(), "Obstruction disappeared");
    }

    private void checkObservation() {
        require(expectedDeaths > 0 && deaths == expectedDeaths,
                "Death count differs: expected=" + expectedDeaths + " actual=" + deaths);
        require(damage == 0, "Unexpected environmental damage events=" + damage);
        require(bot().getHealth() == 20, "Bot lost health after respawn");
    }

    private void checkAt(int x, int y, int z) {
        Location at = bot().getLocation();
        require(at.getWorld() == world && Math.abs(at.getX() - (x + 0.5)) <= 2
                && Math.abs(at.getZ() - (z + 0.5)) <= 2 && Math.abs(at.getY() - y) < 0.01,
                "Unexpected live player location " + at + "; expected " + x + "," + y + "," + z);
        require(at.getBlock().isPassable() && at.clone().add(0, 1, 0).getBlock().isPassable(),
                "Live player intersects an obstruction");
        getLogger().info("SPAWNSAFETY position " + at + " health=" + bot().getHealth());
    }

    private File recordFile() {
        return new File(Bukkit.getPluginManager().getPlugin("SpiralGenesis").getDataFolder(), "data.yml");
    }

    private void checkRecord(int x, int z) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(recordFile());
        ConfigurationSection record = yaml.getConfigurationSection("players." + BOT_ID);
        require(record != null, "Persisted bot record missing");
        require(record.contains("assigned-index") && record.getInt("assigned-index") == 0
                && record.contains("centre") && record.getInt("centre") == 0
                && record.contains("grid-u") && record.getInt("grid-u") == 0
                && record.contains("grid-v") && record.getInt("grid-v") == 0,
                "Repair/fallback changed plot identity");
        require(record.getString("world", "").equals("world")
                && Math.abs(record.getDouble("x") - (x + 0.5)) < 0.01
                && Math.abs(record.getDouble("z") - (z + 0.5)) < 0.01
                && record.getDouble("y") == 100, "Persisted spawn coordinates differ: " + record.getValues(false));
        getLogger().info("SPAWNSAFETY persisted " + record.getValues(false));
    }

    private Player bot() {
        Player player = Bukkit.getPlayerExact("GateProbe");
        require(player != null && player.isOnline(), "Real bot disconnected or absent");
        return player;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (observing && event.getEntity().getUniqueId().equals(BOT_ID)
                && event.getCause() != EntityDamageEvent.DamageCause.KILL) {
            damage++;
            getLogger().warning("SPAWNSAFETY damage " + event.getCause());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (observing && event.getEntity().getUniqueId().equals(BOT_ID)) {
            deaths++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
