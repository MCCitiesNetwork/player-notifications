package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * How one entry is worded on a chat-fallback listing.
 *
 * <p>A per-screen choice, like {@code InboxRouter}'s title, command label and data-type filter: the
 * two routers differ in what a row should say, and threading that in as an argument keeps the listing
 * loop from having to know that {@code mail} is a special data type. The hover and click belong to the
 * command rather than to the format, so {@code InboxRouter} still attaches those itself.
 */
@FunctionalInterface
public interface InboxChatRow {

    /**
     * @param entry    the row's 1-based position on the page, which is what {@code read <entry>} takes
     * @param stored   the entry as stored, for anything the rendered form does not carry
     * @param rendered the entry rendered for this viewer
     * @param unread   whether this viewer has yet to read it
     */
    @NotNull Component format(int entry, @NotNull InboxEntry stored,
                              @NotNull RenderableNotification rendered, boolean unread);

    /** The default: {@code <entry>. <title>}, which is how every listing read before mail got its own. */
    static @NotNull InboxChatRow titleOnly(@NotNull MessageContainer messages) {
        return (entry, stored, rendered, unread) -> messages.messageFor(
                // Separate keys rather than one plus colorIfAbsent: colouring a single key would
                // otherwise erase the unread/read distinction, since colorIfAbsent is a no-op once
                // the text carries a colour of its own.
                unread ? MessageKeys.INBOX_ROW_TITLE_ONLY_UNREAD : MessageKeys.INBOX_ROW_TITLE_ONLY_READ,
                MessageContainer.value("entry", String.valueOf(entry)),
                MessageContainer.markup("title", rendered.title()));
    }
}
