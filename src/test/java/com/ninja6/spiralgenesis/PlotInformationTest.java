package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import com.ninja6.spiralgenesis.commands.SpiralCommand;
import com.ninja6.spiralgenesis.config.ClaimOwnership;
import com.ninja6.spiralgenesis.protection.*;
import com.ninja6.spiralgenesis.storage.StoredSpawn;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class PlotInformationTest {
    private ServerMock server;
    private WorldMock world;
    private NoticePlugin plugin;
    private InlinePlayerMock player;

    public static class NoticePlugin extends SpiralGenesisPlugin {
        ClaimInspection inspection = ClaimInspection.unavailable();
        ProtectionProvider provider = new RecordingProvider().answering(r ->
                new ClaimResult(ClaimOutcome.CREATED, "", ClaimChange.CREATED_TRUSTED));
        @Override public ProtectionProvider getProtectionProvider() { return provider; }
        @Override boolean runForPlayer(org.bukkit.entity.Player player, Runnable action, Runnable retired) {
            action.run();
            return true;
        }
        @Override java.util.concurrent.CompletableFuture<com.ninja6.spiralgenesis.manager.SpawnManager.AllocationOutcome>
                allocateSpawn(com.ninja6.spiralgenesis.manager.CellReserver cells) {
            int index = cells.reserve(getPluginConfig()).index();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new com.ninja6.spiralgenesis.manager.SpawnManager.LocationResult(
                            new Location(org.bukkit.Bukkit.getWorld("world"), index * 16 + 8, 64, 24),
                            index, 0, 0, 63, 1, 1, false, Map.of()));
        }
        @Override void driveBackfill(SpawnProtectionBackfill job, Runnable onFinish) {
            while (!job.runBatch()) { }
            onFinish.run();
        }
        @Override public void inspectPlot(StoredSpawn record, UUID owner, Consumer<ClaimInspection> reply) {
            reply.accept(inspection);
        }
    }

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.loadWith(NoticePlugin.class, getClass().getResourceAsStream("/plugin.yml"));
        plugin.getConfig().set("allocation.action-timeout-seconds", 0);
        plugin.saveConfig();
        plugin.reload();
        player = new InlinePlayerMock(server, "Builder");
        server.addPlayer(player);
        player.setOp(false);
        player.addAttachment(plugin, "spiralgenesis.me", true);
        store(new Location(world, -0.5, 64, -12.5));
        drain();
    }
    @AfterEach void cleanup() {
        try { MockBukkit.unmock(); }
        catch (UnimplementedOperationException e) { MockBukkit.unmock(); }
    }
    private void store(Location spawn) {
        assertTrue(plugin.getDataStorage().setSpawn(player.getUniqueId(), spawn, 1, 0, 0,
                player.getName(), "JAVA"));
        player.setRespawnLocation(spawn, true);
    }
    private List<String> drain() {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = player.nextMessage()) != null) { lines.add(line); }
        return lines;
    }
    private String output() { return String.join("\n", drain()); }
    private void command(String... args) {
        new SpiralCommand(plugin).onCommand(player, plugin.getCommand("sgen"), "sgen", args);
    }
    private ClaimInspection claim(boolean build, boolean manage) {
        return new ClaimInspection(ClaimInspection.Status.FOUND, -5, -15, 5, -5,
                ClaimOwnership.ADMIN_CLAIM, false, build, manage);
    }

    @Test void nonAdminCanDispatchRootAndOnlySeesMe() {
        assertNull(plugin.getCommand("sgen").getPermission());
        server.dispatchCommand(player, "sgen me");
        String text = output();
        assertTrue(text.contains("-1, 64, -13"), text);
        assertTrue(text.contains("Protection information is unavailable"));
        assertTrue(text.contains("your respawn point"));
        command();
        assertTrue(output().contains("/sgen me"));
        for (String token : List.of("setspawn", "reload", "info", "unknown")) {
            command(token);
            String denied = output();
            assertTrue(denied.contains("permission"), denied);
            assertFalse(denied.contains("/sgen setcenter"));
        }
        SpiralCommand cmd = new SpiralCommand(plugin);
        assertEquals(List.of("me"), cmd.onTabComplete(player, plugin.getCommand("sgen"), "sgen", new String[]{""}));
        assertEquals(List.of(), cmd.onTabComplete(player, plugin.getCommand("sgen"), "sgen", new String[]{"info", ""}));
    }

    @Test void existingClaimIsShownWithProtectionDisabledAndWithoutInventingManage() {
        plugin.getConfig().set("protection.enabled", false);
        plugin.getConfig().set("protection.size", 101);
        plugin.inspection = claim(true, false);
        command("me");
        String text = output();
        assertTrue(text.contains("11 x 11"), text);
        assertTrue(text.contains("X -5 to 5"));
        assertTrue(text.contains("containers and beds"));
        assertFalse(text.contains("/trust"));
        plugin.inspection = claim(true, true);
        command("me");
        assertTrue(output().contains("/trust"));
    }

    @Test void notificationsUseInspectionAndEventChanges() {
        Location spawn = plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation();
        plugin.inspection = ClaimInspection.none();
        plugin.plotInformation().notify(player, spawn, false,
                new ClaimResult(ClaimOutcome.CREATED, "", ClaimChange.CREATED_TRUST_FAILED));
        String text = output();
        assertTrue(text.contains("No claim"), text);
        assertFalse(text.contains("build access"));
        plugin.inspection = claim(true, true);
        plugin.plotInformation().notify(player, spawn, false, ClaimResult.of(ClaimOutcome.ALREADY_CLAIMED));
        assertEquals("", output());
        plugin.plotInformation().notify(player, spawn, false,
                new ClaimResult(ClaimOutcome.ALREADY_CLAIMED, "", ClaimChange.ACCESS_REPAIRED));
        assertTrue(output().contains("/trust"));
        plugin.plotInformation().notify(player, spawn, false, ClaimResult.of(ClaimOutcome.REFUSED));
        assertEquals("", output());
        plugin.plotInformation().notify(player, new Location(world, 50, 64, 50), true,
                new ClaimResult(ClaimOutcome.CREATED, "", ClaimChange.CREATED_TRUSTED));
        assertEquals("", output());
    }

    @Test void templatesAndSwitchesReloadAndDoNotDisableCommand() {
        plugin.getConfig().set("messages.plot", "&aHome {x}/{z}");
        plugin.getConfig().set("messages.allocation-notices", false);
        plugin.getConfig().set("messages.claim-notices", false);
        plugin.saveConfig();
        plugin.reload();
        Location spawn = plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation();
        plugin.plotInformation().notify(player, spawn, true,
                new ClaimResult(ClaimOutcome.CREATED, "", ClaimChange.CREATED_TRUSTED));
        assertEquals("", output());
        command("me");
        assertTrue(output().contains("Home -1/-13"));
    }

    @Test void respawnAndWorldWordingReflectCurrentState() {
        player.setRespawnLocation(new Location(world, 30, 65, 30), true);
        player.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0));
        command("me");
        String text = output();
        assertTrue(text.contains("not this plot"), text);
        assertTrue(text.contains("another world"));
    }

    @Test void consoleAndFailedStorageHaveShortReplies() throws Exception {
        new SpiralCommand(plugin).onCommand(server.getConsoleSender(), plugin.getCommand("sgen"),
                "sgen", new String[]{"me"});
        assertTrue(server.getConsoleSender().nextMessage().contains("in game"));
        Files.writeString(plugin.getDataFolder().toPath().resolve("data.yml"), "players: [broken");
        plugin.getDataStorage().load();
        command("me");
        String text = output();
        assertTrue(text.contains("temporarily unavailable"), text);
        assertFalse(text.contains("no recorded"));
    }
    @Test void firstAllocationNotifiesBeforeTeleportRetryAndDoesNotRepeat() {
        InlinePlayerMock newcomer = new InlinePlayerMock(server, "Newcomer");
        newcomer.teleportSucceeds = false;
        server.addPlayer(newcomer);
        plugin.inspection = claim(true, true);
        plugin.allocateNow(newcomer, "JAVA");
        List<String> first = new ArrayList<>();
        String line;
        while ((line = newcomer.nextMessage()) != null) { first.add(line); }
        assertEquals(1, first.stream().filter(message -> message.contains("Your spawn plot")).count());
        assertEquals(1, first.stream().filter(message -> message.contains("/trust")).count());
        newcomer.teleportSucceeds = true;
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerMoveEvent(newcomer,
                new Location(world, 0, 64, 0), new Location(world, 1, 64, 0)));
        assertNull(newcomer.nextMessage());
    }

    @Test void backfillNotifiesOnlyChangesAndOnlyOnlinePlayers() {
        plugin.inspection = claim(true, true);
        UUID offline = UUID.randomUUID();
        plugin.getDataStorage().setSpawn(offline, new Location(world, 30, 64, 30),
                2, 0, 0, "Offline", "JAVA");
        plugin.startProtectionBackfill(ignored -> { });
        String text = output();
        assertTrue(text.contains("/trust"), text);
        assertFalse(text.contains("Your spawn plot"));
        plugin.provider = new RecordingProvider().answering(ClaimOutcome.ALREADY_CLAIMED);
        plugin.startProtectionBackfill(ignored -> { });
        assertEquals("", output());
    }

    @Test void adminInfoUsesTargetsAccessAndFloorsCoordinates() {
        plugin.inspection = claim(true, false);
        new SpiralCommand(plugin).onCommand(server.getConsoleSender(), plugin.getCommand("sgen"),
                "sgen", new String[]{"info", player.getName()});
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = server.getConsoleSender().nextMessage()) != null) { lines.add(line); }
        String text = String.join("\n", lines);
        assertTrue(text.contains("-1, 64, -13"), text);
        assertTrue(text.contains("Builder has build access"), text);
        assertFalse(text.contains("/trust"));
    }

    @Test void unavailableProtectionDoesNotAddAutomaticClaimText() {
        plugin.inspection = ClaimInspection.unavailable();
        plugin.plotInformation().notify(player,
                plugin.getDataStorage().getRecord(player.getUniqueId()).toLocation(), true,
                new ClaimResult(ClaimOutcome.CREATED, "", ClaimChange.CREATED_TRUSTED));
        String text = output();
        assertTrue(text.contains("Your spawn plot"));
        assertFalse(text.contains("claim"));
        assertFalse(text.contains("Protection information"));
    }

}
