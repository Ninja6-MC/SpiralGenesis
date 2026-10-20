package com.ninja6.spiralgenesis.structure;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.protection.ClaimInspection;
import com.ninja6.spiralgenesis.protection.ClaimInspector;
import com.ninja6.spiralgenesis.protection.ClaimLookup;
import com.ninja6.spiralgenesis.protection.RecordingProvider;
import com.ninja6.spiralgenesis.protection.SpawnProtector;
import com.ninja6.spiralgenesis.storage.DataStorage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A server for spawn plot structures that runs everything inline and records what it was
 * asked to schedule, since MockBukkit implements neither the region schedulers, async chunk
 * loading nor the structure manager.
 */
class TestStructureEnvironment implements StructureEnvironment {

    final YamlConfiguration yaml = new YamlConfiguration();
    PluginConfig config;
    final DataStorage storage;
    final File dataFolder;
    final RecordingProvider provider = new RecordingProvider().available(false);
    int protectionSize = 9;
    final SpawnProtector protector = new SpawnProtector(Logger.getLogger("test"),
            () -> provider, () -> config.getProtectionSize());
    ClaimInspector inspector = ClaimInspector.UNAVAILABLE;
    ClaimLookup claims = ClaimLookup.NONE;
    Predicate<Location> safe = spawn -> true;
    final Map<String, Template> templates = new HashMap<>();
    final List<String> copied = new ArrayList<>();

    /** Whether this thread owns every chunk, as the main thread does on Paper. */
    boolean owns = true;
    final List<Runnable> regionTasks = new ArrayList<>();
    final List<BooleanSupplier> tickers = new ArrayList<>();
    final List<String> chunkRequests = new ArrayList<>();

    final Logger logger = Logger.getAnonymousLogger();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();

    TestStructureEnvironment(DataStorage storage, File dataFolder) {
        this.storage = storage;
        this.dataFolder = dataFolder;
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        yaml.set("structure.enabled", true);
        reconfigure();
    }

    void reconfigure() {
        config = new PluginConfig(yaml);
    }

    List<String> messages(Level level) {
        return logged.stream().filter(r -> r.getLevel() == level).map(LogRecord::getMessage).toList();
    }

    /** Runs the queue's driver until it stops, then whatever it handed to regions. */
    void drain() {
        while (!tickers.isEmpty()) {
            BooleanSupplier ticker = tickers.get(0);
            if (ticker.getAsBoolean()) {
                tickers.remove(0);
            }
            runRegionTasks();
        }
        runRegionTasks();
    }

    void runRegionTasks() {
        boolean before = owns;
        owns = true;
        while (!regionTasks.isEmpty()) {
            regionTasks.remove(0).run();
        }
        owns = before;
    }

    /** A claim of the player's at the spawn with these inclusive bounds. */
    void claimBounds(int minX, int minZ, int maxX, int maxZ) {
        provider.available(true);
        inspector = (spawn, player) -> new ClaimInspection(ClaimInspection.Status.FOUND,
                minX, minZ, maxX, maxZ, null, true, true, true);
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public DataStorage storage() {
        return storage;
    }

    @Override
    public SpawnProtector protector() {
        return protector;
    }

    @Override
    public ClaimInspector inspector() {
        return inspector;
    }

    @Override
    public ClaimLookup claimLookup() {
        return claims;
    }

    @Override
    public boolean isSafeSpawn(Location spawn) {
        return safe.test(spawn);
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public File dataFolder() {
        return dataFolder;
    }

    @Override
    public void saveResource(String path) {
        copied.add(path);
        File target = new File(dataFolder, path);
        try {
            Files.createDirectories(target.getParentFile().toPath());
            try (var in = getClass().getResourceAsStream("/" + path)) {
                if (in == null) {
                    throw new IllegalArgumentException("no resource " + path);
                }
                Files.copy(in, target.toPath());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Template loadTemplate(File file) throws Exception {
        String text = Files.readString(file.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (text.startsWith("corrupt")) {
            throw new IOException("Not in GZIP format");
        }
        Template template = templates.get(file.getName());
        if (template == null) {
            throw new IOException("unknown test template " + file.getName());
        }
        return template;
    }

    @Override
    public CompletableFuture<?> loadChunk(World world, int chunkX, int chunkZ) {
        chunkRequests.add(chunkX + "," + chunkZ);
        world.getChunkAt(chunkX, chunkZ).load();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean ownsChunk(World world, int chunkX, int chunkZ) {
        return owns;
    }

    @Override
    public void runOnRegion(World world, int chunkX, int chunkZ, Runnable task) {
        regionTasks.add(task);
    }

    @Override
    public void runEachTick(BooleanSupplier tick) {
        tickers.add(tick);
    }
}
