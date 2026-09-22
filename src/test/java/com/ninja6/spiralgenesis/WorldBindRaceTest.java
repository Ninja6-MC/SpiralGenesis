package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.UnimplementedOperationException;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reload's bind racing a held player's bind retry on another thread.
 *
 * <p>On Folia the reload runs on the thread that typed the command and the retry on the
 * region thread that saw the player act, so nothing orders them. The interleaving that
 * matters is fixed here rather than left to the scheduler: the retry has read the old
 * {@code origin.world} and resolved it to no world, and is paused before it stores that
 * result, while the reload runs. Whatever the plugin does to order the two, the reload's
 * bind must be the one that stands and the held player must be released by it.
 */
class WorldBindRaceTest {

    private static final long TIMEOUT_SECONDS = 10;

    /** Pauses the retry's world lookup, which sits between its config read and its write. */
    private static final class PausingServer extends ServerMock {
        volatile Thread pauseOn;
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public World getWorld(String name) {
            World world = super.getWorld(name);
            if (Thread.currentThread() == pauseOn) {
                paused.countDown();
                await(release);
            }
            return world;
        }
    }

    private PausingServer server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new PausingServer());
        server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        server.release.countDown();
        try {
            MockBukkit.unmock();
        } catch (UnimplementedOperationException e) {
            // As in WorldBindingTest: the storage flush task cannot be cancelled by the mock.
            MockBukkit.unmock();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("interleaving did not reach its next step");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (thread.isAlive()) {
            throw new IllegalStateException(thread.getName() + " did not finish");
        }
    }

    private void write(SpiralGenesisPlugin plugin, Consumer<YamlConfiguration> edit) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        edit.accept(yaml);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private PlayerMock joinPlayer(String name) {
        PlayerMock player = new SessionPlayerMock(server, name) {
            @Override
            public CompletableFuture<Boolean> teleportAsync(Location location,
                                                            PlayerTeleportEvent.TeleportCause cause,
                                                            TeleportFlag... flags) {
                return CompletableFuture.completedFuture(teleport(location, cause));
            }
        };
        server.addPlayer(player);
        return player;
    }

    private void move(PlayerMock player) {
        Location from = new Location(server.getWorld("world"), 0, 64, 0);
        Location to = new Location(server.getWorld("world"), 1, 64, 0);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, from, to));
    }

    @Test
    @DisplayName("a retry that read the old world cannot unbind the world a reload has bound")
    void staleRetryDoesNotClobberReloadBind() {
        WorldBindingTest.UnbindingPlugin plugin = MockBukkit.loadWith(
                WorldBindingTest.UnbindingPlugin.class, getClass().getResourceAsStream("/plugin.yml"));
        write(plugin, yaml -> {
            yaml.set("allocation.action-timeout-seconds", 0);
            yaml.set("origin.world", "survival");
        });
        plugin.reload();

        PlayerMock player = joinPlayer("Waiting");
        move(player);
        assertEquals(1, plugin.allocationCalls.get());
        assertFalse(plugin.getDataStorage().hasSpawn(player.getUniqueId()), "held, not allocated");

        // The retry a held player's action makes, on its own region thread. It reads
        // origin.world as "survival", finds no such world, and stops before storing that.
        Thread retry = new Thread(plugin::initSpawnManager, "region-retry");
        server.pauseOn = retry;
        retry.start();
        await(server.paused);

        // Lets the retry finish at the point this interleaving needs, whichever comes first:
        // the reload waiting on something the paused retry holds, or the reload having
        // bound (the handler below). Neither is a sleep, so the order is the same every run.
        Thread reloadThread = Thread.currentThread();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Thread releaser = new Thread(() -> {
            while (server.release.getCount() > 0) {
                ThreadInfo info = threads.getThreadInfo(reloadThread.getId());
                if (info != null && info.getLockOwnerId() == retry.getId()) {
                    server.release.countDown();
                    return;
                }
                Thread.onSpinWait();
            }
        }, "releaser");
        Handler afterBind = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (Thread.currentThread() == reloadThread
                        && record.getMessage().startsWith("SpawnManager bound")) {
                    // Only reachable with the retry still paused if nothing orders the two:
                    // its stale write then lands between this bind and the resume.
                    server.release.countDown();
                    join(retry);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(afterBind);
        releaser.start();

        // The operator fixes the name and reloads.
        write(plugin, yaml -> yaml.set("origin.world", "world"));
        plugin.reload();

        plugin.getLogger().removeHandler(afterBind);
        join(retry);
        join(releaser);

        assertNotNull(plugin.getSpawnManager(),
                "the reload's bind must stand; a retry that read the old world cleared it");
        assertTrue(plugin.getDataStorage().hasSpawn(player.getUniqueId()),
                "the reload must release the held player without them acting again");
        assertEquals(2, plugin.allocationCalls.get(), "the reload's resume allocates them once");
    }
}
