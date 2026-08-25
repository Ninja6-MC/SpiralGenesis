package com.ninja6.botclient;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A real Minecraft client that joins, walks, and reports what happened. Never shipped.
 *
 * <p>Exists because the allocation gate decides when to place a player, and until now
 * nothing had ever driven that decision with an actual player. The unit tests reproduce a
 * limbo faithfully but are written against our own reading of one; the CI fixture proves
 * two plugins register in the right order but never connects anybody. Both are arguments
 * about a player rather than a player.
 *
 * <p>This connects over the real protocol in offline mode - no account, no session server -
 * so the server creates a genuine {@code Player}, fires {@code PlayerJoinEvent}, and runs
 * every listener the plugin registered. What SpiralGenesis then writes to the server log is
 * the assertion; this process only produces the input and says plainly what it did.
 *
 * <p>Walks on a timer rather than waiting to be told. The gate is supposed to open on the
 * first movement that survives whatever is suppressing it, so the honest test is to keep
 * moving throughout and let the server decide when that becomes true - the caller flips the
 * limbo mid-run and watches for the log line to appear.
 */
public final class GateProbeBot {

    /** Blocks travelled per step. One block is enough: the gate tests block boundaries. */
    private static final double STEP = 1.0;

    private GateProbeBot() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: GateProbeBot <host> <port> <username> <seconds>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String username = args[2];
        long seconds = Long.parseLong(args[3]);

        AtomicReference<double[]> position = new AtomicReference<>();
        AtomicBoolean inGame = new AtomicBoolean();
        CountDownLatch spawned = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        MinecraftProtocol protocol = new MinecraftProtocol(username);
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setAddress(host, port)
                .setProtocol(protocol)
                .create();

        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    inGame.set(true);
                    report("in-game");
                } else if (packet instanceof ClientboundPlayerPositionPacket pos) {
                    // Acknowledge before anything else. Until the teleport id is confirmed
                    // the server discards our movement packets outright, so without this the
                    // bot appears to walk and the server never fires PlayerMoveEvent - which
                    // looks exactly like a gate that refuses to open.
                    s.send(new ServerboundAcceptTeleportationPacket(pos.getId()));

                    // The server's authoritative position. It arrives on join and again
                    // every time the server moves us - which is what SpiralGenesis does when
                    // it allocates, and what a limbo does when it pins us back.
                    position.set(new double[]{
                            pos.getPosition().getX(), pos.getPosition().getY(), pos.getPosition().getZ()});
                    report(String.format(Locale.ROOT, "position x=%.1f y=%.1f z=%.1f",
                            pos.getPosition().getX(), pos.getPosition().getY(), pos.getPosition().getZ()));
                    spawned.countDown();
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                report("disconnected reason=" + plain(event));
                finished.countDown();
            }
        });

        report("connecting to " + host + ":" + port + " as " + username);
        session.connect();

        if (!spawned.await(60, TimeUnit.SECONDS)) {
            report("FAILED never received a spawn position");
            session.disconnect("giving up");
            System.exit(1);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        int steps = 0;
        while (System.nanoTime() < deadline && finished.getCount() > 0 && inGame.get()) {
            double[] at = position.get();
            if (at != null) {
                // Horizontal only. The gate ignores descent, because at least one limbo
                // implementation declines to pin a falling player, so a vertical step would
                // prove nothing either way.
                double x = at[0] + (steps % 2 == 0 ? STEP : -STEP);
                session.send(new ServerboundMovePlayerPosPacket(true, false, x, at[1], at[2]));
                // Optimistic local update. If the server disagrees - a limbo pinning us, or
                // an allocation teleporting us - it says so with a position packet, which the
                // listener above writes back over this.
                position.set(new double[]{x, at[1], at[2]});
                steps++;
            }
            Thread.sleep(1000);
        }

        report("walked steps=" + steps);
        double[] end = position.get();
        if (end != null) {
            report(String.format(Locale.ROOT, "final x=%.1f y=%.1f z=%.1f", end[0], end[1], end[2]));
        }
        session.disconnect("done");
        finished.await(10, TimeUnit.SECONDS);
        report("exiting");
        // Netty keeps non-daemon threads alive; nothing here needs a graceful pool shutdown.
        System.exit(0);
    }

    private static String plain(DisconnectedEvent event) {
        try {
            return String.valueOf(event.getReason());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** One line per event, prefixed so the smoke script can grep it out of the job log. */
    private static void report(String message) {
        System.out.println("BOT " + message);
        System.out.flush();
    }
}
