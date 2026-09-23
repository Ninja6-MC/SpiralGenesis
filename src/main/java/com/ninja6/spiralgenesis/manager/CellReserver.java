package com.ninja6.spiralgenesis.manager;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.storage.DataStorage;

import java.util.function.IntSupplier;

/**
 * Where an allocation scan gets its cells from.
 *
 * <p>A scan asks for one cell per attempt. The first is asked for with the geometry
 * configured at that moment and every later one on the centre the first came from, so a
 * centre moved or a cell size changed while a scan runs applies from the next scan. It
 * hands back every cell it reserved and did not settle on.
 */
@FunctionalInterface
public interface CellReserver {

    /**
     * Claims the next cell of the spiral growing from {@code (originX, originZ)} with cells
     * of {@code cellSize}.
     */
    SpiralCell reserve(int originX, int originZ, int cellSize);

    /**
     * Claims the next cell of {@code centre}, the spiral an earlier cell of the same scan
     * came from. Unlike {@link #reserve(int, int, int)} this does not make it the spiral
     * new scans start on.
     */
    default SpiralCell reserve(SpiralCentre centre) {
        return reserve(centre.originX(), centre.originZ(), centre.cellSize());
    }

    /** Hands back a cell the scan reserved and gave up on. */
    default void release(SpiralCell cell) {
    }

    /** {@link #reserve(int, int, int)} with the geometry {@code config} holds now. */
    default SpiralCell reserve(PluginConfig config) {
        return reserve(config.getOriginX(), config.getOriginZ(), config.getCellSize());
    }

    /** The live spiral: cells claimed from, and handed back to, storage. */
    static CellReserver of(DataStorage storage) {
        return new CellReserver() {
            @Override
            public SpiralCell reserve(int originX, int originZ, int cellSize) {
                return storage.reserveCell(originX, originZ, cellSize);
            }

            @Override
            public SpiralCell reserve(SpiralCentre centre) {
                return storage.reserveCell(centre);
            }

            @Override
            public void release(SpiralCell cell) {
                storage.releaseCell(cell.centre().id(), cell.index());
            }
        };
    }

    /**
     * Cells at the given geometry numbered by {@code indices}, recorded nowhere and tested
     * against nothing, on centre 0. For a simulation and tests.
     */
    static CellReserver counting(IntSupplier indices) {
        return (originX, originZ, cellSize) ->
                new SpiralCentre(0, originX, originZ, cellSize).cell(indices.getAsInt());
    }
}
