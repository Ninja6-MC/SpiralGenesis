package com.ninja6.spiralgenesis.structure;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.structure.StructureRotation;

import java.util.List;

/**
 * A loaded structure template: the positions it stores, and a way to place it.
 *
 * <p>Only stored positions change the world. Outside air is not stored in the shipped
 * templates (it is left out at conversion, the same as structure void), so the terrain
 * around a building is kept; enclosed air is stored and clears rooms.
 */
public interface Template {

    /** The file it was read from, as named in {@code structure.file}. */
    String name();

    /** Every stored position, relative to the template's origin, before rotation. */
    List<Block> blocks();

    /**
     * Places the template with its origin at {@code origin}, turned about that origin.
     * Called on the thread owning every chunk the template touches. Every door the template
     * stores is left standing, both halves, whatever blocks surround it.
     */
    void place(Location origin, StructureRotation rotation, boolean includeEntities);

    /**
     * One stored position.
     *
     * @param air whether the template stores air here, which clears the block
     */
    record Block(int x, int y, int z, Material type, boolean air) { }
}
