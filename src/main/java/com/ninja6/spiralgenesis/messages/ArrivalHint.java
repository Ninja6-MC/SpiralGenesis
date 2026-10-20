package com.ninja6.spiralgenesis.messages;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * When one arrival hint is shown, counted in refreshes about a second apart rather than by
 * a clock, so a lagging server stretches the hint instead of cutting it short.
 *
 * <p>Only a change of block X or Z is movement. Turning the head and moving up or down
 * within the same column are not, so a player looking around or jumping on the spot keeps
 * the hint.
 */
final class ArrivalHint {
    private final String world;
    private final int anchorX;
    private final int anchorZ;
    private final int lingerSeconds;
    private final int maxSeconds;
    private int shown;
    /** Refreshes left after the first move, or -1 while the player has not moved. */
    private int lingerLeft = -1;

    ArrivalHint(Location anchor, int lingerSeconds, int maxSeconds) {
        World anchorWorld = anchor.getWorld();
        this.world = anchorWorld == null ? null : anchorWorld.getName();
        this.anchorX = anchor.getBlockX();
        this.anchorZ = anchor.getBlockZ();
        this.lingerSeconds = Math.max(0, lingerSeconds);
        this.maxSeconds = Math.max(0, maxSeconds);
    }

    /**
     * One refresh, about a second after the previous one.
     *
     * @param at where the player is now
     * @return whether to show the hint now; once false it stays false
     */
    boolean refresh(Location at) {
        if (shown < 0 || shown >= maxSeconds || !sameWorld(at)) {
            shown = -1;
            return false;
        }
        if (lingerLeft < 0 && moved(at)) {
            lingerLeft = lingerSeconds;
        }
        if (lingerLeft == 0) {
            shown = -1;
            return false;
        }
        if (lingerLeft > 0) {
            lingerLeft--;
        }
        shown++;
        return true;
    }

    private boolean sameWorld(Location at) {
        return at != null && at.getWorld() != null && at.getWorld().getName().equals(world);
    }

    private boolean moved(Location at) {
        return at.getBlockX() != anchorX || at.getBlockZ() != anchorZ;
    }
}
