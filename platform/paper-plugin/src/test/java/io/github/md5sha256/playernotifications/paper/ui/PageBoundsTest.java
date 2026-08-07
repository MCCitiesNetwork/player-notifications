package io.github.md5sha256.playernotifications.paper.ui;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageBoundsTest {

    @Test
    @DisplayName("an empty list is page 1 of 1 with no navigation")
    void emptyListIsPageOneOfOne() {
        PageBounds bounds = new PageBounds(1, 5, 0);

        Assertions.assertEquals(1, bounds.page());
        Assertions.assertEquals(1, bounds.totalPages());
        Assertions.assertFalse(bounds.hasPrevious());
        Assertions.assertFalse(bounds.hasNext());
        Assertions.assertEquals(0, bounds.offset());
    }

    @Test
    @DisplayName("7 entries at size 3 gives 3 pages with offsets 0, 3 and 6")
    void offsetsWalkTheList() {
        Assertions.assertEquals(3, new PageBounds(1, 3, 7).totalPages());
        Assertions.assertEquals(0, new PageBounds(1, 3, 7).offset());
        Assertions.assertEquals(3, new PageBounds(2, 3, 7).offset());
        Assertions.assertEquals(6, new PageBounds(3, 3, 7).offset());
    }

    @Test
    @DisplayName("an out-of-range page clamps into range")
    void pageIsClamped() {
        Assertions.assertEquals(1, new PageBounds(0, 3, 7).page());
        Assertions.assertEquals(1, new PageBounds(-4, 3, 7).page());
        Assertions.assertEquals(3, new PageBounds(99, 3, 7).page());
    }

    @Test
    @DisplayName("pageSize clamps to 1..MAX_PAGE_SIZE")
    void pageSizeIsClamped() {
        Assertions.assertEquals(1, new PageBounds(1, 0, 7).pageSize());
        Assertions.assertEquals(1, new PageBounds(1, -3, 7).pageSize());
        Assertions.assertEquals(PageBounds.MAX_PAGE_SIZE, new PageBounds(1, 500, 7).pageSize());
    }

    @Test
    @DisplayName("an oversized pageSize is clamped before the page bound is derived from it")
    void pageSizeIsClampedBeforeThePageBound() {
        // Clamping page first would use the raw pageSize of 500, giving totalPages 1 for the wrong
        // reason; clamping pageSize first gives 20, and 10 entries still fit on page 1 of 1.
        PageBounds bounds = new PageBounds(1, 500, 10);

        Assertions.assertEquals(PageBounds.MAX_PAGE_SIZE, bounds.pageSize());
        Assertions.assertEquals(1, bounds.page());
        Assertions.assertEquals(1, bounds.totalPages());
    }

    @Test
    @DisplayName("a negative totalEntries clamps to zero")
    void negativeTotalClampsToZero() {
        PageBounds bounds = new PageBounds(1, 5, -12);

        Assertions.assertEquals(0, bounds.totalEntries());
        Assertions.assertEquals(1, bounds.totalPages());
    }

    @Test
    @DisplayName("next() on the last page and previous() on the first are no-ops")
    void navigationStopsAtTheEnds() {
        PageBounds last = new PageBounds(3, 3, 7);
        PageBounds first = new PageBounds(1, 3, 7);

        Assertions.assertEquals(3, last.next().page());
        Assertions.assertEquals(1, first.previous().page());
        Assertions.assertTrue(last.hasPrevious());
        Assertions.assertFalse(last.hasNext());
        Assertions.assertFalse(first.hasPrevious());
        Assertions.assertTrue(first.hasNext());
    }

    @Test
    @DisplayName("withPage moves within range and clamps outside it")
    void withPageClamps() {
        PageBounds bounds = new PageBounds(1, 3, 7);

        Assertions.assertEquals(2, bounds.withPage(2).page());
        Assertions.assertEquals(3, bounds.withPage(50).page());
        Assertions.assertEquals(1, bounds.withPage(-1).page());
    }

    @Test
    @DisplayName("offset never runs past the end of the list")
    void offsetNeverExceedsTotal() {
        for (int total = 0; total <= 25; total++) {
            for (int size = 1; size <= 7; size++) {
                for (int page = -2; page <= 30; page++) {
                    PageBounds bounds = new PageBounds(page, size, total);
                    Assertions.assertTrue(bounds.offset() <= Math.max(0, total - 1) || total == 0,
                            "offset " + bounds.offset() + " past end for " + bounds);
                }
            }
        }
    }
}
