package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.ninja6.spiralgenesis.config.ClaimOwnership;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GriefPrevention provider's decisions, taken apart from GriefPrevention itself.
 *
 * <p>What is worth testing here is the arithmetic the API deliberately leaves to its
 * caller - GriefPrevention's own {@code createClaim} states that it checks neither the
 * minimum claim size nor whether the owner can afford the claim - plus the guard ordering
 * that decides which of those questions is even asked. None of that needs a running
 * GriefPrevention, and there is no way to have one: its classes are on the test classpath
 * so this suite can load the provider at all, but there is no server for the plugin itself
 * to enable against, so its singleton stays null.
 *
 * <p>What is left uncovered, and why: the {@code createClaim} call itself, the overlap and
 * refusal branches around its result, and the wrong-thread guard all require an available
 * provider, which requires GriefPrevention to have enabled. Those are exercised on a real
 * server, and the guard ordering below is what makes their absence safe - every one of them
 * sits behind {@code available}, and {@code available} is false everywhere a test can
 * reach.
 */
class GriefPreventionProtectionProviderTest {

    private ServerMock server;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("SpiralGenesisTest");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // -- Minimum claim size ----------------------------------------------------------

    @Test
    @DisplayName("A default 9x9 player claim is below GriefPrevention's default minimum area")
    void testDefaultSizeMissesDefaultMinimumArea() {
        // GriefPrevention ships MinimumWidth 5 and MinimumArea 100. A 9x9 square is 81
        // blocks, so the default configuration on both sides is already a mismatch - which
        // is exactly why BELOW_MINIMUM_SIZE exists and why it is detected once at startup.
        assertTrue(GriefPreventionProtectionProvider.belowMinimum(9, ClaimOwnership.PLAYER_CLAIM, 5, 100));
    }

    @Test
    @DisplayName("Width and area are separate gates and either one alone is enough to fail")
    void testEitherMinimumAloneFails() {
        assertTrue(GriefPreventionProtectionProvider.belowMinimum(9, ClaimOwnership.PLAYER_CLAIM, 16, 1),
                "a square wider than the area minimum can still be narrower than the width minimum");
        assertTrue(GriefPreventionProtectionProvider.belowMinimum(9, ClaimOwnership.PLAYER_CLAIM, 1, 100),
                "a square clearing the width minimum can still be under the area minimum");
        assertFalse(GriefPreventionProtectionProvider.belowMinimum(11, ClaimOwnership.PLAYER_CLAIM, 5, 100),
                "11x11 is 121 blocks, which clears both defaults");
    }

    @Test
    @DisplayName("An admin claim is exempt from both minimums, because GriefPrevention exempts it")
    void testAdminClaimIgnoresMinimums() {
        // GriefPrevention's own fields are documented "minimum width for non-admin claims"
        // and its shovel handler guards the check with shovelMode != Admin. Applying the
        // minimum to an admin claim here would refuse claims the server would have made.
        assertFalse(GriefPreventionProtectionProvider.belowMinimum(3, ClaimOwnership.ADMIN_CLAIM, 16, 400));
    }

    @Test
    @DisplayName("A huge square does not overflow its way past the area minimum")
    void testAreaDoesNotOverflow() {
        // 255 is the configured ceiling on protection.size today, but the arithmetic must
        // not be the reason that stays true. int multiplication of a side near 65536 wraps
        // negative, which would read as "below the minimum" and refuse every claim.
        assertFalse(GriefPreventionProtectionProvider.belowMinimum(255, ClaimOwnership.PLAYER_CLAIM, 5, 100));
        assertFalse(GriefPreventionProtectionProvider.belowMinimum(70000, ClaimOwnership.PLAYER_CLAIM, 5, 100));
    }

    @Test
    @DisplayName("The startup warning names both numbers, which is the only reason to log it")
    void testMinimumDetailNamesBothNumbers() {
        String detail = GriefPreventionProtectionProvider.minimumDetail(9, 5, 100);

        assertTrue(detail.contains("100"), "GriefPrevention's minimum has to appear: " + detail);
        assertTrue(detail.contains("81"), "the configured square's area has to appear: " + detail);
        assertFalse(detail.contains("MinimumWidth"),
                "9 clears a width of 5, so the width half must stay out of it: " + detail);

        String narrow = GriefPreventionProtectionProvider.minimumDetail(9, 16, 1);
        assertTrue(narrow.contains("16") && narrow.contains("9"),
                "the width half has to name both numbers too: " + narrow);
    }

    @Test
    @DisplayName("An unaffordable claim is explained with both balances and a way out")
    void testInsufficientBlocksDetail() {
        String detail = GriefPreventionProtectionProvider.insufficientBlocksDetail(0, 81);

        assertTrue(detail.contains("0"), detail);
        assertTrue(detail.contains("81"), detail);
        assertTrue(detail.contains("ADMIN_CLAIM"),
                "a zero starting balance is the common case, so name the setting that fixes it");
    }

    // -- What the owner is granted on an administrative claim -------------------------

    @Test
    @DisplayName("Build does not imply Manage, which is the whole reason two grants are made")
    void testBuildDoesNotGrantManage() {
        // Asserted against the GriefPrevention on the classpath rather than taken on trust,
        // because the fix depends on it and the enum's declaration order reads the other
        // way: Manage is declared last, which looks like the top of an Edit > Build >
        // Inventory > Access > Manage chain and is not one. isGrantedBy takes Manage out of
        // the chain and answers it separately. If a future GriefPrevention folds Manage into
        // the hierarchy, this fails and the second grant below becomes redundant rather than
        // silently wrong.
        assertFalse(ClaimPermission.Manage.isGrantedBy(ClaimPermission.Build),
                "Build must not confer Manage, or the bug this guards could not have existed");
        assertFalse(ClaimPermission.Build.isGrantedBy(ClaimPermission.Manage),
                "Manage must not confer Build either, which is why Build is still granted");
        assertTrue(ClaimPermission.Manage.isGrantedBy(ClaimPermission.Edit),
                "Edit is the only level above Manage, and it is the one that cannot be granted");
    }

