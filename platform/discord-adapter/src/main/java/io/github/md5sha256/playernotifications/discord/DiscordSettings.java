package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;

/**
 * Discord adapter settings, deserialized from {@code discord.yml} via Configurate.
 *
 * <p>The timeout is a {@code long} of seconds rather than a {@link java.time.Duration} because
 * Configurate 4.2.0 ships no {@code Duration} serializer (see "Configuration" in {@code CLAUDE.md}).
 * Malformed values resolve to a documented default rather than failing module startup: an operator
 * mistyping a colour should not cost them the whole medium.
 *
 * @param botToken                this module's own bot token — it cannot share DiscordSRV's, whose
 *                                JDA is relocated and so not type-compatible with ours
 * @param messageFormat           {@code embed}, {@code markdown} or {@code plain}
 * @param embedColor              the embed accent colour used when a notification's title carries no
 *                                colour of its own, as {@code #RRGGBB}
 * @param deliveryTimeoutSeconds  how long a single DM send may block before it counts as unreachable
 * @param linkProviders           ordered {@link DiscordAccountProvider} keys forming the lookup chain
 */
@ConfigSerializable
public record DiscordSettings(
        @Setting("bot-token")
        @Required
        String botToken,

        @Setting("message-format")
        @Required
        String messageFormat,

        @Setting("embed-color")
        @Required
        String embedColor,

        @Setting("delivery-timeout-seconds")
        long deliveryTimeoutSeconds,

        @Setting("link-providers")
        @Required
        List<String> linkProviders
) {

    /** Discord's own "blurple", used when {@code embed-color} cannot be parsed. */
    public static final int DEFAULT_EMBED_COLOR = 0x5865F2;

    /** Used when {@code delivery-timeout-seconds} is absent or non-positive. */
    public static final long DEFAULT_DELIVERY_TIMEOUT_SECONDS = 15L;

    public DiscordSettings {
        if (deliveryTimeoutSeconds <= 0) {
            deliveryTimeoutSeconds = DEFAULT_DELIVERY_TIMEOUT_SECONDS;
        }
    }

    /** Whether the token is missing — the module refuses to start rather than register a dead sink. */
    public boolean isTokenBlank() {
        return this.botToken.isBlank();
    }

    /** The configured format, falling back to {@link DiscordMessageFormat#EMBED} on an unknown name. */
    public @NotNull DiscordMessageFormat resolvedMessageFormat() {
        return DiscordMessageFormat.parse(this.messageFormat).orElse(DiscordMessageFormat.EMBED);
    }

    /**
     * The configured embed colour as a packed RGB int, accepting {@code #RRGGBB} or {@code RRGGBB}
     * and falling back to {@link #DEFAULT_EMBED_COLOR} on anything else.
     */
    public int resolvedEmbedColor() {
        String value = this.embedColor.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            return Integer.parseInt(value, 16) & 0xFFFFFF;
        } catch (NumberFormatException exception) {
            return DEFAULT_EMBED_COLOR;
        }
    }
}
