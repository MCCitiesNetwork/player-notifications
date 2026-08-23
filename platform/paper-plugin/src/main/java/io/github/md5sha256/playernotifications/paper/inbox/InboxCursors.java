package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.paper.ui.PageBounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player last listed, held <b>separately per surface</b>: the dialog and the chat fallback
 * each index their own listing and neither can move the other's.
 *
 * <p>Before the filtered inbox both surfaces wrote one shared pair of maps, so paging a dialog changed
 * what {@code /notifications read 1} resolved against. Once the dialog gained a category filter that
 * sharing became untenable — a filtered dialog page would silently redefine an unfiltered command — so
 * the state split rather than the filter leaking. The visible consequence is that a player who has only
 * used the dialog and then types {@code read 1} is told to list first; see the design doc's
 * "Known limitations".
 *
 * <p>Holds no Bukkit type, so the split is unit-testable without a live server.
 *
 * <p>There is deliberately no chat page cursor: {@code /notifications list [page]} carries its page
 * explicitly, which is why only the dialog needs to remember one.
 */
final class InboxCursors {

    /** The dialog's page, so *Mark all read* and *Delete all read* can reopen where the player was. */
    private final Map<UUID, PageBounds> dialogCursor = new ConcurrentHashMap<>();
    /** The dialog's listed entries, so a clicked row's key resolves without a re-read. */
    private final Map<UUID, List<InboxEntry>> dialogListed = new ConcurrentHashMap<>();
    /** The chat listing's entries, which {@code read <entry>} / {@code delete <entry>} index into. */
    private final Map<UUID, List<InboxEntry>> chatListed = new ConcurrentHashMap<>();

    void recordDialog(@NotNull UUID playerId, @NotNull PageBounds bounds, @NotNull List<InboxEntry> entries) {
        this.dialogCursor.put(playerId, bounds);
        this.dialogListed.put(playerId, entries);
    }

    void recordChat(@NotNull UUID playerId, @NotNull List<InboxEntry> entries) {
        this.chatListed.put(playerId, entries);
    }

    /** The dialog page this player was last on, or {@code 1} if they have not opened one. */
    int dialogPage(@NotNull UUID playerId) {
        PageBounds bounds = this.dialogCursor.get(playerId);
        return bounds == null ? 1 : bounds.page();
    }

    /** The entry with that key on the player's last dialog page, or {@code null} if it is not there. */
    @Nullable
    InboxEntry dialogEntry(@NotNull UUID playerId, @NotNull String notificationKey) {
        List<InboxEntry> listed = this.dialogListed.get(playerId);
        if (listed == null) {
            return null;
        }
        for (InboxEntry entry : listed) {
            if (entry.notifKey().equals(notificationKey)) {
                return entry;
            }
        }
        return null;
    }

    /** The player's last chat listing, or {@code null} if they have not listed in chat. */
    @Nullable
    List<InboxEntry> chatListed(@NotNull UUID playerId) {
        return this.chatListed.get(playerId);
    }

    /** Drops the chat index only — what {@code /notifications clear} needs, so a stale index cannot resolve. */
    void clearChat(@NotNull UUID playerId) {
        this.chatListed.remove(playerId);
    }

    /** Drops every surface's state for one player, on quit. */
    void drop(@NotNull UUID playerId) {
        this.dialogCursor.remove(playerId);
        this.dialogListed.remove(playerId);
        this.chatListed.remove(playerId);
    }
}
