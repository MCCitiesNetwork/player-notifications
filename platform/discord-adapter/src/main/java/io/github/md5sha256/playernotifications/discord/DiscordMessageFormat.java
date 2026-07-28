package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * How a {@link io.github.md5sha256.playernotifications.api.render.RenderableNotification} is turned
 * into a Discord message. Configured by {@code message-format} in {@code discord.yml}.
 */
public enum DiscordMessageFormat {

    /** An embed with a plain-text title and a markdown description. The default. */
    EMBED,

    /** A single message whose content is markdown: a bold title line, then the body. */
    MARKDOWN,

    /** A single message with no markdown at all, for servers that prefer unstyled notifications. */
    PLAIN;

    /**
     * Parses a configured format name, case-insensitively and ignoring surrounding whitespace.
     * Returns empty for an unknown name so the caller can warn and fall back rather than failing
     * module startup over a typo.
     */
    public static @NotNull Optional<DiscordMessageFormat> parse(@NotNull String name) {
        String trimmed = name.trim();
        for (DiscordMessageFormat format : values()) {
            if (format.name().equalsIgnoreCase(trimmed)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
