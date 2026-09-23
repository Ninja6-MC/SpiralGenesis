package me.ryanhamshire.GriefPrevention;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.UUID;

/**
 * Builds real GriefPrevention {@link Claim} objects for tests.
 *
 * <p>In GriefPrevention's own package because the constructor that takes corners is
 * package-private. It does nothing but store its arguments and apply the trust lists (read
 * off the 16.18.2 jar), so no running GriefPrevention is needed.
 */
public final class TestClaims {

    private TestClaims() {
    }

    /**
     * A top-level claim from {@code (lesserX, lesserZ)} to {@code (greaterX, greaterZ)},
     * both corners included, owned by {@code owner}, or an administrative claim when
     * {@code owner} is {@code null}, with {@code builders} trusted to build on it.
     */
    public static Claim claim(World world, int lesserX, int lesserZ, int greaterX, int greaterZ,
                              UUID owner, List<String> builders, long id) {
        return new Claim(new Location(world, lesserX, 0, lesserZ),
                new Location(world, greaterX, 255, greaterZ),
                owner, builders, List.of(), List.of(), List.of(), id);
    }
}
