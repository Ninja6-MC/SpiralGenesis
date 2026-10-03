package com.ninja6.botclient;

import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

/** Packet signatures for the 1.21.11 test client. Never shipped. */
final class BotWirePackets {
    private BotWirePackets() {
    }

    static Packet acceptTeleport(ClientboundPlayerPositionPacket pos) {
        return new ServerboundAcceptTeleportationPacket(pos.getId());
    }

    static Packet respawn() {
        return new ServerboundClientCommandPacket(ClientCommand.RESPAWN);
    }
}
