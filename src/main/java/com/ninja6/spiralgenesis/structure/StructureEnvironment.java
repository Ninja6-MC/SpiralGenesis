package com.ninja6.spiralgenesis.structure;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.protection.ClaimInspector;
import com.ninja6.spiralgenesis.protection.ClaimLookup;
import com.ninja6.spiralgenesis.protection.SpawnProtector;
import com.ninja6.spiralgenesis.storage.DataStorage;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * What spawn plot structures need from the rest of the plugin and from the server.
 *
 * <p>An interface rather than the plugin itself so the placement rules can run against a
 * test world: MockBukkit implements neither the region schedulers, async chunk loading,
 * the structure manager nor collision shapes.
 */
public interface StructureEnvironment {

    PluginConfig config();

    DataStorage storage();

    SpawnProtector protector();

    /** Reads the claim at a spawn; call on the thread owning the spawn's region. */
    ClaimInspector inspector();

    ClaimLookup claimLookup();

    /**
     * The spawn safety rule a stored spawn is held to on every respawn, applied to the
     * spawn after placement. Called on the thread owning the spawn's region.
     */
    boolean isSafeSpawn(Location spawn);

    Logger logger();

    /** The plugin's data folder; templates live in its {@code structures} folder. */
    File dataFolder();

    /** Copies a resource from the plugin jar into the data folder. */
    void saveResource(String path);

    /** Reads a structure file. Throws if it cannot be read. */
    Template loadTemplate(File file) throws Exception;

    /** Requests a chunk; the future completes once it is loaded. */
    CompletableFuture<?> loadChunk(World world, int chunkX, int chunkZ);

    /** Whether the current thread owns the chunk, so its blocks may be read and changed. */
    boolean ownsChunk(World world, int chunkX, int chunkZ);

    /** Runs a task on the thread owning a chunk. */
    void runOnRegion(World world, int chunkX, int chunkZ, Runnable task);

    /**
     * Runs {@code tick} once per server tick on the global region until it returns true.
     */
    void runEachTick(BooleanSupplier tick);
}
