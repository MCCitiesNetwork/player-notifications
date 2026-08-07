package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One page of a player's inbox.
 *
 * <p>Carries {@code totalEntries} and {@code unreadCount} alongside the page's own entries because the
 * list screen renders "page 2 of 5" and the join line renders a count — neither is derivable from a
 * paged {@code entries} list, and a second round trip per screen is not worth avoiding a five-field
 * record.
 */
public record InboxPage(@NotNull List<InboxEntry> entries,
                        int page,
                        int pageSize,
                        int totalEntries,
                        int unreadCount) {

    /** At least 1, so an empty inbox reads as "page 1 of 1" rather than "page 1 of 0". */
    public int totalPages() {
        return Math.max(1, (this.totalEntries + this.pageSize - 1) / this.pageSize);
    }
}
