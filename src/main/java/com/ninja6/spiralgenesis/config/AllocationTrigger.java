package com.ninja6.spiralgenesis.config;

import java.util.Locale;

/**
 * When a Java player's plot is allocated relative to their connecting.
 *
 * <p>Bedrock players are unaffected: Floodgate has already authenticated them by the time
 * they join, so they are allocated on join under every trigger.
 */
public enum AllocationTrigger {

    /**
     * Allocate as soon as the player joins.
     *
     * <p>Correct on an online-mode server, and on a network where authentication happens
     * on the proxy or on a separate backend, because in both cases the player arriving
     * here is already authenticated. Wrong on a server running a login plugin locally: it
     * allocates while the player is still held in limbo, which burns a spiral index for a
     * connection that has proven nothing and races the login plugin's own position
     * restore.
     */
    ON_JOIN,

    /**
     * Allocate on the player's first action that no other plugin cancelled.
     *
     * <p>Login plugins suppress what an unauthenticated player does, which is the one
     * behaviour they all share where their APIs share nothing. They do it two ways, and
     * both have to be read. Most actions are simply cancelled. Movement often is not:
     * AuthMe's move handler runs at {@code HIGHEST} and pins the player with
     * {@code event.setTo(event.getFrom())} without ever setting the cancel flag, so
     * {@code ignoreCancelled} alone would let a held player through. Comparing the
     * destination the gate is finally handed against the origin catches that case.
     *
     * <p>Keying off suppression rather than an API gates correctly behind AuthMe, nLogin,
     * LibreLogin and anything else without naming or compiling against any of them. Where
     * no login plugin is installed nothing is suppressed and the player's first step
     * allocates immediately.
     */
    FIRST_ACTION;

    /**
     * Parses a configured name, falling back to {@code fallback} for anything unrecognised
     * rather than failing plugin startup over a typo.
     */
    public static AllocationTrigger parse(String name, AllocationTrigger fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
