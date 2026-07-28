package io.github.md5sha256.playernotifications.discord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class DiscordSettingsTest {

    private static DiscordSettings settings(String format, String color, long timeout) {
        return new DiscordSettings("token", format, color, timeout, List.of("discordsrv"));
    }

    @Test
    void messageFormatParsesCaseInsensitively() {
        Assertions.assertEquals(Optional.of(DiscordMessageFormat.MARKDOWN),
                DiscordMessageFormat.parse("MarkDown"));
        Assertions.assertEquals(Optional.of(DiscordMessageFormat.PLAIN),
                DiscordMessageFormat.parse("  plain "));
    }

    @Test
    void anUnknownMessageFormatParsesToEmpty() {
        Assertions.assertEquals(Optional.empty(), DiscordMessageFormat.parse("carrier-pigeon"));
    }

    @Test
    void anUnknownMessageFormatFallsBackToEmbed() {
        Assertions.assertEquals(DiscordMessageFormat.EMBED,
                settings("carrier-pigeon", "#5865F2", 10L).resolvedMessageFormat());
    }

    @Test
    void aConfiguredMessageFormatIsUsed() {
        Assertions.assertEquals(DiscordMessageFormat.PLAIN,
                settings("plain", "#5865F2", 10L).resolvedMessageFormat());
    }

    @Test
    void embedColorParsesWithAndWithoutTheLeadingHash() {
        Assertions.assertEquals(0x5865F2, settings("embed", "#5865F2", 10L).resolvedEmbedColor());
        Assertions.assertEquals(0x5865F2, settings("embed", "5865F2", 10L).resolvedEmbedColor());
    }

    @Test
    void anUnparseableEmbedColorFallsBackToTheDiscordBlurple() {
        Assertions.assertEquals(DiscordSettings.DEFAULT_EMBED_COLOR,
                settings("embed", "not-a-colour", 10L).resolvedEmbedColor());
    }

    @Test
    void aNonPositiveDeliveryTimeoutFallsBackToTheDefault() {
        Assertions.assertEquals(DiscordSettings.DEFAULT_DELIVERY_TIMEOUT_SECONDS,
                settings("embed", "#5865F2", 0L).deliveryTimeoutSeconds());
        Assertions.assertEquals(DiscordSettings.DEFAULT_DELIVERY_TIMEOUT_SECONDS,
                settings("embed", "#5865F2", -5L).deliveryTimeoutSeconds());
    }

    @Test
    void aBlankTokenIsReportedAsUnconfigured() {
        Assertions.assertTrue(
                new DiscordSettings("   ", "embed", "#5865F2", 10L, List.of()).isTokenBlank());
        Assertions.assertFalse(settings("embed", "#5865F2", 10L).isTokenBlank());
    }
}
