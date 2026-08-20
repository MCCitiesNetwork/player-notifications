package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ephemeral messages the inbox commands post: a paged listing, and one entry's detail.
 *
 * <p>Every component id carries the surface it belongs to, so a click on a {@code /mail} listing can
 * only ever be answered by the mail view. Everything is truncated to Discord's own limits before it
 * reaches JDA's builders, which throw on overlong input rather than trimming — the rule
 * {@link io.github.md5sha256.playernotifications.discord.DiscordMessageFactory} already follows.
 */
public final class InboxMessageFactory {

    /** The reply a click on a message whose interaction token has expired gets. */
    public static final String EXPIRED_MESSAGE = "This message has expired; run the command again.";

    /** Discord rejects a select carrying more options than this. */
    static final int MAX_OPTIONS = 25;

    /** Discord's maximum select-option label length. */
    private static final int OPTION_LABEL_LIMIT = 100;

    private final int embedColor;

    public InboxMessageFactory(int embedColor) {
        this.embedColor = embedColor;
    }

    /** One page of the inbox: an embed listing every row, a row select, and Previous/Next. */
    public @NotNull MessageCreateData listing(@NotNull InboxView.Page page, @NotNull String surface) {
        EmbedBuilder embed = new EmbedBuilder().setColor(this.embedColor)
                .setTitle(truncate(page.title() + " — page " + page.page() + " of " + page.totalPages(),
                        MessageEmbed.TITLE_MAX_LENGTH));

        MessageCreateBuilder message = new MessageCreateBuilder();
        if (page.isEmpty()) {
            // No rows means no select and no row buttons, and Discord rejects an empty select outright —
            // the message would fail to send rather than render with nothing on it.
            embed.setDescription("Nothing here.");
            return message.addEmbeds(embed.build()).build();
        }

        StringBuilder description = new StringBuilder();
        List<SelectOption> options = new ArrayList<>();
        for (InboxView.Row row : page.rows()) {
            if (!description.isEmpty()) {
                description.append('\n');
            }
            // The unread marker is the bullet, not the text, so it survives a title that is already bold.
            description.append(row.unread() ? "🔵 " : "⚪ ")
                    .append("**").append(row.entry()).append(".** ")
                    .append(row.title());
            if (options.size() < MAX_OPTIONS) {
                options.add(SelectOption.of(
                        truncate(row.entry() + ". " + row.title(), OPTION_LABEL_LIMIT), row.notifKey()));
            }
        }
        embed.setDescription(truncate(description.toString(), MessageEmbed.DESCRIPTION_MAX_LENGTH));

        return message.addEmbeds(embed.build())
                .addComponents(
                        ActionRow.of(StringSelectMenu.create(ComponentIds.encode(surface, "open"))
                                .setPlaceholder("Open an entry")
                                .addOptions(options)
                                .build()),
                        ActionRow.of(
                                Button.secondary(ComponentIds.encode(surface, "prev",
                                                String.valueOf(Math.max(1, page.page() - 1))), "Previous")
                                        .withDisabled(page.page() <= 1),
                                Button.secondary(ComponentIds.encode(surface, "next",
                                                String.valueOf(Math.min(page.totalPages(), page.page() + 1))), "Next")
                                        .withDisabled(page.page() >= page.totalPages())))
                .build();
    }

    /**
     * One entry, with Dismiss, Mark as unread, and a way back to the listing.
     *
     * <p>Opening an entry marks it seen, so <em>Mark as unread</em> is how a player undoes that. It
     * returns to the listing exactly as Back does; it exists as its own button because Back leaving the
     * entry read is not something the word "back" says.
     */
    public @NotNull MessageCreateData detail(@NotNull InboxView.Row row, @NotNull String surface) {
        MessageEmbed embed = new EmbedBuilder()
                .setColor(this.embedColor)
                .setTitle(truncate(row.title(), MessageEmbed.TITLE_MAX_LENGTH))
                .setDescription(truncate(row.body(), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .build();

        return new MessageCreateBuilder()
                .addEmbeds(embed)
                .addComponents(ActionRow.of(
                        Button.danger(ComponentIds.encode(surface, "dismiss", row.notifKey()), "Dismiss"),
                        Button.secondary(ComponentIds.encode(surface, "unread", row.notifKey()),
                                "Mark as unread"),
                        Button.secondary(ComponentIds.encode(surface, "page", "1"), "Back")))
                .build();
    }

    /** Truncates to {@code limit}, the last character becoming an ellipsis so a cut is visible. */
    private static @NotNull String truncate(@NotNull String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
