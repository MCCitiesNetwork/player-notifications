package io.github.md5sha256.playernotifications.paper;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deserializes {@code settings.yml}-shaped YAML through Configurate, covering the key names the
 * plugin actually reads and the clamping the record's compact constructor applies.
 */
class PluginSettingsTest {

    private static PluginSettings load(String yaml) throws ConfigurateException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .build();
        ConfigurationNode root = loader.load();
        PluginSettings settings = root.get(PluginSettings.class);
        assertNotNull(settings);
        return settings;
    }

    @Test
    void readsJoinDeliveryKeys() throws Exception {
        PluginSettings settings = load("""
                prune-interval-seconds: 3600
                default-media:
                  - chat
                deliver-on-join: true
                join-delivery-delay-seconds: 5
                """);
        assertTrue(settings.deliverOnJoin());
        assertEquals(5L, settings.joinDeliveryDelaySeconds());
    }

    @Test
    void readsDisabledJoinDelivery() throws Exception {
        PluginSettings settings = load("""
                default-media:
                  - chat
                deliver-on-join: false
                join-delivery-delay-seconds: 3
                """);
        assertFalse(settings.deliverOnJoin());
    }

    @Test
    void preservesZeroDelay() throws Exception {
        PluginSettings settings = load("""
                default-media:
                  - chat
                deliver-on-join: true
                join-delivery-delay-seconds: 0
                """);
        assertEquals(0L, settings.joinDeliveryDelaySeconds());
    }

    @Test
    void clampsNegativeDelayToZero() throws Exception {
        PluginSettings settings = load("""
                default-media:
                  - chat
                deliver-on-join: true
                join-delivery-delay-seconds: -10
                """);
        assertEquals(0L, settings.joinDeliveryDelaySeconds());
    }

    @Test
    void nonPositivePruneIntervalFallsBackToAnHour() throws Exception {
        PluginSettings settings = load("""
                prune-interval-seconds: 0
                default-media:
                  - chat
                """);
        assertEquals(3600L, settings.pruneIntervalSeconds());
    }
}
