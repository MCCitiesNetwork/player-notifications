package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Covers the data-type-filtered inbox overloads that back a future filtered view (e.g. {@code /mail})
 * over the same tables.
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-08-10-first-party-mail.md}, Task 1.
 * Design: {@code docs/superpowers/specs/2026-08-10-first-party-mail-design.md}, section 4.
 */
class FilteredInboxTest extends AbstractDatabaseTest {

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

    @Test
    @DisplayName("filtering the inbox by dataType returns only that type, unfiltered still returns everything")
    void filterRestrictsToDataType() {
        insert("mail-1", "mail", NOW.minusSeconds(60), PLAYER);
        insert("mail-2", "mail", NOW.minusSeconds(30), PLAYER);
        insert("test-1", "test", NOW.minusSeconds(10), PLAYER);

        InboxPage mailPage = service.inbox(PLAYER, 1, 10, "mail");
        Assertions.assertEquals(2, mailPage.entries().size());
        Assertions.assertTrue(mailPage.entries().stream()
                .allMatch(entry -> entry.notifPayloadType().equals("mail")));
        Assertions.assertEquals(2, mailPage.totalEntries());
        Assertions.assertEquals(2, mailPage.unreadCount());

        InboxPage everything = service.inbox(PLAYER, 1, 10, (String) null);
        Assertions.assertEquals(3, everything.totalEntries());

        Assertions.assertEquals(2, service.unreadCount(PLAYER, "mail"));
        Assertions.assertEquals(3, service.unreadCount(PLAYER));
    }

    @Test
    @DisplayName("markAllSeen and dismissSeen respect the filter; dismissSeen is select-then-delete")
    void markAndDismissRespectFilter() {
        insert("mail-1", "mail", NOW.minusSeconds(60), PLAYER);
        insert("mail-2", "mail", NOW.minusSeconds(30), PLAYER);
        insert("test-1", "test", NOW.minusSeconds(10), PLAYER);

        service.markAllSeen(PLAYER, "mail");
        Assertions.assertEquals(0, service.unreadCount(PLAYER, "mail"));
        Assertions.assertEquals(1, service.unreadCount(PLAYER));

        service.markAllSeen(PLAYER, (String) null);
        service.dismissSeen(PLAYER, "mail");

        InboxPage remaining = service.inbox(PLAYER, 1, 10, (String) null);
        Assertions.assertEquals(1, remaining.totalEntries());
        Assertions.assertEquals("test-1", remaining.entries().get(0).notifKey());
    }

    @Test
    @DisplayName("paging respects the filter")
    void pagingRespectsFilter() {
        insert("mail-1", "mail", NOW.minusSeconds(60), PLAYER);
        insert("mail-2", "mail", NOW.minusSeconds(30), PLAYER);
        insert("test-1", "test", NOW.minusSeconds(10), PLAYER);

        InboxPage page2 = service.inbox(PLAYER, 2, 1, "mail");
        Assertions.assertEquals(2, page2.totalPages());
        Assertions.assertEquals(1, page2.entries().size());
        Assertions.assertEquals("mail-1", page2.entries().get(0).notifKey());
    }
}
