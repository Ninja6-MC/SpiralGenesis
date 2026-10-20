package com.ninja6.spiralgenesis;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.protection.ClaimInspector;
import com.ninja6.spiralgenesis.protection.ClaimLookup;
import com.ninja6.spiralgenesis.protection.SpawnProtector;
import com.ninja6.spiralgenesis.storage.DataStorage;
import com.ninja6.spiralgenesis.structure.StructureEnvironment;
import com.ninja6.spiralgenesis.structure.Template;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/** The live server behind spawn plot structures. */
class PluginStructureEnvironment implements StructureEnvironment {

    private final SpiralGenesisPlugin plugin;

    PluginStructureEnvironment(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public PluginConfig config() {
        return plugin.getPluginConfig();
    }

    @Override
    public DataStorage storage() {
        return plugin.getDataStorage();
    }

    @Override
    public SpawnProtector protector() {
        return plugin.getSpawnProtector();
    }

    @Override
    public ClaimInspector inspector() {
        return plugin.getClaimInspector();
    }

    @Override
    public ClaimLookup claimLookup() {
        SpawnManager manager = plugin.getSpawnManager();
        return manager == null ? ClaimLookup.NONE : manager.getClaimLookup();
    }

    /**
     * The respawn rule, which needs the bound manager: a spawn in any other world cannot be
     * checked, so it is not taken as safe.
     */
    @Override
    public boolean isSafeSpawn(Location spawn) {
        SpawnManager manager = plugin.getSpawnManager();
        return manager != null && manager.getWorld().equals(spawn.getWorld())
                && manager.isSafeNow(spawn);
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public File dataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public void saveResource(String path) {
        plugin.saveResource(path, false);
    }

    @Override
    public Template loadTemplate(File file) throws Exception {
        return plugin.loadStructureTemplate(file);
    }

    @Override
    public CompletableFuture<?> loadChunk(World world, int chunkX, int chunkZ) {
        return world.getChunkAtAsync(chunkX, chunkZ);
    }

    @Override
    public boolean ownsChunk(World world, int chunkX, int chunkZ) {
        return plugin.getServer().isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    @Override
    public void runOnRegion(World world, int chunkX, int chunkZ, Runnable task) {
        plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    @Override
    public void runEachTick(BooleanSupplier tick) {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (tick.getAsBoolean()) {
                task.cancel();
            }
        }, 1L, 1L);
    }
}
