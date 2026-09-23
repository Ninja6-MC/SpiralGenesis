package com.ninja6.spiralgenesis.storage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.math.CellArea;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plots of different spiral centres never share ground.
 *
 * <p>Moving {@code origin.x}/{@code origin.z} or changing {@code cell-size} starts a new
 * centre whose cells can land on plots already handed out. These tests move the centre and
 * change the cell size with plots recorded, and check what {@code data.yml} keeps across a
 * reload, including a file written before centres had ids.
 */
class SpiralCentreStorageTest {

    private static final int CELL = 500;

    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;
    private Path dataFile;
    private final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
        dataFile = plugin.getDataFolder().toPath().resolve("data.yml");
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private YamlDataStorage loaded() {
        YamlDataStorage storage = new YamlDataStorage(plugin);
        storage.load();
        return storage;
    }

    private void writeDataFile(String content) throws IOException {
        Files.createDirectories(dataFile.getParent());
        Files.writeString(dataFile, content, StandardCharsets.UTF_8);
    }

    /** Reserves the next cell at this geometry and records a player at its centre. */
    private SpiralCell allocate(DataStorage storage, int originX, int originZ, int cellSize,
                                String name) {
        SpiralCell cell = storage.reserveCell(originX, originZ, cellSize);
        int[] grid = cell.grid();
        assertTrue(storage.setSpawn(UUID.randomUUID(),
                new Location(world, cell.centreX() + 0.5, 64, cell.centreZ() + 0.5),
                cell.centre().id(), cell.index(), grid[0], grid[1], name, "TEST", false));
        return cell;
    }

    /** Every recorded spiral plot's cell, found through the centre table. */
    private static List<SpiralCell> recordedCells(DataStorage storage) {
        List<SpiralCell> cells = new ArrayList<>();
        for (StoredSpawn record : storage.getAllRecords().values()) {
            if (record.onSpiral()) {
                cells.add(storage.getCentre(record.centre()).cell(record.index()));
            }
        }
        return cells;
    }

    private static void assertNoTwoOverlap(List<SpiralCell> cells) {
        for (int i = 0; i < cells.size(); i++) {
            for (int j = i + 1; j < cells.size(); j++) {
                SpiralCell a = cells.get(i);
                SpiralCell b = cells.get(j);
                assertFalse(a.area().overlaps(b.area()),
                        "plots " + a.label() + " and " + b.label() + " overlap: " + a.area()
                                + " and " + b.area());
            }
        }
    }

    @Test
    @DisplayName("moving the centre mid-spiral skips every cell that would land on a plot")
    void movedCentreSkipsOverlappingCells() {
        YamlDataStorage storage = loaded();
        for (int i = 0; i < 9; i++) {
            allocate(storage, 0, 0, CELL, "First" + i);
        }

        // The worked example of the defect: under the old single counter index 9 at
        // (-1000, 0) was index 7's cell and index 10 was index 0's.
        List<SpiralCell> moved = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            moved.add(allocate(storage, -1000, 0, CELL, "Moved" + i));
        }

