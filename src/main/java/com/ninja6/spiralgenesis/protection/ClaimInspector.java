package com.ninja6.spiralgenesis.protection;

import org.bukkit.Location;
import java.util.UUID;

/** Read-only inspection, independent of whether automatic claim creation is enabled. */
@FunctionalInterface
public interface ClaimInspector {
    ClaimInspector UNAVAILABLE = (spawn, player) -> ClaimInspection.unavailable();

    /** Call on the thread owning the spawn region. Failures return UNAVAILABLE. */
    ClaimInspection inspect(Location spawn, UUID player);
}
