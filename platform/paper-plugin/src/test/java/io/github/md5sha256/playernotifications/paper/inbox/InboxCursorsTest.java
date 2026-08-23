package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.paper.ui.PageBounds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The per-surface cursor split. The point of these cases is that the dialog and the chat fallback
 * cannot move each other's index: before the filtered inbox, both wrote one shared pair of maps, so
 * paging a dialog changed what {@code /notifications read 1} resolved against.
 */
class InboxCursorsTest {

    private static InboxEntry entry(String key) {
        return new InboxEntry(key, Instant.EPOCH, null, "test", "{}", 0, null);
    }

    /** 100 entries at 7 a page, so the pages these cases name are all in range — {@link PageBounds} clamps. */
    private static PageBounds bounds(int page) {
        return new PageBounds(page, 7, 100);
    }

    @Test
    void dialogListingDoesNotFeedTheChatIndex() {
        InboxCursors cursors = new InboxCursors();
        UUID player = UUID.randomUUID();

        cursors.recordDialog(player, bounds(2), List.of(entry("a"), entry("b")));

        assertNull(cursors.chatListed(player),
                "a dialog listing must not become what /notifications read <entry> indexes");
    }

    @Test
    void chatListingDoesNotMoveTheDialogPage() {
        InboxCursors cursors = new InboxCursors();
        UUID player = UUID.randomUUID();

        cursors.recordDialog(player, bounds(3), List.of(entry("a")));
        cursors.recordChat(player, List.of(entry("b")));

        assertEquals(3, cursors.dialogPage(player),
                "listing in chat must leave the dialog's own page alone");
    }

    @Test
    void dialogPageDefaultsToOneWhenNothingListed() {
        assertEquals(1, new InboxCursors().dialogPage(UUID.randomUUID()));
    }

    @Test
    void dialogEntryResolvesByKey() {
        InboxCursors cursors = new InboxCursors();
        UUID player = UUID.randomUUID();
        cursors.recordDialog(player, bounds(1), List.of(entry("a"), entry("b")));

        InboxEntry found = cursors.dialogEntry(player, "b");

        assertNotNull(found);
        assertEquals("b", found.notifKey());
    }

    @Test
    void dialogEntryIsNullForAKeyNotOnTheListedPage() {
        InboxCursors cursors = new InboxCursors();
        UUID player = UUID.randomUUID();
        cursors.recordDialog(player, bounds(1), List.of(entry("a")));

        assertNull(cursors.dialogEntry(player, "gone"));
    }

    @Test
    void chatListedReturnsWhatChatListed() {
        InboxCursors cursors = new InboxCursors();
        UUID player = UUID.randomUUID();
        cursors.recordChat(player, List.of(entry("a"), entry("b")));

        assertEquals(2, cursors.chatListed(player).size());
    }

    @Test
    void clearChatLeavesTheDialogState() {
        InboxCursors cursors = new InboxCursors();
        UUID player = UUID.randomUUID();
        cursors.recordDialog(player, bounds(4), List.of(entry("a")));
        cursors.recordChat(player, List.of(entry("b")));

        cursors.clearChat(player);

        assertNull(cursors.chatListed(player));
        assertEquals(4, cursors.dialogPage(player), "/notifications clear must not close the dialog's page");
    }

    @Test
    void dropClearsBothSurfaces() {
        InboxCursors cursors = new InboxCursors();
        UUID player = UUID.randomUUID();
        cursors.recordDialog(player, bounds(5), List.of(entry("a")));
        cursors.recordChat(player, List.of(entry("b")));

        cursors.drop(player);

        assertNull(cursors.chatListed(player));
        assertNull(cursors.dialogEntry(player, "a"));
        assertEquals(1, cursors.dialogPage(player));
    }

    @Test
    void statePerPlayerIsIndependent() {
        InboxCursors cursors = new InboxCursors();
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();

        cursors.recordChat(one, List.of(entry("a")));
        cursors.drop(two);

        assertNotNull(cursors.chatListed(one), "dropping one player must not touch another's cursor");
    }
}
