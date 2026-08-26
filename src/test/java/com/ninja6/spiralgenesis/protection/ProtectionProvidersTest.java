package com.ninja6.spiralgenesis.protection;

import be.seeseemelk.mockbukkit.MockBukkit;
import com.ninja6.spiralgenesis.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which provider a given configuration actually gets.
 *
 * <p>Every case a test can reach ends at the no-op, and that is the point rather than a
 * limitation: the selection's job is to make sure a server that cannot claim gets an object
 * that claims nothing, instead of a null, an exception, or a provider that fails once per
 * joining player. The one path that ends anywhere else needs a running GriefPrevention,
 * which a unit test cannot have.
 */
class ProtectionProvidersTest {

    private Plugin plugin;

    private static PluginConfig parse(String yaml) {
        return new PluginConfig(YamlConfiguration.loadConfiguration(new StringReader(yaml)));
    }

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("SpiralGenesisTest");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A default install gets the no-op, because protection is off by default")
    void testDisabledByDefault() {
        assertSame(NoOpProtectionProvider.INSTANCE, ProtectionProviders.create(plugin, parse("")));
    }

    @Test
    @DisplayName("Protection enabled with provider NONE still claims nothing")
    void testExplicitNone() {
        PluginConfig config = parse("protection:\n  enabled: true\n  provider: NONE\n");

        assertSame(NoOpProtectionProvider.INSTANCE, ProtectionProviders.create(plugin, config));
    }

    @Test
    @DisplayName("A misspelled provider falls back to NONE rather than to GriefPrevention")
    void testTypoDoesNotStartClaiming() {
        // The fallback lives in ProtectionProviderType, but this is where it has to hold:
        // a typo must not be read as consent to start creating claims through whatever
        // plugin happens to be installed.
        PluginConfig config = parse("protection:\n  enabled: true\n  provider: GreifPrevention\n");

        assertSame(NoOpProtectionProvider.INSTANCE, ProtectionProviders.create(plugin, config));
    }

    @Test
    @DisplayName("GriefPrevention configured but not installed gets the no-op, not a broken provider")
    void testConfiguredButNotInstalled() {
        // The commonest real misconfiguration: the block copied out of the example config
        // onto a server that never installed the plugin. It has to be inert.
        PluginConfig config = parse("protection:\n  enabled: true\n  provider: GRIEF_PREVENTION\n");

        ProtectionProvider provider = ProtectionProviders.create(plugin, config);

        assertNotNull(provider);
        assertSame(NoOpProtectionProvider.INSTANCE, provider);
    }

    @Test
    @DisplayName("Selection never returns null, whatever the configuration says")
    void testNeverNull() {
        assertNotNull(ProtectionProviders.create(plugin,
                parse("protection:\n  enabled: true\n  size: 3\n  claim-as: PLAYER_CLAIM\n")));
        assertNotNull(ProtectionProviders.create(plugin,
                parse("protection:\n  enabled: true\n  size: 9999\n  claim-as: nonsense\n")));
    }
}
