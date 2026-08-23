package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Covers the set-taking inbox filter: a filter naming several data types, {@code null} meaning
 * unfiltered, and an <em>empty</em> collection meaning "match nothing" rather than "match everything".
 *
 * <p>Design: {@code docs/superpowers/specs/2026-08-23-filtered-inbox-design.md}, sections 1 and 2.
 */
class FilteredInboxSetTest extends AbstractDatabaseTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static void insert(String key, String dataType, Instant scheduled, UUID... players) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(players));
            wrapper.notificationMapper().insert(new NotificationEntity(
                    key, scheduled, null, targetId, dataType, "\"hello\"", 0));
            wrapper.session().commit();
        }
    }

    private static void insertThreeTypes() {
        insert("mail-1", "mail", NOW.minusSeconds(60), PLAYER);
        insert("mail-2", "mail", NOW.minusSeconds(50), PLAYER);
        insert("broadcast-1", "broadcast", NOW.minusSeconds(40), PLAYER);
        insert("test-1", "test", NOW.minusSeconds(10), PLAYER);
    }

    @Test
    @DisplayName("a two-type filter returns entries of both types and excludes a third")
    void twoTypeFilterReturnsBoth() {
        insertThreeTypes();

        InboxPage page = service.inbox(PLAYER, 1, 10, Set.of("mail", "broadcast"));

        Assertions.assertEquals(3, page.totalEntries());
        Assertions.assertEquals(3, page.unreadCount());
        Assertions.assertTrue(page.entries().stream()
                .noneMatch(entry -> entry.notifPayloadType().equals("test")));
        Assertions.assertEquals(3, service.unreadCount(PLAYER, Set.of("mail", "broadcast")));
    }

    @Test
    @DisplayName("a null collection is unfiltered")
    void nullCollectionIsUnfiltered() {
        insertThreeTypes();

        InboxPage page = service.inbox(PLAYER, 1, 10, (java.util.Collection<String>) null);

        Assertions.assertEquals(4, page.totalEntries());
        Assertions.assertEquals(4, service.unreadCount(PLAYER, (java.util.Collection<String>) null));
    }

    @Test
    @DisplayName("an empty collection matches nothing rather than throwing on IN ()")
    void emptyCollectionMatchesNothing() {
        insertThreeTypes();

        InboxPage page = Assertions.assertDoesNotThrow(() -> service.inbox(PLAYER, 1, 10, Set.of()));

        Assertions.assertEquals(0, page.totalEntries());
        Assertions.assertEquals(0, page.entries().size());
        Assertions.assertEquals(0, page.unreadCount());
        int unread = Assertions.assertDoesNotThrow(() -> service.unreadCount(PLAYER, Set.of()));
        Assertions.assertEquals(0, unread);
        Assertions.assertDoesNotThrow(() -> service.markAllSeen(PLAYER, Set.of()));
        Assertions.assertDoesNotThrow(() -> service.dismissSeen(PLAYER, Set.of()));
        Assertions.assertEquals(4, service.inbox(PLAYER, 1, 10).totalEntries());
        Assertions.assertEquals(4, service.unreadCount(PLAYER));
    }

    @Test
    @DisplayName("markAllSeen and dismissSeen over a set touch only matching rows")
    void markAndDismissRespectSetFilter() {
        insertThreeTypes();

        service.markAllSeen(PLAYER, Set.of("mail", "broadcast"));
        Assertions.assertEquals(0, service.unreadCount(PLAYER, Set.of("mail", "broadcast")));
        Assertions.assertEquals(1, service.unreadCount(PLAYER));

        service.dismissSeen(PLAYER, Set.of("mail", "broadcast"));

        InboxPage remaining = service.inbox(PLAYER, 1, 10);
        Assertions.assertEquals(1, remaining.totalEntries());
        Assertions.assertEquals("test-1", remaining.entries().get(0).notifKey());
    }

    @Test
    @DisplayName("paging clamps against the filtered total, not the whole inbox")
    void pagingClampsAgainstFilteredTotal() {
        insertThreeTypes();

        InboxPage page = service.inbox(PLAYER, 9, 1, Set.of("mail", "broadcast"));

        Assertions.assertEquals(3, page.totalPages());
        Assertions.assertEquals(3, page.page());
        Assertions.assertEquals(1, page.entries().size());
        Assertions.assertEquals("mail-1", page.entries().get(0).notifKey());
    }
}
