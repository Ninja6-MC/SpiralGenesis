package com.ninja6.spiralgenesis.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.UUID;

/** Read-only inspection, independent of whether automatic claim creation is enabled. */
@FunctionalInterface
public interface ClaimInspector {
    ClaimInspector UNAVAILABLE = (spawn, player) -> ClaimInspection.unavailable();

    /** Call on the thread owning the spawn region. Failures return UNAVAILABLE. */
    ClaimInspection inspect(Location spawn, UUID player);

    /**
     * Draws the edges of the player's claim at the spawn column for that player only, once.
     * Does nothing when there is no claim of theirs there or the provider cannot draw it.
     * Call on the thread owning both the player and the spawn region; never throws.
     */
    default void showEdges(Location spawn, Player player) {
    }

    /** The provider's claim investigation tool as a readable item name, or null if unknown. */
    default String investigationTool() {
        return null;
    }
}
