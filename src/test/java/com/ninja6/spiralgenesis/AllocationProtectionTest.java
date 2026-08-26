package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ninja6.spiralgenesis.manager.SpawnManager;
import com.ninja6.spiralgenesis.protection.ClaimOutcome;
import com.ninja6.spiralgenesis.protection.ProtectionProvider;
import com.ninja6.spiralgenesis.protection.RecordingProvider;
import com.ninja6.spiralgenesis.protection.SpawnProtectionBackfill;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation against a protection provider, and specifically against one that never works.
 *
 * <p>The claim is an enhancement to allocation and must never become a precondition for it,
 * so the case that matters most here is the one where every call into the provider fails:
 * the player still gets their plot, still gets teleported, and still gets their respawn
 * point. A regression in that is not a lost claim, it is a player who cannot join.
 *
 * <p>The seams are the ones {@code AllocationOwnershipTest} established - MockBukkit
 * implements neither Paper's entity scheduler nor async chunk loading - plus one more:
 * {@code getProtectionProvider} is overridden to inject a provider, which is why every call
 * site in the plugin reads the provider through that method rather than through the field
 * behind it.
 */
class AllocationProtectionTest {

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
            // Same defect AllocationOwnershipTest documents: disabling the plugin cancels
            // the storage flush task, which MockBukkit cannot cancel. Letting it out of
            // teardown would report every test in this class as skipped rather than run.
            MockBukkit.unmock();
        }
    }

    /** Resolves the asynchronous seams immediately and lets a test choose the provider. */
    public static class ProtectedPlugin extends SpiralGenesisPlugin {

        /** The provider every call site in the plugin will see. */
        ProtectionProvider provider = new RecordingProvider();

        @Override
        public ProtectionProvider getProtectionProvider() {
            return provider;
        }

        @Override
        CompletableFuture<SpawnManager.LocationResult> allocateSpawn(IntSupplier indexSupplier) {
            int index = indexSupplier.getAsInt();
            Location where = new Location(Bukkit.getWorlds().get(0), index * 16 + 8, 64, 24);
            return CompletableFuture.completedFuture(new SpawnManager.LocationResult(
                    where, index, 0, 0, 63, 1, 1, false, Map.of()));
        }

        @Override
        boolean runForPlayer(Player player, Runnable action, Runnable retired) {
            action.run();
            return true;
        }
    }

    /**
     * Drives the backfill inline, since MockBukkit has no global region scheduler.
     *
     * <p>A subclass rather than a flag, matching how the other asynchronous seams in this
     * plugin are reached from a test: the batching that is worth asserting lives in the job,
     * and the scheduling around it is what cannot run here.
     */
    public static class InlineBackfillPlugin extends ProtectedPlugin {
        @Override
        void driveBackfill(SpawnProtectionBackfill job, Runnable onFinish) {
            while (!job.runBatch()) {
                // One iteration stands in for one tick.
            }
            onFinish.run();
        }
    }

    private <T extends ProtectedPlugin> T load(Class<T> type) {
        // loadWith, not load: MockBukkit looks for plugin.yml beside the class it is given,
        // and these variants compile into the test tree rather than next to the resource.
        T plugin = MockBukkit.loadWith(type, getClass().getResourceAsStream("/plugin.yml"));
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("allocation.action-timeout-seconds", 0);
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

    /** One uncancelled step, which is what settles anything the gate is still holding. */
    private void move(InlinePlayerMock player) {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 1, 64, 0);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));
    }

    @Test
    @DisplayName("allocation completes in full against a provider that fails every call")
    void allocationSurvivesAProviderThatAlwaysFails() {
        ProtectedPlugin plugin = load(ProtectedPlugin.class);
        // Not merely refusing: throwing, which is the contract breach a provider is not
        // supposed to be capable of. The claim is an enhancement to allocation and must
        // never be a precondition for it, so this is the case that matters most.
        RecordingProvider provider = new RecordingProvider().throwing();
        plugin.provider = provider;
        InlinePlayerMock player = join("Unlucky");

        plugin.allocateNow(player, "JAVA");

        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record, "the player must still get their plot");
        Location plot = record.toLocation();
        assertNotNull(plot);
        assertEquals(plot.getBlockX(), player.getLocation().getBlockX(),
                "they must still be teleported to it");
        assertEquals(plot.getBlockZ(), player.getLocation().getBlockZ());
        assertNotNull(player.getRespawnLocation(), "they must still get their respawn point");
        assertEquals(1, provider.reservations.size(), "and the claim must have been attempted");
    }

    @Test
    @DisplayName("allocation completes when the provider throws from its availability check")
    void allocationSurvivesAProviderThatCannotSayWhetherItWorks() {
        ProtectedPlugin plugin = load(ProtectedPlugin.class);
        // A different failure from the one above, and reached earlier: this provider throws
        // from the question "can you claim anything at all", which is asked before any claim
        // is attempted. A caller reading availability outside its guard survives a throwing
        // reserve and not this, so the two cases are separate on purpose.
        plugin.provider = new RecordingProvider().throwingAvailability();
        InlinePlayerMock player = join("Unluckier");

        plugin.allocateNow(player, "JAVA");

        StoredSpawn record = plugin.getDataStorage().getRecord(player.getUniqueId());
        assertNotNull(record, "the player must still get their plot");
        Location plot = record.toLocation();
        assertNotNull(plot);
        assertEquals(plot.getBlockX(), player.getLocation().getBlockX(),
                "they must still be teleported to it");
        assertNotNull(player.getRespawnLocation(), "they must still get their respawn point");
    }

    @Test
    @DisplayName("the claim is asked for after the spawn is stored, and at the stored point")
    void theClaimFollowsTheStoredSpawn() {
        ProtectedPlugin plugin = load(ProtectedPlugin.class);
        AtomicBoolean storedFirst = new AtomicBoolean();
        RecordingProvider provider = new RecordingProvider();
        InlinePlayerMock player = join("Ordered");
        // Read at the moment the provider is called, which is the only place the ordering
        // is observable. A claim around a point that is not yet the player's recorded spawn
        // is a claim around ground they may never be sent to.
        provider.onReserve(() -> storedFirst.set(
                plugin.getDataStorage().hasSpawn(player.getUniqueId())));
        plugin.provider = provider;

        plugin.allocateNow(player, "JAVA");

        assertTrue(storedFirst.get(), "the spawn must be stored before the claim is asked for");
        assertEquals(1, provider.reservations.size());
        RecordingProvider.Reservation asked = provider.reservations.get(0);
        Location stored = plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation();
        assertNotNull(stored);
        assertEquals(stored.getBlockX(), asked.centre().getBlockX());
        assertEquals(stored.getBlockZ(), asked.centre().getBlockZ());
        assertEquals(plugin.getPluginConfig().getProtectionSize(), asked.size());
        assertEquals(player.getUniqueId(), asked.owner());
    }

    @Test
    @DisplayName("a refused teleport and its re-assert never ask for a second claim")
    void theTeleportReassertClaimsNothingOfItsOwn() {
        ProtectedPlugin plugin = load(ProtectedPlugin.class);
        RecordingProvider provider = new RecordingProvider();
        plugin.provider = provider;
        InlinePlayerMock player = join("Held");
        player.teleportSucceeds = false;

        plugin.allocateNow(player, "JAVA");

        // The claim was already attempted before the teleport that failed, because the
        // failure is what arms the re-assert and it happens strictly after the claim.
        assertEquals(1, provider.reservations.size());

        // The player's first uncancelled action, which is what fires the re-assert.
        move(player);

        assertEquals(1, provider.reservations.size(),
                "the re-assert re-sends the player to the same stored point, so there is no "
                        + "new ground for a claim to be needed around");
        assertTrue(provider.releases.isEmpty(), "and nothing on that path deletes anything");
    }

    @Test
    @DisplayName("a server with no provider never reaches one")
    void aDefaultInstallClaimsNothing() {
        ProtectedPlugin plugin = load(ProtectedPlugin.class);
        RecordingProvider provider = new RecordingProvider().available(false);
        plugin.provider = provider;
        InlinePlayerMock player = join("Plain");

        plugin.allocateNow(player, "JAVA");

        assertTrue(plugin.getDataStorage().hasSpawn(player.getUniqueId()));
        assertTrue(provider.reservations.isEmpty(),
                "a server that has never heard of a claim plugin must behave exactly as before");
    }

    @Test
    @DisplayName("the backfill claims a player who was allocated before protection existed")
    void theBackfillCoversAnExistingPlayer() {
        InlineBackfillPlugin plugin = load(InlineBackfillPlugin.class);
        RecordingProvider offAtFirst = new RecordingProvider().available(false);
        plugin.provider = offAtFirst;
        InlinePlayerMock player = join("Early");
        plugin.allocateNow(player, "JAVA");
        assertTrue(offAtFirst.reservations.isEmpty());

        // The operator switches protection on afterwards, which is exactly the situation
        // /sgen protect exists for: allocation fires once per player and will never revisit
        // anybody already in data.yml.
        RecordingProvider onNow = new RecordingProvider();
        plugin.provider = onNow;

        StringBuilder reported = new StringBuilder();
        assertNotNull(plugin.startProtectionBackfill(reported::append));

        assertEquals(1, onNow.reservations.size());
        assertEquals(player.getUniqueId(), onNow.reservations.get(0).owner());
        assertTrue(reported.toString().contains("1 created"), reported.toString());
        assertFalse(plugin.isProtectionBackfillRunning(), "the job must clear itself when done");

        // Running it again finds the square already reserved, which is the whole of its
        // idempotency: nothing on disk records that it ever ran.
        RecordingProvider second = new RecordingProvider().answering(ClaimOutcome.ALREADY_CLAIMED);
        plugin.provider = second;
        StringBuilder again = new StringBuilder();
        assertNotNull(plugin.startProtectionBackfill(again::append));
        assertTrue(again.toString().contains("0 created"), again.toString());
        assertTrue(again.toString().contains("1 skipped"), again.toString());
    }

}
