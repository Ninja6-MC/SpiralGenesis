package com.ninja6.spiralgenesis.structure;

import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.math.CellArea;
import com.ninja6.spiralgenesis.math.SpiralCentre;
import com.ninja6.spiralgenesis.protection.ClaimInspection;
import com.ninja6.spiralgenesis.protection.SpawnProtectionBackfill;
import com.ninja6.spiralgenesis.storage.DataStorage;
import com.ninja6.spiralgenesis.storage.PlacedStructure;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.structure.StructureRotation;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Places the configured template on each new spawn plot, once.
 *
 * <h2>When</h2>
 *
 * <p>Asked for after a first allocation's storage write succeeded and its claim step
 * returned, on the player's thread. The work itself runs later, on the thread owning the
 * spawn's region, once every chunk the template touches is loaded, and is drained from a
 * queue within the per-tick budget the {@code /sgen protect} backfill uses.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li>Once per plot. A placement is recorded in {@code data.yml} by spiral centre and
 *       index, and a plot with a record is never placed again; nothing ever removes a
 *       structure.</li>
 *   <li>The template's blocks must stay inside the {@code protection.size} square around
 *       the spawn, inside the player's actual claim when protection is active, and off
 *       every claim that is not theirs.</li>
 *   <li>No stored position may hold bedrock or a block with a block entity.</li>
 *   <li>The floor needs ground under it: {@code SKIP} leaves the plot without a structure,
 *       {@code FOUNDATION} fills down to the ground, at most {@code foundation-depth}
 *       blocks. Nothing may reach past the world's build height.</li>
 *   <li>The spawn must pass the spawn safety rule afterwards, with its feet and head
 *       clear. Otherwise every changed block is put back and the plot keeps its spawn.</li>
 * </ul>
 *
 * <p>Any rule that fails leaves the plot as it was and is logged once; allocation is never
 * affected.
 */
public final class SpawnStructures {

    /** Templates shipped in the jar and copied to the structures folder when absent. */
    public static final List<String> DEFAULT_TEMPLATES = List.of("spawn-hut.nbt", "spawn-tower.nbt",
            "spawn-marker.nbt");

    /** The folder inside the plugin's data folder that templates are read from. */
    public static final String FOLDER = "structures";

    private final StructureEnvironment env;
    private volatile Loaded loaded = Loaded.failed(null, "not loaded yet");
    private final Queue<Job> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean driving = new AtomicBoolean();

    public SpawnStructures(StructureEnvironment env) {
        this.env = env;
    }

    /**
     * The template in force, or why there is none.
     *
     * @param requiredSize the smallest {@code protection.size} that holds it; 0 if none
     */
    public record Loaded(String file, Template template, String error, int requiredSize) {
        static Loaded failed(String file, String error) {
            return new Loaded(file, null, error, 0);
        }
    }

    /**
     * What one attempt came to.
     *
     * @param placed  whether blocks were placed
     * @param message one line for the log or the operator who asked
     */
    public record Outcome(boolean placed, boolean wouldPlace, String message) { }

    public Loaded loaded() {
        return loaded;
    }

    /**
     * Copies the shipped templates where absent, reads the configured one, and reports at
     * warning when structures are on and cannot be placed: the template is missing,
     * unreadable or empty, or larger than {@code protection.size}. Called at startup and
     * on every reload; nothing is reported per player.
     */
    public void reload() {
        copyDefaults();
        PluginConfig config = env.config();
        StructureSettings settings = config.getStructure();
        Loaded read = read(settings);
        this.loaded = read;
        if (!settings.enabled()) {
            return;
        }
        if (read.template() == null) {
            env.logger().warning("structure.enabled is true, but structure.file '" + settings.file()
                    + "' cannot be used: " + read.error() + ". No spawn plot structures will be"
                    + " placed; allocation is unaffected. Fix the file and run /sgen reload.");
            return;
        }
        int size = config.getProtectionSize();
        if (read.requiredSize() > size) {
            env.logger().warning("Spawn plot structure " + settings.file() + " needs a "
                    + read.requiredSize() + "x" + read.requiredSize() + " square around the spawn"
                    + " (its blocks reach " + (read.requiredSize() - 1) / 2 + " blocks from the"
                    + " spawn spot at structure.offset " + settings.offsetX() + ", "
                    + settings.offsetY() + ", " + settings.offsetZ() + "), but protection.size is "
                    + size + ". Structures will be skipped on every plot until protection.size"
                    + " is at least " + read.requiredSize() + " or a smaller template is used.");
            return;
        }
        env.logger().info("Spawn plot structure: " + settings.file() + " (needs a "
                + read.requiredSize() + "x" + read.requiredSize() + " square; protection.size "
                + size + "), rotation " + settings.rotation() + ", terrain " + settings.terrain()
                + ".");
    }

