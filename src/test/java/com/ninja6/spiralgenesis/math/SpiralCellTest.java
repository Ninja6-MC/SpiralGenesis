package com.ninja6.spiralgenesis.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiralCellTest {

    @Test
    @DisplayName("a cell covers cell-size columns on each axis, from half a cell below its centre")
    void cellArea() {
        SpiralCentre centre = new SpiralCentre(0, 100, -200, 500);

        assertEquals(new CellArea(-150, -450, 350, 50), centre.cell(0).area());
        // Index 1 is grid (1, 0), one cell east.
        assertEquals(new CellArea(350, -450, 850, 50), centre.cell(1).area());
        assertEquals(new CellArea(-150, -450, 351, 51),
                new SpiralCentre(0, 100, -200, 501).cell(0).area(), "odd sizes too");
    }

    @Test
    @DisplayName("neighbouring cells touch without overlapping")
    void neighboursDoNotOverlap() {
        SpiralCentre centre = new SpiralCentre(0, 0, 0, 500);
        for (int a = 0; a < 25; a++) {
            for (int b = a + 1; b < 25; b++) {
                assertFalse(centre.cell(a).area().overlaps(centre.cell(b).area()), a + " " + b);
            }
        }
    }

    @Test
    @DisplayName("areas are half-open")
    void halfOpen() {
        CellArea area = new CellArea(0, 0, 10, 10);

        assertTrue(area.contains(0, 9));
        assertFalse(area.contains(10, 0));
        assertFalse(area.overlaps(new CellArea(10, 0, 20, 10)), "touching is not overlapping");
        assertTrue(area.overlaps(new CellArea(9, 9, 20, 20)));
        assertTrue(area.overlaps(CellArea.column(5, 5)));
    }

    @Test
    @DisplayName("plots are labelled centre,index, and points set by hand keep #-1")
    void labels() {
        assertEquals("#2,17", new SpiralCentre(2, 0, 0, 500).cell(17).label());
        assertEquals("#-1", SpiralCentre.label(0, -1));
    }
}
