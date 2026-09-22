package com.ninja6.spiralgenesis;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;

import java.util.UUID;

/**
 * A player mock whose connection belongs to the entity, as it does on the server.
 *
 * <p>MockBukkit throws from {@code isConnected()}. The server answers it per entity: it is
 * the negation of the handle's disconnected flag, which is set before the quit event fires
 * (checked in the CraftPlayer bytecode of paper 1.20.4 and 1.21.11). {@code isOnline()} is
 * left as MockBukkit has it, a lookup by UUID, which is also what the server does - so an
 * old entity reads online again once a new one with the same UUID has joined, and a test
 * built on this double sees that.
 */
public class SessionPlayerMock extends PlayerMock {

    private volatile boolean connected = true;

    public SessionPlayerMock(ServerMock server, String name) {
        super(server, name);
    }

    public SessionPlayerMock(ServerMock server, String name, UUID uuid) {
        super(server, name, uuid);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /** Disconnects the way the server does: the entity first, then the quit event. */
    @Override
    public boolean disconnect() {
        connected = false;
        return super.disconnect();
    }

    /**
     * Drops the connection without the quit event reaching anything, which is what a
     * listener sees of a quit it lost the race to.
     */
    public void dropConnection() {
        connected = false;
    }
}
