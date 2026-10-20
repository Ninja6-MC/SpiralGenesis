package com.ninja6.spiralgenesis.structure;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A vanilla structure file read through the server's own structure manager, so the
 * server upgrades older data versions on load exactly as {@code /place template} does.
 *
 * <p>Always the first palette: a structure block saves one, and placing a different one on
 * each plot would make the checks made before placement describe another template.
 */
public final class BukkitTemplate implements Template {

    private final String name;
    private final Structure structure;
    private final List<Block> blocks;

    /** Reads a structure file through the server's structure manager. */
    public static Template load(File file) throws IOException {
        return new BukkitTemplate(file.getName(), Bukkit.getStructureManager().loadStructure(file));
    }

    BukkitTemplate(String name, Structure structure) {
        this.name = name;
        this.structure = structure;
        List<Block> read = new ArrayList<>();
        List<Palette> palettes = structure.getPalettes();
        if (!palettes.isEmpty()) {
            for (BlockState state : palettes.get(0).getBlocks()) {
                // Palette states carry their position relative to the template's origin.
                read.add(new Block(state.getX(), state.getY(), state.getZ(), state.getType(),
                        state.getType().isAir()));
            }
        }
        this.blocks = List.copyOf(read);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Block> blocks() {
        return blocks;
    }

    @Override
    public void place(Location origin, StructureRotation rotation, boolean includeEntities) {
        structure.place(origin, includeEntities, rotation, Mirror.NONE, 0, 1.0f, new Random());
    }
}
