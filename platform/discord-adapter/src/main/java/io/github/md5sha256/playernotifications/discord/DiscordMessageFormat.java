package io.github.md5sha256.playernotifications.discord;

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
    PLAIN
}
