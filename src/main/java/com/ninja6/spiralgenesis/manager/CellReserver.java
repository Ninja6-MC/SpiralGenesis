package com.ninja6.spiralgenesis.manager;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.storage.DataStorage;

import java.util.function.IntSupplier;

/**
 * Where an allocation scan gets its cells from.
 *
 * <p>A scan asks for one cell per attempt, with the geometry configured at that moment, so
 * a centre moved or a cell size changed while it runs applies from its next cell. It hands
 * back every cell it reserved and did not settle on.
 */
@FunctionalInterface
public interface CellReserver {

    /**
     * Claims the next cell of the spiral growing from {@code (originX, originZ)} with cells
     * of {@code cellSize}.
     */
    SpiralCell reserve(int originX, int originZ, int cellSize);

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
            public void release(SpiralCell cell) {
                storage.releaseCell(cell);
            }
        };
    }

    /**
     * Cells at the given geometry numbered by {@code indices}, recorded nowhere and tested
     * against nothing, on centre 0. For a simulation, a repair of a cell already held, and
     * tests.
     */
    static CellReserver counting(IntSupplier indices) {
        return (originX, originZ, cellSize) ->
                new SpiralCentre(0, originX, originZ, cellSize).cell(indices.getAsInt());
    }
}