        assertTrue(moved.stream().allMatch(cell -> cell.centre().id() == 1),
                "a new geometry is a new centre: " + moved);
        assertEquals(0, moved.get(0).index(), "the new centre counts from its own 0");
        assertNoTwoOverlap(recordedCells(storage));
        assertTrue(moved.get(moved.size() - 1).index() > moved.size() - 1,
                "some cells must have been skipped");
        assertTrue(logged.stream().anyMatch(line -> line.startsWith("Skipped plot #1,")
                        && line.contains("overlaps plot #0,")),
                "a skipped index is logged with what it overlaps: " + logged);
    }

    @Test
    @DisplayName("changing the cell size mid-spiral skips every cell that would land on a plot")
    void changedCellSizeSkipsOverlappingCells() {
        YamlDataStorage storage = loaded();
        for (int i = 0; i < 9; i++) {
            allocate(storage, 0, 0, CELL, "First" + i);
        }

        SpiralCell first = allocate(storage, 0, 0, 400, "Smaller");

        assertEquals(1, first.centre().id(), "the same origin with another size is a new centre");
        for (int i = 0; i < 20; i++) {
            allocate(storage, 0, 0, 400, "Smaller" + i);
        }
        assertNoTwoOverlap(recordedCells(storage));
        // The first 3x3 cells at 500 cover x and z in [-750, 750); every 400-block cell
        // handed out must lie wholly outside that square.
        CellArea taken = new CellArea(-750, -750, 750, 750);
        for (SpiralCell cell : recordedCells(storage)) {
            if (cell.centre().id() == 1) {
                assertFalse(cell.area().overlaps(taken), cell.label() + " " + cell.area());
            }
        }
    }

    @Test
    @DisplayName("returning to an earlier centre resumes its own counter, not the other's")
    void returningResumesThatCentresCounter() {
        YamlDataStorage storage = loaded();
        for (int i = 0; i < 3; i++) {
            allocate(storage, 0, 0, CELL, "First" + i);
        }
        for (int i = 0; i < 5; i++) {
            allocate(storage, 100_000, 0, CELL, "Far" + i);
        }

        SpiralCell back = allocate(storage, 0, 0, CELL, "Back");

        assertEquals(0, back.centre().id());
        assertEquals(3, back.index(), "centre 0 resumes where it left off");
        assertEquals(4, storage.getCurrentIndex(), "the active centre is the one used last");
    }

    @Test
    @DisplayName("a cell reserved and not yet recorded blocks another centre until it is released")
    void inFlightCellBlocksOtherCentres() {
        YamlDataStorage storage = loaded();
        SpiralCell held = storage.reserveCell(0, 0, CELL);

        SpiralCell other = storage.reserveCell(250, 0, CELL);
        assertEquals(1, other.centre().id());
        assertFalse(other.area().overlaps(held.area()),
                "the in-flight cell must be skipped: " + other.label());

        storage.releaseCell(held);
        SpiralCentre third = storage.centreFor(0, 250, CELL);
        SpiralCell after = storage.reserveCell(0, 250, CELL);
        assertEquals(third, after.centre());
        assertEquals(0, after.index(), "once released it no longer blocks anything");
    }

    @Test
    @DisplayName("a point set by hand is tested by its column")
    void manualPointIsTestedByItsColumn() {
        YamlDataStorage storage = loaded();
        storage.centreFor(0, 0, CELL);
        storage.setSpawn(UUID.randomUUID(), new Location(world, 5000.5, 64, 0.5), -1, 0, 0,
                "Manual", "MANUAL");

        SpiralCell cell = storage.reserveCell(5000, 0, CELL);

        assertFalse(cell.area().contains(5000, 0), "the cell holding the manual point is skipped");
        assertEquals(1, cell.index());
    }

    @Test
    @DisplayName("centres, the centre of each plot, and each counter survive a save and load")
    void centresRoundTrip() {
        YamlDataStorage storage = loaded();
        for (int i = 0; i < 3; i++) {
            allocate(storage, 0, 0, CELL, "First" + i);
        }
        SpiralCell moved = allocate(storage, -1000, 0, CELL, "Moved");
        storage.save();

        YamlConfiguration written = YamlConfiguration.loadConfiguration(dataFile.toFile());
        assertEquals(1, written.getInt("active-centre"));
        assertEquals(moved.index() + 1, written.getInt("current-spiral-index"),
                "the old counter key holds the active centre's counter");
        assertEquals(-1000, written.getInt("centres.1.x"));
        assertEquals(3, written.getInt("centres.0.next-index"));

        YamlDataStorage reloaded = loaded();
        assertEquals(new SpiralCentre(0, 0, 0, CELL), reloaded.getCentre(0));
        assertEquals(new SpiralCentre(1, -1000, 0, CELL), reloaded.getCentre(1));
        UUID movedPlayer = reloaded.findByName("Moved");
        assertEquals(1, reloaded.getRecord(movedPlayer).centre());
        assertEquals(moved.index(), reloaded.getRecord(movedPlayer).index());
        assertEquals(moved.index() + 1, reloaded.getCurrentIndex());
        assertEquals(3, reloaded.reserveCell(0, 0, CELL).index(),
                "centre 0 resumes from its own counter after a reload");
        assertNoTwoOverlap(recordedCells(reloaded));
    }

    @Test
    @DisplayName("a file written before centres had ids puts its plots on centre 0 at the configured origin")
    void legacyRecordsAreCentreZeroAtTheConfiguredOrigin() throws IOException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        writeDataFile("current-spiral-index: 2\nplayers:\n"
                + "  " + a + ":\n    name: A\n    assigned-index: 0\n    x: 0.5\n    z: 0.5\n"
                + "    world: world\n"
                + "  " + b + ":\n    name: B\n    assigned-index: 1\n    x: 500.5\n    z: 0.5\n"
                + "    world: world\n");
        YamlDataStorage storage = loaded();
        assertNull(storage.getCentre(0), "nothing has said where centre 0 is yet");
        assertEquals(0, storage.getRecord(a).centre());

        SpiralCentre configured = storage.centreFor(0, 0, CELL);

        assertEquals(new SpiralCentre(0, 0, 0, CELL), configured);
        assertEquals(2, storage.reserveCell(0, 0, CELL).index(),
                "the file's counter carries over to centre 0");
        // Centre 1 is 500 blocks east, so its cell 0 is centre 0's cell 1, which B holds.
        SpiralCell moved = storage.reserveCell(500, 0, CELL);
        assertEquals(1, moved.centre().id());
        assertFalse(moved.area().overlaps(configured.cell(1).area()), moved.label());
        assertFalse(moved.area().overlaps(configured.cell(0).area()), moved.label());

        storage.save();
        YamlConfiguration written = YamlConfiguration.loadConfiguration(dataFile.toFile());
        assertEquals(0, written.getInt("centres.0.x"));
        assertEquals(CELL, written.getInt("centres.0.cell-size"));
    }

    @Test
    @DisplayName("a counter advanced by a version that predates centres is not lost")
    void downgradedCounterIsKept() throws IOException {
        writeDataFile("current-spiral-index: 9\nactive-centre: 1\ncentres:\n"
                + "  '0':\n    x: 0\n    z: 0\n    cell-size: 500\n    next-index: 4\n"
                + "  '1':\n    x: -1000\n    z: 0\n    cell-size: 500\n    next-index: 6\n");

        YamlDataStorage storage = loaded();

        assertEquals(9, storage.getCurrentIndex(), "the active centre takes the larger counter");
        assertEquals(4, storage.reserveCell(0, 0, CELL).index(), "centre 0 keeps its own");
    }
}
