package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * Turns a medium-neutral {@link RenderableNotification} into a Discord message.
 *
 * <p>The body is rendered through {@link DiscordMarkdownSerializer} for the embed and markdown
 * formats, and flattened to plain text for {@link DiscordMessageFormat#PLAIN}. Titles are always
 * plain text: Discord does not render markdown in embed titles.
 *
 * <p>Everything is truncated to Discord's own limits before it reaches JDA's builders, which throw
 * on overlong input rather than trimming.
 */
public final class DiscordMessageFactory {

    /** Discord's maximum embed title length. */
    static final int TITLE_LIMIT = 256;
    /** Discord's maximum embed description length. */
    static final int DESCRIPTION_LIMIT = 4096;
    /** Discord's maximum message content length. */
    static final int CONTENT_LIMIT = 2000;

    /**
     * Stand-in for an empty rendering. Discord rejects a message with neither content nor embed, and
     * a zero-width space satisfies that without displaying anything.
     */
    private static final String EMPTY_PLACEHOLDER = "​";

    private final DiscordMessageFormat format;
    private final int fallbackEmbedColor;

    public DiscordMessageFactory(@NotNull DiscordMessageFormat format, int fallbackEmbedColor) {
        this.format = format;
        this.fallbackEmbedColor = fallbackEmbedColor;
    }

    public @NotNull MessageCreateData create(@NotNull RenderableNotification notification) {
        return switch (this.format) {
            case EMBED -> createEmbed(notification);
            case MARKDOWN -> MessageCreateData.fromContent(createMarkdownContent(notification));
            case PLAIN -> MessageCreateData.fromContent(createPlainContent(notification));
        };
    }

    private @NotNull MessageCreateData createEmbed(@NotNull RenderableNotification notification) {
        String title = truncate(plainText(notification.title()), TITLE_LIMIT);
        String description = truncate(
                DiscordMarkdownSerializer.serialize(notification.body()), DESCRIPTION_LIMIT);

        EmbedBuilder builder = new EmbedBuilder()
                .setDescription(description.isEmpty() ? EMPTY_PLACEHOLDER : description)
                .setColor(embedColor(notification.title()));
        if (!title.isEmpty()) {
            builder.setTitle(title);
        }
        return MessageCreateData.fromEmbeds(builder.build());
    }

    private @NotNull String createMarkdownContent(@NotNull RenderableNotification notification) {
        // Serializing the title with BOLD forced on reuses the serializer's escaping and cannot
        // double up the markers when the title is already bold.
        String title = DiscordMarkdownSerializer.serialize(
                notification.title().decoration(TextDecoration.BOLD, TextDecoration.State.TRUE));
        return joinAndLimit(title, DiscordMarkdownSerializer.serialize(notification.body()));
    }

    private @NotNull String createPlainContent(@NotNull RenderableNotification notification) {
        return joinAndLimit(plainText(notification.title()), plainText(notification.body()));
    }

    private static @NotNull String joinAndLimit(@NotNull String title, @NotNull String body) {
        String joined;
        if (title.isEmpty()) {
            joined = body;
        } else if (body.isEmpty()) {
            joined = title;
        } else {
            joined = title + "\n" + body;
        }
        joined = truncate(joined, CONTENT_LIMIT);
        return joined.isEmpty() ? EMPTY_PLACEHOLDER : joined;
    }

    private int embedColor(@NotNull Component title) {
        TextColor color = title.color();
        return color == null ? this.fallbackEmbedColor : color.value();
    }

    private static @NotNull String plainText(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Truncates to {@code limit} characters, the last of which becomes an ellipsis so a cut is
     * visible rather than silent.
     */
    private static @NotNull String truncate(@NotNull String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
