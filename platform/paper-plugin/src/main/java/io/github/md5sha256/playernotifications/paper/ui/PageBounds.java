package io.github.md5sha256.playernotifications.paper.ui;

/**
 * Page arithmetic for a paged dialog: which page, how big, how many entries in total. Pure — no
 * Bukkit, no Adventure — so it is exhaustively testable without a server.
 *
 * <p>{@code page} is 1-based. Every component is clamped by the compact constructor, so a stale
 * button asking for page 9 of a now-4-page list yields page 4 rather than an empty screen.
 *
 * <p>This class, like the rest of {@code paper.ui}, deliberately references nothing from this plugin;
 * see the package's own note.
 */
public record PageBounds(int page, int pageSize, int totalEntries) {

    public static final int MAX_PAGE_SIZE = 20;

    public PageBounds {
        // Order matters: pageSize is clamped first so the page bound below is derived from the clamped
        // size. Reversing these two lines gives a wrong page bound for an out-of-range pageSize.
        pageSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        totalEntries = Math.max(0, totalEntries);
        int totalPages = Math.max(1, (totalEntries + pageSize - 1) / pageSize);
        page = Math.clamp(page, 1, totalPages);
    }

    /** At least 1, so an empty list reads as "page 1 of 1". */
    public int totalPages() {
        return Math.max(1, (this.totalEntries + this.pageSize - 1) / this.pageSize);
    }

    public int offset() {
        return (this.page - 1) * this.pageSize;
    }

    public boolean hasPrevious() {
        return this.page > 1;
    }

    public boolean hasNext() {
        return this.page < totalPages();
    }

    public PageBounds withPage(int page) {
        return new PageBounds(page, this.pageSize, this.totalEntries);
    }

    public PageBounds previous() {
        return withPage(this.page - 1);
    }

    public PageBounds next() {
        return withPage(this.page + 1);
    }
}