    private void copyDefaults() {
        File folder = new File(env.dataFolder(), FOLDER);
        for (String name : DEFAULT_TEMPLATES) {
            // Only when absent: a server's edited copy is never overwritten.
            if (!new File(folder, name).exists()) {
                try {
                    env.saveResource(FOLDER + "/" + name);
                } catch (RuntimeException e) {
                    env.logger().log(Level.WARNING, "Could not copy the default template " + name
                            + " to " + folder + ".", e);
                }
            }
        }
    }

    private Loaded read(StructureSettings settings) {
        File file = new File(new File(env.dataFolder(), FOLDER), settings.file());
        if (!file.isFile()) {
            return Loaded.failed(settings.file(), "the file " + file.getPath() + " does not exist");
        }
        Template template;
        try {
            template = env.loadTemplate(file);
        } catch (Throwable e) {
            return Loaded.failed(settings.file(), "it could not be read as a structure file ("
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
        if (template == null || template.blocks().stream().allMatch(Template.Block::air)) {
            return Loaded.failed(settings.file(), "it holds no blocks");
        }
        return new Loaded(settings.file(), template, null,
                Placement.requiredSize(template, settings));
    }

    /**
     * Places the structure on a player's plot after its first allocation, if structures are
     * on and the plot has none recorded. The outcome is logged.
     *
     * <p>Call on the player's thread after the storage write succeeded and the claim step
     * returned.
     */
    public void placeForAllocation(UUID owner, String playerName) {
        try {
            if (!env.config().getStructure().enabled()) {
                return;
            }
            DataStorage storage = env.storage();
            StoredSpawn record = storage.getRecord(owner);
            if (record == null || !record.onSpiral()
                    || storage.getStructure(record.centre(), record.index()) != null) {
                return;
            }
            start(owner, playerName, record, false, outcome -> { });
        } catch (Throwable t) {
            // An enhancement to allocation, never a precondition for it.
            env.logger().log(Level.WARNING, "Could not start placing a structure for "
                    + playerName + ".", t);
        }
    }

    /**
     * For {@code /sgen structure}: checks, or places, the configured template on a player's
     * recorded plot now, whether or not {@code structure.enabled} is on.
     *
     * @param dryRun check every rule and change nothing
     * @param reply  receives the outcome once, on whichever thread it was decided
     */
    public void placeNow(UUID owner, String playerName, boolean dryRun, Consumer<Outcome> reply) {
        StoredSpawn record = env.storage().getRecord(owner);
        if (record == null) {
            reply.accept(new Outcome(false, false, playerName + " has no recorded plot."));
            return;
        }
        if (!record.onSpiral()) {
            reply.accept(new Outcome(false, false, playerName + "'s spawn was set by /sgen"
                    + " setspawn and is on no spiral plot, so no structure can be recorded for it."));
            return;
        }
        PlacedStructure existing = env.storage().getStructure(record.centre(), record.index());
        if (existing != null && !dryRun) {
            reply.accept(new Outcome(false, false, "Plot " + record.plotLabel() + " already has "
                    + describe(existing) + "; a plot is only ever given one."));
            return;
        }
        try {
            start(owner, playerName, record, dryRun, reply);
        } catch (Throwable t) {
            env.logger().log(Level.WARNING, "Could not start placing a structure for "
                    + playerName + ".", t);
            reply.accept(new Outcome(false, false, "The structure could not be started; see"
                    + " the console."));
        }
    }

    /** One line naming a recorded structure. */
    public static String describe(PlacedStructure placed) {
        return "the structure " + placed.file() + " placed " + placed.placedAt() + " for "
                + placed.player() + ", origin " + placed.x() + ", " + placed.y() + ", "
                + placed.z() + " in " + placed.world() + ", rotation " + placed.rotation();
    }

    private void start(UUID owner, String playerName, StoredSpawn record, boolean dryRun,
                       Consumer<Outcome> reply) {
        String plot = "plot " + record.plotLabel() + " for " + playerName;
        Consumer<Outcome> finish = outcome -> {
            if (!dryRun) {
                env.logger().info(outcome.message());
            }
            reply.accept(outcome);
        };
        Loaded current = loaded;
        if (current.template() == null) {
            finish.accept(skip(plot, "structure.file '" + current.file() + "' cannot be used: "
                    + current.error()));
            return;
        }
        Location spawn = record.toLocation();
        if (spawn == null) {
            finish.accept(skip(plot, "its world '" + record.worldName() + "' is not loaded"));
            return;
        }
        World world = spawn.getWorld();
        PluginConfig config = env.config();
        StructureSettings settings = config.getStructure();
        StructureRotation rotation = rotationFor(settings, record, spawn);
        Placement placement = Placement.of(current.template(), settings, spawn.getBlockX(),
                spawn.getBlockY(), spawn.getBlockZ(), rotation);
        CellArea footprint = placement.footprint();

        if (placement.topY() > world.getMaxHeight() - 1) {
            finish.accept(skip(plot, "its top would reach Y=" + placement.topY()
                    + ", past the build height of " + (world.getMaxHeight() - 1)));
            return;
        }
        if (placement.bottomY() < world.getMinHeight()) {
            finish.accept(skip(plot, "its floor would be below the bottom of the world"));
            return;
        }
        int size = config.getProtectionSize();
        if (!within(footprint, CellArea.square(spawn.getBlockX(), spawn.getBlockZ(), size))) {
            finish.accept(skip(plot, current.file() + " needs a " + current.requiredSize() + "x"
                    + current.requiredSize() + " square around the spawn and protection.size is "
                    + size));
            return;
        }

        Job job = new Job(owner, playerName, record, current, settings, placement, footprint,
                spawn, dryRun, finish);
        Set<Long> chunks = chunksOf(footprint);
        List<CompletableFuture<?>> loads = new ArrayList<>();
        try {
            for (long key : chunks) {
                loads.add(env.loadChunk(world, (int) (key >> 32), (int) key));
            }
        } catch (Throwable t) {
            finish.accept(skip(plot, "its chunks could not be requested (" + t + ")"));
            return;
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).whenComplete((ok, ex) -> {
            if (ex != null) {
                finish.accept(skip(plot, "its chunks could not be loaded (" + ex + ")"));
                return;
            }
            queue.add(job);
            drive();
        });
    }

    private StructureRotation rotationFor(StructureSettings settings, StoredSpawn record,
                                          Location spawn) {
        return switch (settings.rotation()) {
            case NONE -> StructureRotation.NONE;
            case RANDOM -> StructureRotation.values()[
                    ThreadLocalRandom.current().nextInt(StructureRotation.values().length)];
            case FACING_CENTRE -> {
                SpiralCentre centre = env.storage().getCentre(record.centre());
                int x = centre != null ? centre.originX() : env.config().getOriginX();
                int z = centre != null ? centre.originZ() : env.config().getOriginZ();
                yield Placement.facing(spawn.getBlockX(), spawn.getBlockZ(), x, z);
            }
        };
    }

    private void drive() {
        if (!driving.compareAndSet(false, true)) {
            return;
        }
        try {
            env.runEachTick(this::tick);
        } catch (Throwable t) {
            driving.set(false);
            env.logger().log(Level.WARNING, "Spawn plot structures could not be scheduled.", t);
            Job job;
            while ((job = queue.poll()) != null) {
                job.finish.accept(skip(job.plot(), "the scheduler refused the work"));
            }
        }
    }

    /**
     * One tick of the queue, bounded like the {@code /sgen protect} backfill: at most
     * {@link SpawnProtectionBackfill#MAX_PER_TICK} placements and
     * {@link SpawnProtectionBackfill#BUDGET_NANOS}, at least one. A placement whose region
     * this thread does not own (always, on Folia) is handed to that region's thread and ends
     * the tick's batch, so each tick starts at most one placement elsewhere.
     *
     * @return true when the queue is empty and the driver can stop
     */
    boolean tick() {
        long deadline = System.nanoTime() + SpawnProtectionBackfill.BUDGET_NANOS;
        int done = 0;
        Job job;
        while (done < SpawnProtectionBackfill.MAX_PER_TICK && (job = queue.poll()) != null) {
            done++;
            if (env.ownsChunk(job.spawn.getWorld(), job.spawn.getBlockX() >> 4,
                    job.spawn.getBlockZ() >> 4)) {
                job.run();
                if (System.nanoTime() >= deadline) {
                    break;
                }
            } else {
                Job handed = job;
                try {
                    env.runOnRegion(handed.spawn.getWorld(), handed.spawn.getBlockX() >> 4,
                            handed.spawn.getBlockZ() >> 4, handed::run);
                } catch (Throwable t) {
                    handed.finish.accept(skip(handed.plot(), "the region scheduler refused it"));
                }
                break;
            }
        }
        if (!queue.isEmpty()) {
            return false;
        }
        driving.set(false);
        // A job added between the empty check and the release would otherwise wait for the
        // next submission; take the driver back if one did.
        return queue.isEmpty() || !driving.compareAndSet(false, true);
    }

    private static Set<Long> chunksOf(CellArea area) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int cx = area.minX() >> 4; cx <= (area.maxX() - 1) >> 4; cx++) {
            for (int cz = area.minZ() >> 4; cz <= (area.maxZ() - 1) >> 4; cz++) {
                chunks.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            }
        }
        return chunks;
    }

