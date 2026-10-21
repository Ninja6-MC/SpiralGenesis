package com.ninja6.spiralgenesis.structure;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.math.CellArea;
import com.ninja6.spiralgenesis.storage.PlacedStructure;
import com.ninja6.spiralgenesis.storage.YamlDataStorage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placement rules for spawn plot structures, against a MockBukkit world with ground at
 * Y=20 and a spawn standing on it at the origin.
 */
class SpawnStructuresTest {

    private static final int GROUND = 20;

    private ServerMock server;
    private WorldMock world;
    private JavaPlugin plugin;
    private YamlDataStorage storage;
    private TestStructureEnvironment env;
    private SpawnStructures structures;
    private TestTemplate hut;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        storage = new YamlDataStorage(plugin);
        storage.load();
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = GROUND - 4; y <= GROUND; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE);
                }
            }
        }
        env = new TestStructureEnvironment(storage, plugin.getDataFolder());
        hut = TestTemplate.hut("spawn-hut.nbt", 7);
        env.templates.put("spawn-hut.nbt", hut);
        structures = new SpawnStructures(env);
        structures.reload();
        assertTrue(storage.setSpawn(owner, new Location(world, 0.5, GROUND + 1, 0.5), 0, 0, 0, 0,
                "Builder", "JAVA", false));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Material at(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType();
    }

    private SpawnStructures.Outcome placeNow(boolean dryRun) {
        AtomicReference<SpawnStructures.Outcome> outcome = new AtomicReference<>();
        structures.placeNow(owner, "Builder", dryRun, outcome::set);
        env.drain();
        assertNotNull(outcome.get(), "an outcome is always reported");
        return outcome.get();
    }

    private void allocate() {
        structures.placeForAllocation(owner, "Builder");
        env.drain();
    }

    @Test
    @DisplayName("the floor replaces the ground, rooms clear, and the plot is recorded once")
    void placesOnceAndRecordsIt() {
        world.getBlockAt(0, GROUND + 1, 0).setType(Material.DIRT);

        allocate();

        assertEquals(Material.COBBLESTONE, at(0, GROUND, 0), "the floor replaces the ground");
        assertEquals(Material.AIR, at(0, GROUND + 1, 0), "enclosed air clears the room");
        assertEquals(Material.COBBLESTONE, at(3, GROUND + 1, 0), "walls are placed");
        PlacedStructure placed = storage.getStructure(0, 0);
        assertNotNull(placed);
        assertEquals("spawn-hut.nbt", placed.file());
        assertEquals(-3, placed.x());
        assertEquals(GROUND, placed.y());

        world.getBlockAt(0, GROUND + 1, 0).setType(Material.DIRT);
        allocate();
        assertEquals(1, hut.placements, "a plot is never placed twice");
        assertEquals(Material.DIRT, at(0, GROUND + 1, 0), "and what the player changed stays");
    }

    @Test
    @DisplayName("positions the template does not store leave the terrain alone")
    void outsideAirLeavesTerrain() {
        List<Template.Block> blocks = new ArrayList<>(hut.blocks());
        blocks.removeIf(b -> b.x() == 0 && b.y() == 3 && b.z() == 0);
        TestTemplate open = new TestTemplate("spawn-hut.nbt", blocks);
        env.templates.put("spawn-hut.nbt", open);
        structures.reload();
        world.getBlockAt(-3, GROUND + 4, -3).setType(Material.OAK_LOG);
        world.getBlockAt(5, GROUND + 1, 5).setType(Material.OAK_LOG);

        allocate();

        assertEquals(Material.OAK_LOG, at(-3, GROUND + 4, -3), "an unstored position is untouched");
        assertEquals(Material.OAK_LOG, at(5, GROUND + 1, 5), "and so is anything outside the box");
        assertEquals(1, open.placements);
    }

    @Test
    @DisplayName("missing ground under the floor skips the plot under SKIP and changes nothing")
    void skipsUnfitTerrain() {
        world.getBlockAt(3, GROUND - 1, 3).setType(Material.AIR);

        allocate();

        assertEquals(0, hut.placements);
        assertEquals(Material.STONE, at(0, GROUND, 0));
        assertNull(storage.getStructure(0, 0));
        assertTrue(env.messages(Level.INFO).stream().anyMatch(m -> m.contains("ground under its floor")),
                "the skip is logged");
    }

    @Test
    @DisplayName("FOUNDATION fills down to the ground, and gives up past foundation-depth")
    void foundationFillsAndHasALimit() {
        env.yaml.set("structure.terrain", "FOUNDATION");
        env.yaml.set("structure.foundation-depth", 2);
        env.reconfigure();
        world.getBlockAt(3, GROUND - 1, 3).setType(Material.AIR);
        world.getBlockAt(3, GROUND - 2, 3).setType(Material.WATER);

        allocate();

        assertEquals(1, hut.placements);
        assertEquals(Material.COBBLESTONE, at(3, GROUND - 1, 3));
        assertEquals(Material.COBBLESTONE, at(3, GROUND - 2, 3));

        UUID second = UUID.randomUUID();
        storage.setSpawn(second, new Location(world, 40.5, GROUND + 1, 0.5), 0, 1, 1, 0, "Deep",
                "JAVA", false);
        for (int x = 36; x <= 44; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = GROUND - 4; y <= GROUND; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE);
                }
            }
        }
        for (int y = GROUND - 3; y < GROUND; y++) {
            world.getBlockAt(43, y, 3).setType(Material.AIR);
        }
        structures.placeForAllocation(second, "Deep");
        env.drain();
        assertNull(storage.getStructure(0, 1), "three blocks down is past a depth of two");
        assertEquals(Material.AIR, at(43, GROUND - 1, 3), "and nothing was filled");
    }

    @Test
    @DisplayName("a template reaching into a claim that is not the player's is skipped")
    void foreignClaim() {
        env.claims = new com.ninja6.spiralgenesis.protection.ClaimLookup() {
            @Override
            public boolean overlapsClaim(org.bukkit.World w, CellArea area) {
                return true;
            }

            @Override
            public boolean overlapsForeignClaim(org.bukkit.World w, CellArea area, UUID player) {
                return area.contains(3, 3) && player.equals(owner);
            }
        };

        allocate();

        assertEquals(0, hut.placements);
        assertNull(storage.getStructure(0, 0));
    }

    @Test
    @DisplayName("the actual claim, not the configured size, bounds the template")
    void actualClaimBounds() {
        env.claimBounds(-2, -2, 2, 2);
        allocate();
        assertEquals(0, hut.placements, "a claim resized to 5x5 cannot hold a 7x7 hut");

        env.claimBounds(-4, -4, 4, 4);
        allocate();
        assertEquals(1, hut.placements);
    }

    @Test
    @DisplayName("protection.size bounds the template even with protection off")
    void protectionSizeBounds() {
        env.yaml.set("protection.size", 5);
        env.reconfigure();

        allocate();

        assertEquals(0, hut.placements);
    }

    @Test
    @DisplayName("bedrock and block entities are never replaced")
    void neverReplacesBedrockOrBlockEntities() {
        world.getBlockAt(1, GROUND + 1, 1).setType(Material.CHEST);
        allocate();
        assertEquals(0, hut.placements);
        assertEquals(Material.CHEST, at(1, GROUND + 1, 1));

        world.getBlockAt(1, GROUND + 1, 1).setType(Material.AIR);
        world.getBlockAt(-3, GROUND, -3).setType(Material.BEDROCK);
        allocate();
        assertEquals(0, hut.placements);
        assertEquals(Material.BEDROCK, at(-3, GROUND, -3));
    }

    @Test
    @DisplayName("a spawn that is unsafe afterwards gets every block back and no record")
    void unsafeSpawnIsRolledBack() {
        world.getBlockAt(0, GROUND + 1, 0).setType(Material.DIRT);
        env.safe = spawn -> false;

        allocate();

        assertEquals(1, hut.placements, "placed, then checked");
        assertEquals(Material.STONE, at(0, GROUND, 0));
        assertEquals(Material.DIRT, at(0, GROUND + 1, 0));
        assertEquals(Material.AIR, at(3, GROUND + 1, 0));
        assertNull(storage.getStructure(0, 0));
    }

    @Test
    @DisplayName("a template whose spawn spot is in a wall fails the feet check")
    void spawnInsideAWallIsRolledBack() {
        env.yaml.set("structure.offset.x", 0);
        env.yaml.set("structure.offset.z", 3);
        env.yaml.set("protection.size", 15);
        env.reconfigure();

        allocate();

        assertNull(storage.getStructure(0, 0));
        assertEquals(Material.AIR, at(0, GROUND + 1, 0));
    }

    @Test
    @DisplayName("a template that would pass the build height is skipped")
    void buildHeight() {
        int top = world.getMaxHeight() - 1;
        storage.setSpawn(owner, new Location(world, 0.5, top - 1, 0.5), 0, 0, 0, 0, "Builder",
                "JAVA", false);

        allocate();

        assertEquals(0, hut.placements);
    }

    @Test
    @DisplayName("on Folia the placement runs on the region thread, one hand-off per tick")
    void foliaScheduling() {
        env.owns = false;
        UUID second = UUID.randomUUID();
        storage.setSpawn(second, new Location(world, 100.5, GROUND + 1, 0.5), 0, 1, 1, 0,
                "Second", "JAVA", false);

        structures.placeForAllocation(owner, "Builder");
        structures.placeForAllocation(second, "Second");
        assertTrue(env.tickers.isEmpty(), "queued once the chunks are held");
        env.runRegionTasks();
        assertEquals(1, env.tickers.size(), "one driver for the queue");
        assertFalse(env.tickers.get(0).getAsBoolean(), "one placement handed off, one waiting");
        assertEquals(1, env.regionTasks.size());
        assertEquals(Material.STONE, at(0, GROUND, 0), "nothing placed on the global thread");

        env.runRegionTasks();
        assertEquals(Material.COBBLESTONE, at(0, GROUND, 0));
        assertTrue(env.tickers.get(0).getAsBoolean(), "the second hand-off empties the queue");
        assertEquals(1, env.regionTasks.size());
    }

    @Test
    @DisplayName("where the driver owns the region, several placements run in one tick")
    void paperScheduling() {
        UUID second = UUID.randomUUID();
        storage.setSpawn(second, new Location(world, 8.5, GROUND + 1, 0.5), 0, 1, 1, 0,
                "Second", "JAVA", false);
        structures.placeForAllocation(owner, "Builder");
        structures.placeForAllocation(second, "Second");

        assertTrue(env.tickers.get(0).getAsBoolean());
        assertEquals(2, hut.placements);
        assertTrue(env.regionTasks.isEmpty());
    }

    @Test
    @DisplayName("structure.enabled false places nothing on allocation")
    void disabled() {
        env.yaml.set("structure.enabled", false);
        env.reconfigure();

        allocate();

        assertEquals(0, hut.placements);
        assertTrue(env.tickers.isEmpty());
    }

    @Test
    @DisplayName("the admin dry run changes nothing and says it would place")
    void dryRun() {
        SpawnStructures.Outcome outcome = placeNow(true);

        assertTrue(outcome.wouldPlace(), outcome.message());
        assertFalse(outcome.placed());
        assertEquals(0, hut.placements);
        assertNull(storage.getStructure(0, 0));
    }

    @Test
    @DisplayName("the admin place works with structures off, and refuses a recorded plot")
    void adminPlace() {
        env.yaml.set("structure.enabled", false);
        env.reconfigure();

        assertTrue(placeNow(false).placed());
        SpawnStructures.Outcome again = placeNow(false);
        assertFalse(again.placed());
        assertTrue(again.message().contains("already has"), again.message());
        assertEquals(1, hut.placements);
    }

    @Test
    @DisplayName("a point set by hand has no plot to record a structure for")
    void manualPoint() {
        storage.setSpawn(owner, new Location(world, 0.5, GROUND + 1, 0.5), -1, 0, 0, "Builder",
                "JAVA");

        assertFalse(placeNow(false).placed());
        assertEquals(0, hut.placements);
    }

    @Test
    @DisplayName("FACING_CENTRE turns the template and the spawn spot stays the stored spawn")
    void facingCentreRotation() {
        env.yaml.set("structure.rotation", "FACING_CENTRE");
        env.yaml.set("origin.x", 0);
        env.yaml.set("origin.z", -500);
        env.reconfigure();

        allocate();

        PlacedStructure placed = storage.getStructure(0, 0);
        assertNotNull(placed);
        assertEquals("CLOCKWISE_90", placed.rotation(), "west turned to face north");
        assertEquals(Material.COBBLESTONE, at(0, GROUND, 0));
        assertEquals(Material.AIR, at(0, GROUND + 1, 0));
    }

    @Test
    @DisplayName("boot and reload warn once when the template exceeds protection.size")
    void sizeWarning() {
        assertTrue(env.messages(Level.WARNING).isEmpty(), "a 7x7 hut fits size 9");

        env.yaml.set("protection.size", 5);
        env.reconfigure();
        structures.reload();
        List<String> warnings = env.messages(Level.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("needs a 7x7") && warnings.get(0).contains("is 5"),
                warnings.get(0));

        env.logged.clear();
        env.yaml.set("structure.enabled", false);
        env.reconfigure();
        structures.reload();
        assertTrue(env.messages(Level.WARNING).isEmpty(), "nothing is said while off");
    }

    @Test
    @DisplayName("protection.size is rounded and clamped before it is compared")
    void sizeIsCorrectedFirst() {
        env.yaml.set("protection.size", 6);
        env.reconfigure();
        structures.reload();
        assertTrue(env.messages(Level.WARNING).isEmpty(), "6 is used as 7, which holds a 7x7 hut");

        env.yaml.set("protection.size", 1);
        env.reconfigure();
        structures.reload();
        assertTrue(env.messages(Level.WARNING).stream().anyMatch(m -> m.contains("is 3.")),
                "1 is used as 3");
    }

    @Test
    @DisplayName("a missing or corrupt template is reported, and plots are skipped")
    void missingAndCorruptFiles() throws IOException {
        env.yaml.set("structure.file", "absent.nbt");
        env.reconfigure();
        structures.reload();
        assertTrue(env.messages(Level.WARNING).stream().anyMatch(m -> m.contains("does not exist")));

        File corrupt = new File(new File(plugin.getDataFolder(), SpawnStructures.FOLDER), "broken.nbt");
        Files.writeString(corrupt.toPath(), "corrupt", StandardCharsets.ISO_8859_1);
        env.yaml.set("structure.file", "broken.nbt");
        env.reconfigure();
        env.logged.clear();
        structures.reload();
        assertTrue(env.messages(Level.WARNING).stream().anyMatch(m -> m.contains("could not be read")));

        allocate();
        assertEquals(0, hut.placements);
        assertNull(storage.getStructure(0, 0));
    }

    @Test
    @DisplayName("an empty template is reported")
    void emptyTemplate() throws IOException {
        File empty = new File(new File(plugin.getDataFolder(), SpawnStructures.FOLDER), "empty.nbt");
        Files.writeString(empty.toPath(), "x", StandardCharsets.ISO_8859_1);
        env.templates.put("empty.nbt", new TestTemplate("empty.nbt",
                List.of(new Template.Block(0, 0, 0, Material.AIR, true))));
        env.yaml.set("structure.file", "empty.nbt");
        env.reconfigure();
        structures.reload();

        assertTrue(env.messages(Level.WARNING).stream().anyMatch(m -> m.contains("holds no blocks")));
    }

    @Test
    @DisplayName("the shipped templates are copied only when absent")
    void defaultsCopiedOnlyWhenAbsent() throws IOException {
        File folder = new File(plugin.getDataFolder(), SpawnStructures.FOLDER);
        assertEquals(List.of("structures/spawn-hut.nbt", "structures/spawn-tower.nbt",
                "structures/spawn-marker.nbt"), env.copied);
        assertTrue(new File(folder, "spawn-marker.nbt").length() > 0);
        assertTrue(new File(folder, "spawn-tower.nbt").length() > 0);

        Files.writeString(new File(folder, "spawn-hut.nbt").toPath(), "edited");
        env.copied.clear();
        structures.reload();

        assertTrue(env.copied.isEmpty());
        assertEquals("edited", Files.readString(new File(folder, "spawn-hut.nbt").toPath()));
    }

    @Test
    @DisplayName("the placement survives a restart, and storage refuses it while failed")
    void persistence() throws IOException {
        allocate();
        storage.save();

        YamlDataStorage restarted = new YamlDataStorage(plugin);
        restarted.load();
        assertEquals(storage.getStructure(0, 0), restarted.getStructure(0, 0));

        Files.writeString(plugin.getDataFolder().toPath().resolve("data.yml"), "players: [unclosed");
        storage.load();
        assertNull(storage.getStructure(0, 0));
        assertFalse(storage.recordStructure(0, 1,
                new PlacedStructure("x.nbt", "world", 0, 0, 0, "NONE", "t", "p")));
    }

    private static final Set<String> FOOTPRINT = Set.of("-1,-1", "-1,0", "0,-1", "0,0");

    /** Every ticket added was removed again, and nothing is held. */
    private void assertNoTicketLeft() {
        assertTrue(env.tickets.isEmpty(), "tickets left: " + env.tickets);
        assertEquals(0, structures.heldChunks());
        assertTrue(env.ticketsOnOwner, "tickets change only on the thread owning the chunk");
    }

    @Test
    @DisplayName("an offline plot's chunks unloaded before the job are held, and placed on")
    void offlinePlotChunksAreHeld() {
        AtomicReference<SpawnStructures.Outcome> outcome = new AtomicReference<>();
        structures.placeNow(owner, "Builder", false, outcome::set);
        assertEquals(FOOTPRINT, env.tickets, "every footprint chunk is held before the job");

        env.unloadUnheld(world);
        for (String key : FOOTPRINT) {
            String[] xz = key.split(",");
            assertTrue(world.isChunkLoaded(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])),
                    "a held chunk stays loaded: " + key);
        }
        env.drain();

        assertTrue(outcome.get().placed(), outcome.get().message());
        assertEquals(1, hut.placements);
        assertNoTicketLeft();
        assertEquals(8, env.ticketChanges.size(), "one ticket added and removed per chunk");
    }

    @Test
    @DisplayName("without a ticket the unloaded chunks refuse the job, as before the fix")
    void unheldChunksAreRefused() {
        AtomicReference<SpawnStructures.Outcome> outcome = new AtomicReference<>();
        structures.placeNow(owner, "Builder", true, outcome::set);
        env.tickets.clear();
        env.unloadUnheld(world);
        env.drain();

        assertFalse(outcome.get().wouldPlace());
        assertTrue(outcome.get().message().contains("not all loaded"), outcome.get().message());
    }

    @Test
    @DisplayName("the dry run's verdict reads as a dry run and is logged to the console")
    void dryRunVerdictWordingAndLog() {
        SpawnStructures.Outcome would = placeNow(true);
        assertTrue(would.wouldPlace(), would.message());
        assertTrue(env.messages(Level.INFO).contains(would.message()), "the verdict is logged");

        world.getBlockAt(-3, GROUND, -3).setType(Material.BEDROCK);
        SpawnStructures.Outcome refused = placeNow(true);
        assertEquals("Would not place a structure on plot #0,0 for Builder: it would replace"
                + " bedrock at (-3, 20, -3).", refused.message());
        assertTrue(env.messages(Level.INFO).contains(refused.message()), "the refusal is logged");
        assertNoTicketLeft();
    }

    @Test
    @DisplayName("place keeps its wording and logs its verdict too")
    void placeRefusalWordingAndLog() {
        world.getBlockAt(-3, GROUND, -3).setType(Material.BEDROCK);
        SpawnStructures.Outcome refused = placeNow(false);
        assertEquals("No structure placed on plot #0,0 for Builder: it would replace bedrock at"
                + " (-3, 20, -3).", refused.message());
        assertTrue(env.messages(Level.INFO).contains(refused.message()));
        assertEquals(0, hut.placements);
        assertNoTicketLeft();
    }

    @Test
    @DisplayName("tickets are released when the job throws")
    void ticketsReleasedOnException() {
        env.safe = spawn -> {
            throw new IllegalStateException("boom");
        };
        SpawnStructures.Outcome outcome = placeNow(false);

        assertFalse(outcome.placed());
        assertTrue(outcome.message().contains("IllegalStateException"), outcome.message());
        assertEquals(FOOTPRINT, Set.copyOf(env.ticketChanges.stream()
                .filter(c -> c.startsWith("-")).map(c -> c.substring(1)).toList()));
        assertNoTicketLeft();
    }

    @Test
    @DisplayName("tickets are released when a chunk cannot be loaded or the scheduler refuses")
    void ticketsReleasedWhenTheJobCannotRun() {
        env.loadFailure = new IllegalStateException("no chunk");
        SpawnStructures.Outcome failed = placeNow(true);
        assertTrue(failed.message().startsWith("Would not place"), failed.message());
        assertTrue(failed.message().contains("could not be loaded"), failed.message());
        assertNoTicketLeft();

        env.loadFailure = null;
        env.tickFailure = new IllegalStateException("scheduler shut down");
        SpawnStructures.Outcome refused = placeNow(false);
        assertTrue(refused.message().contains("scheduler refused"), refused.message());
        assertNoTicketLeft();
    }

    @Test
    @DisplayName("on Folia tickets change on the region thread, and a shared chunk stays held")
    void foliaTicketsAndSharedChunks() {
        env.owns = false;
        UUID second = UUID.randomUUID();
        storage.setSpawn(second, new Location(world, 8.5, GROUND + 1, 0.5), 0, 1, 1, 0,
                "Second", "JAVA", false);
        structures.placeNow(owner, "Builder", true, outcome -> { });
        structures.placeNow(second, "Second", true, outcome -> { });
        assertTrue(env.tickets.isEmpty(), "nothing is added off the owning thread");

        env.runRegionTasks();
        assertEquals(FOOTPRINT, env.tickets);
        assertFalse(env.tickers.get(0).getAsBoolean(), "the first job handed off");
        env.runRegionTasks();
        assertEquals(Set.of("0,-1", "0,0"), env.tickets,
                "the chunks the second plot shares stay held after the first job");

        env.drain();
        assertNoTicketLeft();
    }
}
