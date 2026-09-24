package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import com.ninja6.spiralgenesis.config.PluginConfig;
import com.ninja6.spiralgenesis.manager.CellReserver;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.math.SpiralCell;
import com.ninja6.spiralgenesis.protection.ProtectionProvider;
import com.ninja6.spiralgenesis.protection.RecordingProvider;
import com.ninja6.spiralgenesis.protection.ReleaseOutcome;
import com.ninja6.spiralgenesis.protection.ReleaseResult;
import com.ninja6.spiralgenesis.protection.SpawnClaimRelease;
import com.ninja6.spiralgenesis.protection.SpawnProtectionBackfill;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three settled behaviours that live in the command layer, tested at the level they
 * were specified rather than one below it.
 *
 * <p>{@code /sgen setspawn}, {@code /sgen reassign} with and without its release token, and
 * the revalidation repair each have a defined answer about the claim left behind, and the
 * reassign default is required to be <em>incapable</em> of deleting anything. Asserting that
 * against {@code SpawnProtector} alone would leave the argument parsing that decides which
 * of the two paths runs completely untested, which is exactly where a default could flip.
 */
class SpiralCommandProtectionTest {

    /**
     * The literal that authorises a deletion. A copy of {@code SpiralCommand.RELEASE_FLAG},
     * which is private, so a rename there would leave the "the output does not advise this"
     * assertion below looking for a string nothing prints any more. The test underneath it
     * runs the token for real and would fail on such a rename, which is what actually keeps
     * the pair honest.
     */
    private static final String RELEASE_TOKEN = "release";

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        try {
            MockBukkit.unmock();
        } catch (UnimplementedOperationException e) {
            // Disabling the plugin cancels the storage flush task, which MockBukkit cannot
            // cancel. Swallowed for the reason AllocationOwnershipTest documents: this
            // exception extends TestAbortedException, so letting it out of teardown reports
            // every test in the class as skipped rather than run.
            MockBukkit.unmock();
        }
    }

    /** A spawn manager that hands back a fixed point without touching a chunk. */
    private static final class StubSpawnManager extends SpawnManager {

        private Location next;
        private int index = 7;

        private final PluginConfig config;

        private StubSpawnManager(JavaPlugin plugin, World world, PluginConfig config) {
            super(plugin, world, config);
            this.config = config;
        }

        private SpawnManager.LocationResult result() {
            return new SpawnManager.LocationResult(next, index, 0, 0, 63, 1, 1, false, Map.of());
        }

        @Override
        public CompletableFuture<AllocationOutcome> allocateNextSafeSpawn(CellReserver cells) {
            index = cells.reserve(config).index();
            return CompletableFuture.completedFuture(result());
        }

        @Override
        public CompletableFuture<LocationResult> findSafeSpawnInCell(SpiralCell cell, UUID owner) {
            index = cell.index();
            return CompletableFuture.completedFuture(result());
        }
    }

    /** Injects the provider and the stub search, and nothing else. */
    public static class CommandPlugin extends SpiralGenesisPlugin {

        ProtectionProvider provider = new RecordingProvider();
        StubSpawnManager stub;

        @Override
        public ProtectionProvider getProtectionProvider() {
            return provider;
        }

        @Override
        public SpawnManager getSpawnManager() {
            if (stub == null) {
                stub = new StubSpawnManager(this, Bukkit.getWorlds().get(0), getPluginConfig());
            }
            return stub;
        }

        @Override
        public CompletableFuture<SpawnManager.LocationResult> searchInCell(SpiralCell cell,
                                                                            UUID owner) {
            return getSpawnManager().findSafeSpawnInCell(cell, owner);
        }

        /**
         * When set, jobs are accepted and never driven, so they stay "running" for a test
         * of what else is refused meanwhile.
         */
        boolean holdJobs;

        /** Drives the release inline, since MockBukkit has no global region scheduler. */
        @Override
        void driveClaimRelease(SpawnClaimRelease job, Runnable onFinish) {
            if (holdJobs) {
                return;
            }
            while (!job.runBatch()) {
                // One iteration stands in for one tick.
            }
            onFinish.run();
        }

        @Override
        void driveBackfill(SpawnProtectionBackfill job, Runnable onFinish) {
            if (holdJobs) {
                return;
            }
            while (!job.runBatch()) {
                // One iteration stands in for one tick.
            }
            onFinish.run();
        }
    }

    private CommandPlugin load() {
        return load("ADMIN_CLAIM");
    }

    private CommandPlugin load(String claimAs) {
        CommandPlugin plugin = MockBukkit.loadWith(CommandPlugin.class,
                getClass().getResourceAsStream("/plugin.yml"));
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("allocation.action-timeout-seconds", 0);
        yaml.set("protection.claim-as", claimAs);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        plugin.reload();
        return plugin;
    }

    private InlinePlayerMock join(String name) {
        InlinePlayerMock player = new InlinePlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    /** Everything the console was told since it was last drained. */
    private List<String> drain() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = console.nextMessage()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private boolean saidSomethingContaining(List<String> lines, String fragment) {
        return lines.stream().anyMatch(line -> line.contains(fragment));
    }

    @Test
    @DisplayName("setspawn claims the new point and leaves the old claim, naming where it is")
    void setspawnClaimsAndReportsTheStaleClaim() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        join("Bob");

        server.executeConsole("sgen", "setspawn", "Bob", "10", "64", "10").assertSucceeded();
        drain();

        assertEquals(1, provider.reservations.size());
        assertEquals(10, provider.reservations.get(0).centre().getBlockX());

        // Moving it again is what produces a stale claim to report.
        server.executeConsole("sgen", "setspawn", "Bob", "200", "70", "200").assertSucceeded();
        List<String> said = drain();

        assertEquals(2, provider.reservations.size());
        assertEquals(200, provider.reservations.get(1).centre().getBlockX());
        assertTrue(provider.releases.isEmpty(), "setspawn must never delete a claim");
        assertTrue(saidSomethingContaining(said, "still standing"), said.toString());
        assertTrue(saidSomethingContaining(said, "10, 64, 10"),
                "the operator has to be told where the stale claim is: " + said);
    }

    @Test
    @DisplayName("reassign without the token claims the new plot and deletes nothing")
    void reassignWithoutTheTokenDeletesNothing() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 10, 64, 10),
                1, 0, 0, "Bob", "JAVA");
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 900, 64, 900);

        server.executeConsole("sgen", "reassign", "Bob").assertSucceeded();
        List<String> said = drain();

        assertEquals(1, provider.reservations.size());
        assertEquals(900, provider.reservations.get(0).centre().getBlockX(),
                "the new plot is what gets claimed");
        assertTrue(provider.releases.isEmpty(),
                "the default reassign must be incapable of deleting anything");
        assertTrue(saidSomethingContaining(said, "still standing"), said.toString());
        assertTrue(saidSomethingContaining(said, "10, 64, 10"),
                "the operator has to be told where the stale claim is: " + said);
        assertTrue(saidSomethingContaining(said, "your protection plugin's own commands"),
                "the only thing that can remove the named square belongs in the output: " + said);
        // The regression this pins. The line used to end "Re-run with '/sgen reassign Bob
        // release' to remove it.", which reads as an undo and is not one: see
        // rerunningWithTheTokenReleasesTheSecondSquareNotTheFirst below for what following
        // that advice actually does. No output from this command may spell a runnable
        // reassign command an operator could copy out of it.
        assertFalse(saidSomethingContaining(said, "/sgen reassign Bob " + RELEASE_TOKEN),
                "the output must not advise re-running the command it is reporting on: " + said);
    }

    /**
     * Why the hint in {@link #reassignWithoutTheTokenDeletesNothing} cannot say what it
     * used to say, demonstrated rather than asserted about.
     *
     * <p>An operator who followed the old advice ran the command a second time. This is
     * that second run: the player is moved on to a third plot, and the square released is
     * the one they were sitting on at the moment the command was typed - the plot the
     * <em>first</em> reassignment had just given them - not the original square the message
     * was about, which is left exactly where it was. So the advice cost a second
     * reassignment and released the one square nobody had raised. The behaviour is correct
     * and stays exactly as it is; it is the wording that had to stop pointing at it.
     */
    @Test
    @DisplayName("re-running reassign with the token releases the square just left, not the one reported")
    void rerunningWithTheTokenReleasesTheSecondSquareNotTheFirst() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 10, 64, 10),
                1, 0, 0, "Bob", "JAVA");
        plugin.getSpawnManager();

        plugin.stub.next = new Location(world, 900, 64, 900);
        server.executeConsole("sgen", "reassign", "Bob").assertSucceeded();
        drain();

        plugin.stub.next = new Location(world, 1500, 64, 1500);
        server.executeConsole("sgen", "reassign", "Bob", RELEASE_TOKEN).assertSucceeded();

        assertEquals(1, provider.releases.size(), "the token releases exactly one square");
        assertEquals(900, provider.releases.get(0).centre().getBlockX(),
                "the square released is the one the player is leaving on this run");
        assertTrue(provider.releases.stream().noneMatch(r -> r.centre().getBlockX() == 10),
                "the square the first run reported on is never the one that goes");
        assertEquals(1500, (int) plugin.getDataStorage().getRecord(player.getUniqueId()).x(),
                "and the player has been moved a second time, to a third plot");
    }

    @Test
    @DisplayName("reassign with the release token releases the old claim, and only the old one")
    void reassignWithTheTokenReleasesTheOldClaim() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        plugin.getDataStorage().setSpawn(player.getUniqueId(), new Location(world, 10, 64, 10),
                1, 0, 0, "Bob", "JAVA");
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 900, 64, 900);

        server.executeConsole("sgen", "reassign", "Bob", "release").assertSucceeded();
        List<String> said = drain();

        assertEquals(1, provider.reservations.size());
        assertEquals(900, provider.reservations.get(0).centre().getBlockX());
        assertEquals(1, provider.releases.size(), "the token must reach the release");
        assertEquals(10, provider.releases.get(0).centre().getBlockX(),
                "and it must be the old point that is released, not the new one");
        assertTrue(saidSomethingContaining(said, "Released"), said.toString());
    }

    @Test
    @DisplayName("a mistyped third argument is refused rather than ignored")
    void aMistypedTokenIsRefused() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        Location before = new Location(world, 10, 64, 10);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), before, 1, 0, 0, "Bob", "JAVA");

        server.executeConsole("sgen", "reassign", "Bob", "relase");
        List<String> said = drain();

        assertTrue(saidSomethingContaining(said, "Unknown argument"), said.toString());
        assertTrue(provider.reservations.isEmpty(), "nothing should have been reassigned");
        assertTrue(provider.releases.isEmpty(), "and certainly nothing deleted");
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record);
        assertEquals(10, (int) record.x(), "the player must not have been moved");
    }

    @Test
    @DisplayName("a revalidation move claims the point the player actually ends up on")
    void revalidationClaimsTheReplacementPoint() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Bob");
        Location unsafe = new Location(world, 10, 64, 10);
        plugin.getDataStorage().setSpawn(player.getUniqueId(), unsafe, 3, 0, 0, "Bob", "JAVA");
        plugin.getSpawnManager();
        plugin.stub.next = new Location(world, 44, 64, 44);
        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());

        // confirmFirst false, which is the branch the respawn handler takes when it has
        // already judged the stored point unsafe inline.
        plugin.repairSpawn(player, record, false);

        assertEquals(1, provider.reservations.size());
        assertEquals(44, provider.reservations.get(0).centre().getBlockX(),
                "the player must be protected where they actually spawn, not where they did");
        assertTrue(provider.releases.isEmpty(),
                "revalidation leaves the old claim standing, like every other path");
        assertEquals(44, (int) plugin.getDataStorage().getRecord(player.getUniqueId()).x());
    }

    /** Two players with stored plots, one of them offline, as an uninstall finds them. */
    private List<java.util.UUID> storeTwoPlots(CommandPlugin plugin) {
        InlinePlayerMock online = join("Bob");
        java.util.UUID offline = java.util.UUID.randomUUID();
        plugin.getDataStorage().setSpawn(online.getUniqueId(), new Location(world, 10, 64, 10),
                1, 0, 0, "Bob", "JAVA");
        plugin.getDataStorage().setSpawn(offline, new Location(world, 600, 64, 600),
                2, 1, 0, "Carol", "JAVA");
        return List.of(online.getUniqueId(), offline);
    }

    @Test
    @DisplayName("release-all without the confirm token says what it would do and deletes nothing")
    void releaseAllWithoutConfirmDeletesNothing() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        storeTwoPlots(plugin);

        server.executeConsole("sgen", "release-all").assertSucceeded();
        List<String> said = drain();

        assertTrue(provider.releases.isEmpty(), "nothing may go without the confirm token");
        assertTrue(saidSomethingContaining(said, "/sgen release-all confirm"), said.toString());
        assertTrue(saidSomethingContaining(said, "2 stored players"), said.toString());
    }

    @Test
    @DisplayName("release-all confirm releases each current plot's claim and keeps every record")
    void releaseAllConfirmReleasesEveryCurrentPlot() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider().releasing(r ->
                r.centre().getBlockX() == 600
                        ? ReleaseResult.of(ReleaseOutcome.NOT_OURS, "it has been resized.")
                        : ReleaseResult.of(ReleaseOutcome.RELEASED));
        plugin.provider = provider;
        List<java.util.UUID> owners = storeTwoPlots(plugin);

        server.executeConsole("sgen", "release-all", "CONFIRM").assertSucceeded();
        List<String> said = drain();

        assertEquals(2, provider.releases.size(), provider.releases.toString());
        assertTrue(provider.releases.stream().anyMatch(r -> r.owner().equals(owners.get(1))
                && r.centre().getBlockX() == 600), "an offline player's claim is reached too");
        assertTrue(provider.reservations.isEmpty(), "releasing must never claim anything");
        assertTrue(saidSomethingContaining(said, "1 released, 1 not ours"), said.toString());
        for (java.util.UUID owner : owners) {
            assertNotNull(plugin.getDataStorage().getRecord(owner),
                    "every spawn record is kept");
        }
        assertFalse(plugin.isClaimReleaseRunning(), "the job must clear itself when done");
    }

    @Test
    @DisplayName("release-all is refused under PLAYER_CLAIM")
    void releaseAllIsRefusedUnderPlayerClaim() {
        CommandPlugin plugin = load("PLAYER_CLAIM");
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        storeTwoPlots(plugin);

        server.executeConsole("sgen", "release-all", "confirm").assertSucceeded();
        List<String> said = drain();

        assertTrue(provider.releases.isEmpty(), said.toString());
        assertTrue(saidSomethingContaining(said, "PLAYER_CLAIM"), said.toString());
    }

    @Test
    @DisplayName("release-all is refused when no protection plugin is available, as on Folia")
    void releaseAllIsRefusedWithoutAProvider() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider().available(false);
        plugin.provider = provider;
        storeTwoPlots(plugin);

        server.executeConsole("sgen", "release-all", "confirm").assertSucceeded();
        List<String> said = drain();

        assertTrue(provider.releases.isEmpty(), said.toString());
        assertTrue(saidSomethingContaining(said, "not active"), said.toString());
        assertFalse(plugin.isClaimReleaseRunning());
    }

    @Test
    @DisplayName("release-all refuses a mistyped token and completes the right one")
    void releaseAllRefusesAMistypedToken() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        storeTwoPlots(plugin);

        server.executeConsole("sgen", "release-all", "confrim").assertSucceeded();
        List<String> said = drain();

        assertTrue(provider.releases.isEmpty(), said.toString());
        assertTrue(saidSomethingContaining(said, "Usage"), said.toString());
        assertEquals(List.of("confirm"), plugin.getCommand("sgen").tabComplete(
                server.getConsoleSender(), "sgen", new String[] {"release-all", "con"}));
        assertTrue(plugin.getCommand("sgen").tabComplete(server.getConsoleSender(), "sgen",
                new String[] {"rel"}).contains("release-all"));
    }

    @Test
    @DisplayName("a reload to PLAYER_CLAIM mid-run stops release-all before the next claim")
    void releaseAllStopsWhenAReloadSwitchesToPlayerClaim() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        provider.releasing(r -> {
            // The operator's reload lands between two entries of the running job.
            File file = new File(plugin.getDataFolder(), "config.yml");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            yaml.set("protection.claim-as", "PLAYER_CLAIM");
            try {
                yaml.save(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            plugin.reload();
            return ReleaseResult.of(ReleaseOutcome.RELEASED);
        });
        plugin.provider = provider;
        storeTwoPlots(plugin);

        server.executeConsole("sgen", "release-all", "confirm").assertSucceeded();
        List<String> said = drain();

        assertEquals(1, provider.releases.size(),
                "no claim may be released once claim-as is PLAYER_CLAIM: " + said);
        assertTrue(saidSomethingContaining(said, "stopped because protection.claim-as was "
                + "changed to PLAYER_CLAIM"), said.toString());
        assertTrue(saidSomethingContaining(said, "1 skipped"), said.toString());
        assertFalse(plugin.isClaimReleaseRunning());
    }

    @Test
    @DisplayName("protect is refused while release-all runs")
    void protectIsRefusedWhileReleaseAllRuns() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        storeTwoPlots(plugin);
        plugin.holdJobs = true;

        server.executeConsole("sgen", "release-all", "confirm").assertSucceeded();
        assertTrue(plugin.isClaimReleaseRunning());
        drain();

        server.executeConsole("sgen", "protect").assertSucceeded();
        List<String> said = drain();

        assertFalse(plugin.isProtectionBackfillRunning(), said.toString());
        assertTrue(provider.reservations.isEmpty(), said.toString());
        assertTrue(saidSomethingContaining(said, "spawn claim release is running"), said.toString());
    }

    @Test
    @DisplayName("release-all is refused while a protect backfill runs")
    void releaseAllIsRefusedWhileProtectRuns() {
        CommandPlugin plugin = load();
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        storeTwoPlots(plugin);
        plugin.holdJobs = true;

        server.executeConsole("sgen", "protect").assertSucceeded();
        assertTrue(plugin.isProtectionBackfillRunning());
        drain();

        server.executeConsole("sgen", "release-all", "confirm").assertSucceeded();
        List<String> said = drain();

        assertFalse(plugin.isClaimReleaseRunning(), said.toString());
        assertTrue(provider.releases.isEmpty(), said.toString());
        assertTrue(saidSomethingContaining(said, "backfill is running"), said.toString());
    }
}