    @Test
    @DisplayName("A fresh admin claim's owner is granted Build and Manage, in that order")
    void testFreshClaimGrantsBuildAndManage() {
        // The permission set is what decides whether a player can invite somebody onto their
        // own spawn plot, so it is asserted as a list and not as a count. Manage is the
        // level /trust checks - Claim.allowGrantPermission is checkPermission(player, Manage,
        // null) - and Build is the ground, the bed and the first chest.
        List<ClaimPermission> granted =
                GriefPreventionProtectionProvider.missingOwnerPermissions(level -> false);

        assertEquals(List.of(ClaimPermission.Build, ClaimPermission.Manage), granted);
    }

    @Test
    @DisplayName("A claim from before the fix is topped up with Manage and not re-granted Build")
    void testLegacyClaimGainsOnlyTheMissingGrant() {
        // The upgrade path. Every admin claim this plugin created before Manage was granted
        // carries Build alone, and /sgen protect walking over one must add the level it
        // lacks rather than a second copy of the level it has: Claim.setPermission appends
        // Manage to a plain ArrayList of managers without checking, so an unguarded repeat
        // grows that list every pass.
        List<ClaimPermission> granted = GriefPreventionProtectionProvider
                .missingOwnerPermissions(level -> level == ClaimPermission.Build);

        assertEquals(List.of(ClaimPermission.Manage), granted);
    }

    @Test
    @DisplayName("A claim already carrying both grants is left entirely alone")
    void testFullyTrustedClaimIsNotWrittenTo() {
        // Empty is what suppresses the saveClaim as well as the setPermission calls, so this
        // is also the assertion that a repeated backfill on a healthy server writes nothing.
        assertTrue(GriefPreventionProtectionProvider.missingOwnerPermissions(level -> true)
                .isEmpty());
    }

    // -- Behaviour with GriefPrevention absent ---------------------------------------

    @Test
    @DisplayName("Without GriefPrevention the provider resolves unavailable instead of throwing")
    void testUnavailableWithoutGriefPrevention() {
        // Construction is where the third-party plugin is resolved. GriefPrevention is on
        // the test classpath, so its classes load and GriefPrevention.instance is simply
        // null - the unresolved-plugin branch, not the catch-Throwable one, which nothing
        // in this suite can reach because reaching it needs a jar that has moved.
        GriefPreventionProtectionProvider provider =
                new GriefPreventionProtectionProvider(plugin, ClaimOwnership.ADMIN_CLAIM, 9);

        assertFalse(provider.isAvailable());
        assertEquals("GRIEF_PREVENTION", provider.name());
    }

    @Test
    @DisplayName("An unavailable provider answers PROVIDER_UNAVAILABLE and never null")
    void testReserveWhenUnavailable() {
        GriefPreventionProtectionProvider provider =
                new GriefPreventionProtectionProvider(plugin, ClaimOwnership.ADMIN_CLAIM, 9);

        ClaimResult result = provider.reserve(new Location(null, 0, 64, 0), 9, UUID.randomUUID());

        assertNotNull(result);
        assertEquals(ClaimOutcome.PROVIDER_UNAVAILABLE, result.outcome());
    }

    @Test
    @DisplayName("An unavailable provider tolerates a null request rather than throwing")
    void testReserveToleratesNullsWhenUnavailable() {
        // The availability guard has to come first. A caller holding a provider that cannot
        // claim must not be able to provoke an exception out of it with a bad argument.
        GriefPreventionProtectionProvider provider =
                new GriefPreventionProtectionProvider(plugin, ClaimOwnership.PLAYER_CLAIM, 9);

        assertEquals(ClaimOutcome.PROVIDER_UNAVAILABLE, provider.reserve(null, 0, null).outcome());
    }

    // -- The softdepend name ---------------------------------------------------------

    @Test
    @DisplayName("plugin.yml soft-depends on GriefPrevention under its own exact name")
    void testSoftdependNameMatchesExactly() {
        // The trap this guards is silent: a softdepend entry that does not match the other
        // plugin's plugin.yml `name:` character for character buys no load-order guarantee
        // at all, and the symptom is an intermittently unresolved provider rather than an
        // error. "GriefPrevention" was read out of the shipped jar.
        File descriptor = new File("src/main/resources/plugin.yml");
        assertTrue(descriptor.isFile(), "plugin.yml should be where this test expects it");

        List<String> softdepend = YamlConfiguration.loadConfiguration(descriptor)
                .getStringList("softdepend");

        assertTrue(softdepend.contains(GriefPreventionProtectionProvider.PLUGIN_NAME),
                "softdepend must carry the exact name the provider looks the plugin up by, "
                        + "and it reads " + softdepend);
    }

    @Test
    @DisplayName("The server mock is not Folia, so the Folia branch is genuinely a branch")
    void testFoliaDetectionIsFalseOffFolia() {
        // If Folia's marker class were reachable from the test classpath, every test that
        // expects a real provider would silently take the Folia path instead.
        assertFalse(ProtectionProviders.isFolia());
        assertNotNull(server, "the mock server is what stands in for a Paper server here");
    }
}
