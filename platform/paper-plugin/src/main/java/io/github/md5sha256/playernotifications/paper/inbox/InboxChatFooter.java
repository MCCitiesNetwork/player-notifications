package io.github.md5sha256.playernotifications.paper.inbox;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The chat listing's pager: {@code « Page 2 of 3 »}, each arrow running the command for that page.
 *
 * <p>Its own class rather than a private method on {@link InboxRouter} for the reason
 * {@link InboxFilters} and {@code MailChatRow} are: it holds a decision — when an arrow is live and
 * what command it runs — and names no Bukkit type, so the decision is unit testable without a server.
 *
 * <p><strong>The click is attached here, not in {@code messages.yml}.</strong> A {@code <click>} tag
 * <em>argument</em> is a position no {@code TagResolver} can fill, and reaching it from the file would
 * need the {@code deserializeRaw} subclass of {@link MessageContainer} this plugin deliberately does
 * not have. So the two arrow keys hold arrow text only.
 */
public final class InboxChatFooter {

    private InboxChatFooter() {
    }

    /**
     * @param commandLabel {@code notifications} or {@code mail} — the two routers share this class, so
     *                     hardcoding either would page the other inbox
     * @param page         the page being shown, 1-based and already clamped by {@code InboxPage}
     * @param totalPages   how many there are, at least 1
     * @return the footer, or {@code null} when there is only one page — a pager offering no
     *         destination is noise, so the listing ends at its usage hint instead
     */
    public static @Nullable Component build(@NotNull MessageContainer messages,
                                            @NotNull String commandLabel,
                                            int page, int totalPages) {
        if (totalPages <= 1) {
            return null;
        }
        return messages.messageFor(MessageKeys.INBOX_FOOTER,
                MessageContainer.markup("previous", arrow(messages, commandLabel, page - 1, page > 1,
                        MessageKeys.INBOX_FOOTER_PREVIOUS, MessageKeys.INBOX_FOOTER_PREVIOUS_INERT)),
                MessageContainer.markup("next", arrow(messages, commandLabel, page + 1, page < totalPages,
                        MessageKeys.INBOX_FOOTER_NEXT, MessageKeys.INBOX_FOOTER_NEXT_INERT)),
                MessageContainer.value("page", String.valueOf(page)),
                MessageContainer.value("total-pages", String.valueOf(totalPages)));
    }

    /**
     * One arrow. An arrow at the end of its range is rendered <em>inert</em> — dimmed and unclickable —
     * rather than omitted: dropping it changes the footer's width from page to page, which reads as the
     * pager jumping about. The cost is a click that does nothing, which is the quieter failure.
     *
     * <p>The two states are <strong>two keys</strong>, not one key recoloured here. MiniMessage renders
     * {@code <yellow>«</yellow>} as a parent carrying a coloured child, so {@code Component#color} on
     * what {@code messageFor} returns is a no-op and the inert arrow kept rendering yellow —
     * indistinguishable from the live one. Keys also leave the look where the rest of it lives: an
     * operator can restyle either state, or blank the inert one to hide it.
     */
    private static @NotNull Component arrow(@NotNull MessageContainer messages,
                                            @NotNull String commandLabel, int target, boolean live,
                                            @NotNull String liveKey, @NotNull String inertKey) {
        return live
                ? messages.messageFor(liveKey)
                        .clickEvent(ClickEvent.runCommand("/" + commandLabel + " list " + target))
                : messages.messageFor(inertKey);
    }
}
