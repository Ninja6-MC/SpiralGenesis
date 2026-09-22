package com.ninja6.spiralgenesis;

/**
 * Why allocation cannot run right now, for a cause the plugin expects an operator to clear.
 *
 * <p>One state with several reasons rather than one state per reason, because every reason
 * is handled the same way: a player who needs a plot is held by the action gate, told
 * nothing they could act on, and allocated as soon as the reason clears. Only the words
 * differ, and those are what each constant carries.
 */
enum AllocationUnavailable {

    /**
     * {@code data.yml} could not be read, so there is no spiral counter to advance and no
     * record of who already has a plot. Checked ahead of the world: a player allocated once
     * a world binds would still be allocated against records nobody could read.
     */
    STORAGE_FAILED("data.yml could not be read (see the storage error above)",
            "once /sgen reload reads it successfully"),

    /** {@code origin.world} names no loaded world, so there is nowhere to allocate into. */
    WORLD_UNBOUND("no world is bound (see the origin.world error above)",
            "once origin.world names a loaded world");

    private final String cause;
    private final String until;

    AllocationUnavailable(String cause, String until) {
        this.cause = cause;
        this.until = until;
    }

    /** The line reported once when a player starts being held for this reason. */
    String holdMessage(String playerName) {
        return "Cannot allocate a spawn for " + playerName + ": " + cause
                + ". They are held and will be allocated " + until + ".";
    }
}
