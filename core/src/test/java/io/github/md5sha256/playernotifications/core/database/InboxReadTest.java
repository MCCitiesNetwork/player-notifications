package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Covers the inbox read path: paging, ordering, clamping, expiry exclusion and per-player isolation.
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-08-07-notification-inbox.md}, Tasks 4 and 5.
 */
class InboxReadTest extends AbstractDatabaseTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final String TYPE = "test";
    private static final String STORED = "\"hello\"";

    private static List<String> keys(InboxPage page) {
        return page.entries().stream().map(InboxEntry::notifKey).toList();
    }

    @Test
    @DisplayName("entries come back newest first")
    void newestFirst() {
        insert("old", NOW.minus(3, ChronoUnit.HOURS), null, PLAYER);
        insert("mid", NOW.minus(2, ChronoUnit.HOURS), null, PLAYER);
        insert("new", NOW.minus(1, ChronoUnit.HOURS), null, PLAYER);

        Assertions.assertEquals(List.of("new", "mid", "old"), keys(service.inbox(PLAYER, 1, 10)));
    }

    @Test
    @DisplayName("paging walks the whole inbox with no duplicates and no gaps")
    void pagingIsExhaustiveAndDisjoint() {
        for (int i = 0; i < 7; i++) {
            insert("n" + i, NOW.minus(7 - i, ChronoUnit.MINUTES), null, PLAYER);
        }

        List<String> seen = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            InboxPage read = service.inbox(PLAYER, page, 3);
            Assertions.assertEquals(7, read.totalEntries());
            Assertions.assertEquals(3, read.totalPages());
            seen.addAll(keys(read));
        }

        Assertions.assertEquals(7, seen.size());
        Assertions.assertEquals(7, Set.copyOf(seen).size());
    }

    @Test
    @DisplayName("an out-of-range page clamps into range rather than returning nothing")
    void pageIsClamped() {
        insert("a", NOW.minusSeconds(60), null, PLAYER);
        insert("b", NOW.minusSeconds(30), null, PLAYER);

        Assertions.assertEquals(1, service.inbox(PLAYER, 0, 1).page());
        Assertions.assertEquals(2, service.inbox(PLAYER, 99, 1).page());
    }

    @Test
    @DisplayName("pageSize clamps to 1..20")
    void pageSizeIsClamped() {
        insert("a", NOW.minusSeconds(60), null, PLAYER);

        Assertions.assertEquals(1, service.inbox(PLAYER, 1, 0).pageSize());
        Assertions.assertEquals(20, service.inbox(PLAYER, 1, 500).pageSize());
    }

    @Test
    @DisplayName("an expired notification is not in the inbox")
    void expiredIsExcluded() {
        insert("expired", NOW.minus(2, ChronoUnit.HOURS), NOW.minus(1, ChronoUnit.HOURS), PLAYER);
        insert("live", NOW.minus(2, ChronoUnit.HOURS), NOW.plus(1, ChronoUnit.HOURS), PLAYER);

        Assertions.assertEquals(List.of("live"), keys(service.inbox(PLAYER, 1, 10)));
    }

    @Test
    @DisplayName("a notification scheduled in the future is not in the inbox")
    void futureScheduledIsExcluded() {
        insert("future", NOW.plus(1, ChronoUnit.HOURS), null, PLAYER);

        Assertions.assertEquals(List.of(), keys(service.inbox(PLAYER, 1, 10)));
    }

    @Test
    @DisplayName("unreadCount counts only rows with no seenTime")
    void unreadCountIgnoresSeenRows() {
        insert("a", NOW.minusSeconds(60), null, PLAYER);
        insert("b", NOW.minusSeconds(30), null, PLAYER);
        markSeenDirectly("a", PLAYER);

        Assertions.assertEquals(1, service.unreadCount(PLAYER));
        Assertions.assertEquals(2, service.inbox(PLAYER, 1, 10).totalEntries());
        Assertions.assertEquals(1, service.inbox(PLAYER, 1, 10).unreadCount());
    }

    @Test
    @DisplayName("another player's notifications are not visible")
    void inboxIsPerPlayer() {
        insert("mine", NOW.minusSeconds(60), null, PLAYER);
        insert("theirs", NOW.minusSeconds(60), null, OTHER);

        Assertions.assertEquals(List.of("mine"), keys(service.inbox(PLAYER, 1, 10)));
        Assertions.assertEquals(List.of("theirs"), keys(service.inbox(OTHER, 1, 10)));
    }

    @Test
    @DisplayName("an empty inbox reads as page 1 of 1")
    void emptyInboxIsPageOneOfOne() {
        InboxPage page = service.inbox(PLAYER, 1, 10);

        Assertions.assertEquals(List.of(), page.entries());
        Assertions.assertEquals(1, page.page());
        Assertions.assertEquals(1, page.totalPages());
        Assertions.assertEquals(0, page.totalEntries());
        Assertions.assertEquals(0, page.unreadCount());
    }

    /** Stamps seenTime through the mapper, so the read tests do not depend on the delivery loop. */
    private static void markSeenDirectly(String key, UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationEntity entity = wrapper.notificationMapper().selectByKey(key);
            Assertions.assertNotNull(entity);
            wrapper.notificationTargetMapper().markSeen(entity.notifTargetId(), player, NOW);
            wrapper.session().commit();
        }
    }

    private static void insert(String key, Instant scheduled, Instant expiry, UUID... players) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(players));
            wrapper.notificationMapper().insert(new NotificationEntity(
                    key, scheduled, expiry, targetId, TYPE, STORED, 0));
            wrapper.session().commit();
        }
    }
}