    private static boolean within(CellArea inner, CellArea outer) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }

    private static Outcome skip(String plot, String reason) {
        return new Outcome(false, false, "No structure placed on " + plot + ": " + reason + ".");
    }

    private static boolean hasBlockEntity(Block block) {
        return block.getState() instanceof TileState;
    }

    /** One placement waiting for, or running on, the thread owning its spawn. */
    private final class Job {
        private final UUID owner;
        private final String playerName;
        private final StoredSpawn record;
        private final Loaded template;
        private final StructureSettings settings;
        private final Placement placement;
        private final CellArea footprint;
        private final Location spawn;
        private final boolean dryRun;
        private final Consumer<Outcome> finish;

        Job(UUID owner, String playerName, StoredSpawn record, Loaded template,
            StructureSettings settings, Placement placement, CellArea footprint, Location spawn,
            boolean dryRun, Consumer<Outcome> finish) {
            this.owner = owner;
            this.playerName = playerName;
            this.record = record;
            this.template = template;
            this.settings = settings;
            this.placement = placement;
            this.footprint = footprint;
            this.spawn = spawn;
            this.dryRun = dryRun;
            this.finish = finish;
        }

        String plot() {
            return "plot " + record.plotLabel() + " for " + playerName;
        }

        void run() {
            Outcome outcome;
            try {
                outcome = attempt();
            } catch (Throwable t) {
                env.logger().log(Level.WARNING, "Placing a structure on " + plot() + " failed.", t);
                outcome = skip(plot(), "it failed with " + t.getClass().getSimpleName()
                        + "; see the console");
            }
            finish.accept(outcome);
        }

        private Outcome attempt() {
            World world = spawn.getWorld();
            for (long key : chunksOf(footprint)) {
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if (!world.isChunkLoaded(cx, cz) || !env.ownsChunk(world, cx, cz)) {
                    return skip(plot(), "its chunks are not all loaded and owned by the spawn's"
                            + " region thread");
                }
            }
            DataStorage storage = env.storage();
            if (storage.isFailed()) {
                return skip(plot(), "data.yml could not be read, so the placement could not be"
                        + " recorded");
            }
            StoredSpawn now = storage.getRecord(owner);
            if (now == null || now.centre() != record.centre() || now.index() != record.index()
                    || now.x() != record.x() || now.y() != record.y() || now.z() != record.z()
                    || !now.worldName().equals(record.worldName())) {
                return skip(plot(), "the player's spawn changed before it could be placed");
            }
            if (!dryRun && storage.getStructure(record.centre(), record.index()) != null) {
                return skip(plot(), "it already has a recorded structure");
            }

            if (env.protector().isActive()) {
                ClaimInspection claim = env.inspector().inspect(spawn, owner);
                if (claim.status() != ClaimInspection.Status.FOUND) {
                    return skip(plot(), "protection is active but no claim of the player's"
                            + " covers the spawn");
                }
                CellArea bounds = CellArea.inclusive(claim.minX(), claim.minZ(), claim.maxX(),
                        claim.maxZ());
                if (!within(footprint, bounds)) {
                    return skip(plot(), "the claim at the spawn (" + claim.width() + "x"
                            + claim.depth() + ") does not hold the template's footprint");
                }
            }
            if (env.claimLookup().overlapsForeignClaim(world, footprint, owner)) {
                return skip(plot(), "the template would reach into a claim that is not the"
                        + " player's");
            }

            Set<Location> touched = new LinkedHashSet<>();
            for (Placement.WorldBlock block : placement.blocks()) {
                Block target = world.getBlockAt(block.x(), block.y(), block.z());
                String refusal = refusal(target);
                if (refusal != null) {
                    return skip(plot(), "it would replace " + refusal + " at " + at(target));
                }
                touched.add(target.getLocation());
            }

            Map<Location, Block> foundation = new LinkedHashMap<>();
            for (Placement.WorldBlock floor : placement.blocks()) {
                if (!floor.floor()) {
                    continue;
                }
                Block below = world.getBlockAt(floor.x(), floor.y() - 1, floor.z());
                if (below.getType().isSolid()) {
                    continue;
                }
                if (settings.terrain() == StructureSettings.Terrain.SKIP) {
                    return skip(plot(), "the ground under its floor is missing at " + at(below)
                            + " and structure.terrain is SKIP");
                }
                String gap = planFoundation(world, floor, foundation);
                if (gap != null) {
                    return skip(plot(), gap);
                }
            }

            if (dryRun) {
                return new Outcome(false, true, template.file() + " would be placed on " + plot()
                        + " with rotation " + placement.rotation()
                        + (foundation.isEmpty() ? "" : " and " + foundation.size()
                                + " foundation blocks")
                        + ", subject to the spawn safety check made after placing.");
            }

            List<BlockState> before = new ArrayList<>(touched.size() + foundation.size());
            for (Location location : touched) {
                before.add(location.getBlock().getState());
            }
            for (Block block : foundation.values()) {
                before.add(block.getState());
            }
            template.template().place(new Location(world, placement.originX(),
                    placement.originY(), placement.originZ()), placement.rotation(),
                    settings.includeEntities());
            for (Block block : foundation.values()) {
                block.setType(settings.foundationBlock(), false);
            }

            Block feet = world.getBlockAt(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
            Block head = feet.getRelative(0, 1, 0);
            if (feet.getType().isSolid() || head.getType().isSolid() || !env.isSafeSpawn(spawn)) {
                for (BlockState state : before) {
                    state.update(true, false);
                }
                return skip(plot(), "the spawn would no longer be safe to stand on; every block"
                        + " was put back");
            }

            PlacedStructure placed = new PlacedStructure(template.file(), world.getName(),
                    placement.originX(), placement.originY(), placement.originZ(),
                    placement.rotation().name(), Instant.now().toString(), playerName);
            if (!storage.recordStructure(record.centre(), record.index(), placed)) {
                env.logger().warning("The structure on " + plot() + " was placed but could not be"
                        + " recorded, because data.yml could not be read.");
            }
            return new Outcome(true, true, "Placed structure " + template.file() + " on "
                    + plot() + " at (" + spawn.getBlockX() + ", " + spawn.getBlockY() + ", "
                    + spawn.getBlockZ() + "), rotation " + placement.rotation()
                    + (foundation.isEmpty() ? "" : ", with " + foundation.size()
                            + " foundation blocks") + ".");
        }

        /**
         * Adds the foundation for one floor block: every block straight down from under it
         * until the ground, within {@code foundation-depth}.
         *
         * @return why it cannot be built, or {@code null}
         */
        private String planFoundation(World world, Placement.WorldBlock floor,
                                      Map<Location, Block> foundation) {
            for (int depth = 1; depth <= settings.foundationDepth(); depth++) {
                int y = floor.y() - depth;
                if (y < world.getMinHeight()) {
                    return "the ground under its floor at " + floor.x() + ", " + floor.z()
                            + " is below the bottom of the world";
                }
                Block block = world.getBlockAt(floor.x(), y, floor.z());
                if (block.getType().isSolid()) {
                    return null;
                }
                String refusal = refusal(block);
                if (refusal != null) {
                    return "its foundation would replace " + refusal + " at " + at(block);
                }
                foundation.put(block.getLocation(), block);
            }
            Block next = world.getBlockAt(floor.x(), floor.y() - settings.foundationDepth() - 1,
                    floor.z());
            if (next.getType().isSolid()) {
                return null;
            }
            return "the ground under its floor at " + floor.x() + ", " + floor.z()
                    + " is more than structure.foundation-depth (" + settings.foundationDepth()
                    + ") blocks down";
        }
    }

    /** Why a block must not be replaced, or {@code null}. */
    private static String refusal(Block block) {
        if (block.getType() == Material.BEDROCK) {
            return "bedrock";
        }
        if (hasBlockEntity(block)) {
            return "a block entity (" + block.getType().getKey().getKey() + ")";
        }
        return null;
    }

    private static String at(Block block) {
        return "(" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")";
    }
}
