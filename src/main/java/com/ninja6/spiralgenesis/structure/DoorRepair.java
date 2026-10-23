package com.ninja6.spiralgenesis.structure;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Puts back the door halves a structure placement broke.
 *
 * <p>Vanilla structure placement runs shape updates as it sets each block. A door half that
 * is updated while its other half or the floor under it is not in place turns to air and
 * drops the door as an item. Natural blocks the template does not overwrite, such as leaf
 * litter beside a doorway, change which updates reach the door and when. The Bukkit API
 * cannot place a structure without those updates, so the halves are checked once placement
 * is done, and any that did not survive are set again without updating their neighbours.
 *
 * <p>Use one instance per placement: {@link #before} notes the targets and what already
 * stands there, {@link #after} repairs.
 */
final class DoorRepair {

    /** One door half as the template stores it, relative to its origin and before rotation. */
    record Half(int x, int y, int z, BlockData data) { }

    /** Sets a block's data. On a server, without updating its neighbours. */
    @FunctionalInterface
    interface Writer {
        void set(Block block, BlockData data);
    }

    /** Sets the block with no neighbour or shape updates, so the door cannot break again. */
    static final Writer WITHOUT_PHYSICS = (block, data) -> block.setBlockData(data, false);

    /** Where one half lands and the state it should have there, rotation applied. */
    private record Target(Block block, BlockData data) { }

    private final World world;
    private final List<Target> targets;
    private final Set<UUID> present;

    private DoorRepair(World world, List<Target> targets, Set<UUID> present) {
        this.world = world;
        this.targets = targets;
        this.present = present;
    }

    /** The door halves among a template's stored states. */
    static List<Half> halvesOf(Collection<? extends BlockState> states) {
        List<Half> halves = new ArrayList<>();
        for (BlockState state : states) {
            BlockData data = state.getBlockData();
            if (data instanceof Door) {
                halves.add(new Half(state.getX(), state.getY(), state.getZ(), data));
            }
        }
        return List.copyOf(halves);
    }

    /**
     * Notes where each half will land for a template placed at {@code origin} with
     * {@code rotation}, and which entities are already there, so that {@link #after} removes
     * only the drops of this placement. Call before placing, on the thread owning the chunks.
     */
    static DoorRepair before(Location origin, StructureRotation rotation, List<Half> halves) {
        World world = origin.getWorld();
        List<Target> targets = new ArrayList<>(halves.size());
        Set<UUID> present = new HashSet<>();
        for (Half half : halves) {
            int[] turned = Placement.rotate(half.x(), half.z(), rotation);
            Block block = world.getBlockAt(origin.getBlockX() + turned[0],
                    origin.getBlockY() + half.y(), origin.getBlockZ() + turned[1]);
            BlockData data = half.data().clone();
            if (rotation != StructureRotation.NONE) {
                data.rotate(rotation);
            }
            targets.add(new Target(block, data));
            for (Entity entity : world.getNearbyEntities(BoundingBox.of(block))) {
                present.add(entity.getUniqueId());
            }
        }
        return new DoorRepair(world, List.copyOf(targets), present);
    }

    /**
     * Sets again every half that is no longer the template's door with the same half, and
     * removes the door items that broken halves dropped. A door left standing is not
     * touched, so an open or powered door keeps that state.
     *
     * @return the number of halves set again
     */
    int after(Writer writer) {
        int restored = 0;
        for (Target target : targets) {
            if (standing(target)) {
                continue;
            }
            writer.set(target.block(), target.data());
            removeDrops(target);
            restored++;
        }
        return restored;
    }

    private static boolean standing(Target target) {
        BlockData now = target.block().getBlockData();
        return now.getMaterial() == target.data().getMaterial()
                && now instanceof Bisected placed
                && placed.getHalf() == ((Bisected) target.data()).getHalf();
    }

    /** A broken door drops where it stood, as one item of the door's own type. */
    private void removeDrops(Target target) {
        for (Entity entity : world.getNearbyEntities(BoundingBox.of(target.block()))) {
            if (entity instanceof Item item && !present.contains(item.getUniqueId())
                    && item.getItemStack().getType() == target.data().getMaterial()) {
                item.remove();
            }
        }
    }
}
