package io.github.md5sha256.playernotifications.paper;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the gate and the reload swap. {@code PlayerJoinEvent} and the Bukkit scheduler need a live
 * server, so {@code onJoin} itself is verified by hand with {@code runServer} — everything reachable
 * without a server is tested here, which is why the gate decision lives in {@code deliver} rather
 * than inline in the event handler.
 *
 * <p>The {@code Plugin} argument is {@code null} throughout: it is only dereferenced by {@code onJoin}
 * (to reach the scheduler) and by the failure branch of {@code deliver} (to reach the logger), and no
 * test here takes either path. It is not a claim that {@code null} is a legal argument.
 */
class JoinDeliveryListenerTest {

    private final AtomicInteger supplierCalls = new AtomicInteger();
    private final Supplier<NotificationDelivery> delivery = () -> {
        this.supplierCalls.incrementAndGet();
        return null;
    };

    @Test
    void reportsConstructedSettings() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 3L);
        assertTrue(listener.enabled());
        assertEquals(3L, listener.delaySeconds());
    }

    @Test
    void reloadSettingsReplacesBoth() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 3L);
        listener.reloadSettings(false, 30L);
        assertFalse(listener.enabled());
        assertEquals(30L, listener.delaySeconds());
    }

    @Test
    void reloadSettingsClampsNegativeDelay() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 3L);
        listener.reloadSettings(true, -5L);
        assertEquals(0L, listener.delaySeconds());
    }

    @Test
    void deliverDoesNothingWhenDisabled() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, false, 0L);
        listener.deliver(UUID.randomUUID());
        assertEquals(0, this.supplierCalls.get());
    }

    @Test
    void deliverIsSkippedAfterAReloadTurnsItOff() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 30L);
        listener.reloadSettings(false, 30L);
        listener.deliver(UUID.randomUUID());
        assertEquals(0, this.supplierCalls.get());
    }

    @Test
    void mailReminderIsSentWhenMailIsUnread() {
        UUID player = UUID.randomUUID();
        JoinDeliveryListener listener = new JoinDeliveryListener(
                null, this.delivery, new FakeUnreadCountService(Map.of(MailPayload.DATA_TYPE, 2)),
                true, 0L);

        var reminder = listener.mailReminder(player);

        assertTrue(reminder != null);
        String text = PlainTextComponentSerializer.plainText().serialize(reminder);
        assertTrue(text.contains("/mail"));
    }

    @Test
    void mailReminderIsNothingWhenNoMailIsUnread() {
        UUID player = UUID.randomUUID();
        JoinDeliveryListener listener = new JoinDeliveryListener(
                null, this.delivery, new FakeUnreadCountService(Map.of(MailPayload.DATA_TYPE, 0)),
                true, 0L);

        assertNull(listener.mailReminder(player));
    }

    /** Answers {@link NotificationService#unreadCount(UUID, String)} from a fixed data-type -> count map. */
    private static final class FakeUnreadCountService implements NotificationService {

        private final Map<String, Integer> countsByDataType;

        FakeUnreadCountService(@NotNull Map<String, Integer> countsByDataType) {
            this.countsByDataType = countsByDataType;
        }

        @Override
        public int unreadCount(@NotNull UUID playerId, @Nullable String dataType) {
            return this.countsByDataType.getOrDefault(dataType, 0);
        }

        @Override
        public void enqueueNotification(@NotNull ResolvedNotification notification, boolean overwriteAllowed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void enqueueNotification(@NotNull TypedNotification<T> notification, boolean overwriteAllowed) {
            throw new UnsupportedOperationException();
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
        public @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSeen(@NotNull String notificationKey, @NotNull UUID playerId) {
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
        public @NotNull NotificationDataTypeRegistry dataTypeRegistry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationCategoryRegistry categoryRegistry() {
            throw new UnsupportedOperationException();
        }
    }
}
