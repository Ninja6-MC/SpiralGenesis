package com.ninja6.spiralgenesis.config;

import java.util.Locale;

/**
 * Which land protection plugin the spawn claim is created through.
 *
 * <p>One name per shipped implementation. The seam is narrow enough that adding another
 * plugin is a constant here and a class in {@code protection/}, which is the whole reason
 * the abstraction stops where it does.
 */
public enum ProtectionProviderType {

    /**
     * Claim nothing. The value a typo falls back to, and the effective state of any server
     * with {@code protection.enabled: false}.
     */
    NONE,

    /**
     * GriefPrevention. Absent on Folia, which it does not run on at all, so a Folia server
     * configured for it gets the no-op provider and an unchanged plugin.
     */
    GRIEF_PREVENTION;

    /**
     * Parses a configured name, falling back to {@code fallback} for anything unrecognised
     * rather than failing plugin startup over a typo.
     *
     * <p>Callers pass {@link #NONE} as the fallback deliberately. Every other enum in this
     * package falls back to its own default, but the default here creates claims, and a
     * misspelled provider name is not evidence that the server owner wanted claims created
     * through some other plugin.
     */
    public static ProtectionProviderType parse(String name, ProtectionProviderType fallback) {
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
