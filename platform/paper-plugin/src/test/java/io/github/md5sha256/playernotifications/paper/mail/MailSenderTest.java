package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Covers {@link MailSender}: exactly what {@code send} enqueues, since sending is otherwise
 * indistinguishable from any other notification once it hits the database.
 */
class MailSenderTest {

    /** Captures every enqueue call rather than persisting anything. */
    private static final class RecordingService implements NotificationService {

        private final List<TypedNotification<?>> enqueued = new ArrayList<>();
        private final List<Boolean> overwriteFlags = new ArrayList<>();

        @Override
        public void enqueueNotification(@NotNull ResolvedNotification notification, boolean overwriteAllowed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void enqueueNotification(@NotNull TypedNotification<T> notification, boolean overwriteAllowed) {
            this.enqueued.add(notification);
            this.overwriteFlags.add(overwriteAllowed);
        }

        @Override
        public <T> void registerJsonPayload(@NotNull String dataType, @NotNull Class<T> type,
                @NotNull io.github.md5sha256.playernotifications.api.processor.NotificationProcessor<T> processor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type,
                @NotNull io.github.md5sha256.playernotifications.api.render.NotificationRenderer<T> renderer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull List<ResolvedNotification> resolveNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotification(@NotNull String notificationKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTarget(@NotNull String notificationKey, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTargets(@NotNull String notificationKey, @NotNull Collection<UUID> playerIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull String notificationDataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearExpiredNotifications() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int unreadCount(@NotNull UUID playerId, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSeen(@NotNull String key, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markUnread(@NotNull String key, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAllSeen(@NotNull UUID playerId, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dismissSeen(@NotNull UUID playerId, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void pruneOrphanedTargets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationDataTypeRegistry dataTypeRegistry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationCategoryRegistry categoryRegistry() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    @DisplayName("send enqueues a mail notification with the documented shape")
    void sendEnqueuesMail() {
        RecordingService service = new RecordingService();
        MailSender sender = new MailSender(service);
        UUID senderId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        String key = sender.send(senderId, "Steve", recipient, "hello there");

        Assertions.assertEquals(1, service.enqueued.size());
        TypedNotification<?> notification = service.enqueued.get(0);

        Assertions.assertEquals(MailPayload.DATA_TYPE, notification.notifPayloadType());
        Assertions.assertNull(notification.notifExpiryTime());
        Assertions.assertEquals(List.of(recipient), notification.notifTarget().playerUUIDs());
        Assertions.assertTrue(notification.notifKey().startsWith("mail-"));
        Assertions.assertEquals(key, notification.notifKey());
        Assertions.assertFalse(service.overwriteFlags.get(0));

        Assertions.assertInstanceOf(MailPayload.class, notification.notifPayload());
        MailPayload payload = (MailPayload) notification.notifPayload();
        Assertions.assertEquals(senderId, payload.sender());
        Assertions.assertEquals("Steve", payload.senderName());
        Assertions.assertEquals("hello there", payload.message());
    }

    @Test
    @DisplayName("the send-time overload stores that instant as the scheduled time")
    void storesTheGivenSendTimeAsTheScheduledTime() {
        RecordingService service = new RecordingService();
        MailSender sender = new MailSender(service);
        UUID senderId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        Instant sentAt = Instant.parse("2019-04-01T12:00:00Z");

        sender.send(senderId, "Alex", recipient, "hello", sentAt);

        TypedNotification<?> notification = service.enqueued.get(0);
        Assertions.assertEquals(sentAt, notification.notifScheduledTime());
        // Imported mail must still never expire, exactly as a live send does not.
        Assertions.assertNull(notification.notifExpiryTime());
    }

    @Test
    @DisplayName("the four-argument send stamps the current time")
    void defaultSendUsesNow() {
        RecordingService service = new RecordingService();
        MailSender sender = new MailSender(service);
        Instant before = Instant.now();

        sender.send(UUID.randomUUID(), "Steve", UUID.randomUUID(), "hi");

        Instant scheduled = service.enqueued.get(0).notifScheduledTime();
        Assertions.assertFalse(scheduled.isBefore(before));
        Assertions.assertFalse(scheduled.isAfter(Instant.now()));
    }

    @Test
    @DisplayName("two successive sends produce different keys")
    void keysAreUnique() {
        RecordingService service = new RecordingService();
        MailSender sender = new MailSender(service);
        UUID senderId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        String first = sender.send(senderId, "Steve", recipient, "hi");
        String second = sender.send(senderId, "Steve", recipient, "hi again");

        Assertions.assertNotEquals(first, second);
    }

    @Test
    @DisplayName("the console sends as the reserved server identity")
    void consoleSendsAsServer() {
        RecordingService service = new RecordingService();
        MailSender sender = new MailSender(service);
        UUID recipient = UUID.randomUUID();

        sender.send(MailSender.SERVER_SENDER, MailSender.SERVER_NAME, recipient, "an announcement");

        TypedNotification<?> notification = service.enqueued.get(0);
        MailPayload payload = (MailPayload) notification.notifPayload();
        Assertions.assertEquals("Server", payload.senderName());
        Assertions.assertEquals(new UUID(0, 0), payload.sender());
        Assertions.assertEquals(MailSender.SERVER_SENDER, payload.sender());
    }
}
