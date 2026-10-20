package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.protection.ClaimInspection;
import com.ninja6.spiralgenesis.protection.ClaimInspector;
import com.ninja6.spiralgenesis.protection.RecordingProvider;
import com.ninja6.spiralgenesis.storage.PlacedStructure;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import com.ninja6.spiralgenesis.structure.StructureEnvironment;
import com.ninja6.spiralgenesis.structure.Template;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spawn plot structures wired into allocation: placed after the storage write and the claim
 * step, recorded once, and read again on reload.
 */
class AllocationStructureTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        for (int x = -8; x <= 40; x++) {
            for (int z = 12; z <= 36; z++) {
                world.getBlockAt(x, 62, z).setType(Material.STONE);
                world.getBlockAt(x, 63, z).setType(Material.STONE);
            }
        }
    }

    @AfterEach
    void tearDown() {
        try {
            MockBukkit.unmock();
        } catch (UnimplementedOperationException e) {
            MockBukkit.unmock();
        }
    }

    /** A 3x3 slab of gold with stored air above it, spawn spot in the middle. */
    static final class Slab implements Template {
        int placements;

        @Override
        public String name() {
            return "spawn-hut.nbt";
        }

        @Override
        public List<Block> blocks() {
            List<Block> blocks = new ArrayList<>();
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    blocks.add(new Block(x, 0, z, Material.GOLD_BLOCK, false));
                    blocks.add(new Block(x, 1, z, Material.AIR, true));
                }
            }
            return blocks;
        }

        @Override
        public void place(Location origin, StructureRotation rotation, boolean includeEntities) {
            placements++;
            for (Block block : blocks()) {
                origin.getWorld().getBlockAt(origin.getBlockX() + block.x(),
                        origin.getBlockY() + block.y(), origin.getBlockZ() + block.z())
                        .setType(block.type());
            }
        }
    }

    public static class StructurePlugin extends AllocationProtectionTest.ProtectedPlugin {
        final Slab slab = new Slab();
        final List<String> loadedFiles = new ArrayList<>();

        @Override
        StructureEnvironment createStructureEnvironment() {
            return new PluginStructureEnvironment(this) {
                @Override
                public boolean isSafeSpawn(Location spawn) {
                    return true; // MockBukkit has no collision shapes.
                }

                @Override
                public CompletableFuture<?> loadChunk(World w, int chunkX, int chunkZ) {
                    w.getChunkAt(chunkX, chunkZ).load();
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public boolean ownsChunk(World w, int chunkX, int chunkZ) {
                    return true;
                }

                @Override
                public void runEachTick(BooleanSupplier tick) {
                    while (!tick.getAsBoolean()) {
                        // One iteration stands in for one tick.
                    }
                }
            };
        }

        /** The claim the recording provider stands for: the 9x9 square at the spawn. */
        @Override
        public ClaimInspector getClaimInspector() {
            return (spawn, player) -> new ClaimInspection(ClaimInspection.Status.FOUND,
                    spawn.getBlockX() - 4, spawn.getBlockZ() - 4, spawn.getBlockX() + 4,
                    spawn.getBlockZ() + 4, null, true, true, true);
        }

        @Override
        Template loadStructureTemplate(File file) {
            loadedFiles.add(file.getName());
            return slab;
        }
    }

    private StructurePlugin load(boolean enabled) {
        StructurePlugin plugin = MockBukkit.loadWith(StructurePlugin.class,
                getClass().getResourceAsStream("/plugin.yml"));
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("allocation.action-timeout-seconds", 0);
        yaml.set("structure.enabled", enabled);
        yaml.set("structure.offset.x", 1);
        yaml.set("structure.offset.z", 1);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        plugin.reload();
        return plugin;
    }

    @Test
    @DisplayName("the structure follows the write and the claim, and is placed once")
    void placedAfterWriteAndClaim() {
        StructurePlugin plugin = load(true);
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = new InlinePlayerMock(server, "Settler");
        server.addPlayer(player);
        AtomicBoolean writtenBeforeClaim = new AtomicBoolean();
        provider.onReserve(() -> {
            StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
            writtenBeforeClaim.set(record != null
                    && plugin.getDataStorage().getStructure(record.centre(), record.index()) == null
                    && plugin.slab.placements == 0);
        });

        plugin.allocateNow(player, "JAVA");

        assertTrue(writtenBeforeClaim.get(), "stored first, claimed second, no structure yet");
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record);
        Location plot = record.toLocation();
        assertEquals(Material.GOLD_BLOCK, world.getBlockAt(plot.getBlockX(), plot.getBlockY() - 1,
                plot.getBlockZ()).getType(), "the floor replaced the ground under the spawn");
        PlacedStructure placed = plugin.getDataStorage().getStructure(record.centre(), record.index());
        assertNotNull(placed);
        assertEquals("Settler", placed.player());
        assertEquals(1, plugin.slab.placements);

        plugin.allocateNow(player, "JAVA");
        assertEquals(1, plugin.slab.placements, "an allocated player is never given another");
    }

    @Test
    @DisplayName("nothing is placed with structures off, and reload reads the template again")
    void offByDefaultAndReloaded() {
        StructurePlugin plugin = load(false);
        InlinePlayerMock player = new InlinePlayerMock(server, "Plain");
        server.addPlayer(player);

        plugin.allocateNow(player, "JAVA");

        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record);
        assertNull(plugin.getDataStorage().getStructure(record.centre(), record.index()));
        assertEquals(0, plugin.slab.placements);
        int reads = plugin.loadedFiles.size();
        plugin.reload();
        assertEquals(reads + 1, plugin.loadedFiles.size());
        assertFalse(plugin.getStructures().loaded().template() == null);
        assertTrue(new File(plugin.getDataFolder(), "structures/spawn-tower.nbt").isFile(),
                "the shipped templates are copied into the data folder");
    }
}
