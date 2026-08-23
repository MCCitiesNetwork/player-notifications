package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * An in-memory {@link NotificationService} that records the calls the Discord surface makes, including
 * the {@code dataType} filter each one carried — the filter is the whole difference between the
 * {@code /mail} and {@code /notifications} views, so it has to be asserted rather than assumed.
 */
final class FakeNotificationService implements NotificationService {

    /** One recorded mutation: the method name, and the data-type filter it was called with. */
    record Call(String method, String dataType, String notifKey) {
    }

    private final NotificationDataTypeRegistry registry;
    private final List<InboxEntry> entries = new ArrayList<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<TypedNotification<?>> enqueued = new ArrayList<>();
    private RuntimeException enqueueFailure;

    FakeNotificationService(NotificationDataTypeRegistry registry) {
        this.registry = registry;
    }

    void add(InboxEntry entry) {
        this.entries.add(entry);
    }

    void failEnqueueWith(RuntimeException failure) {
        this.enqueueFailure = failure;
    }

    List<Call> calls() {
        return List.copyOf(this.calls);
    }

    List<TypedNotification<?>> enqueued() {
        return List.copyOf(this.enqueued);
    }

    private List<InboxEntry> matching(Collection<String> dataTypes) {
        // null is unfiltered; an empty collection matches nothing. See NotificationService.
        return this.entries.stream()
                .filter(entry -> dataTypes == null || dataTypes.contains(entry.notifPayloadType()))
                .toList();
    }

    /**
     * The single data type a filter names, for the call log. Every view in this module filters to one
     * type or to nothing at all, so recording more would assert on a shape nothing produces.
     */
    private static String soleType(Collection<String> dataTypes) {
        return dataTypes == null || dataTypes.isEmpty() ? null : dataTypes.iterator().next();
    }

    @Override
    public InboxPage inbox(UUID playerId, int page, int pageSize, Collection<String> dataTypes) {
        List<InboxEntry> matching = matching(dataTypes);
        // The same clamping contract DefaultNotificationService applies, so a view relying on it is
        // exercised here rather than only in production.
        int size = Math.min(20, Math.max(1, pageSize));
        int totalPages = Math.max(1, (matching.size() + size - 1) / size);
        int clamped = Math.min(totalPages, Math.max(1, page));
        int from = Math.min(matching.size(), (clamped - 1) * size);
        int to = Math.min(matching.size(), from + size);
        return new InboxPage(matching.subList(from, to), clamped, size, matching.size(),
                (int) matching.stream().filter(InboxEntry::unread).count());
    }

    @Override
    public int unreadCount(UUID playerId, Collection<String> dataTypes) {
        return (int) matching(dataTypes).stream().filter(InboxEntry::unread).count();
    }

    @Override
    public void markSeen(String notificationKey, UUID playerId) {
        this.calls.add(new Call("markSeen", null, notificationKey));
        this.entries.replaceAll(entry -> entry.notifKey().equals(notificationKey)
                ? new InboxEntry(entry.notifKey(), entry.notifScheduledTime(), entry.notifExpiryTime(),
                entry.notifPayloadType(), entry.notifPayload(), entry.notifPriority(),
                java.time.Instant.EPOCH)
                : entry);
    }

    @Override
    public void markUnread(String notificationKey, UUID playerId) {
        this.calls.add(new Call("markUnread", null, notificationKey));
        this.entries.replaceAll(entry -> entry.notifKey().equals(notificationKey)
                ? new InboxEntry(entry.notifKey(), entry.notifScheduledTime(), entry.notifExpiryTime(),
                entry.notifPayloadType(), entry.notifPayload(), entry.notifPriority(), null)
                : entry);
    }

    @Override
    public void markAllSeen(UUID playerId, Collection<String> dataTypes) {
        this.calls.add(new Call("markAllSeen", soleType(dataTypes), null));
    }

    @Override
    public void dismissSeen(UUID playerId, Collection<String> dataTypes) {
        this.calls.add(new Call("dismissSeen", soleType(dataTypes), null));
    }

    @Override
    public void deleteNotificationTarget(String notificationKey, UUID playerId) {
        this.calls.add(new Call("deleteNotificationTarget", null, notificationKey));
        this.entries.removeIf(entry -> entry.notifKey().equals(notificationKey));
    }

    @Override
    public <T> void enqueueNotification(TypedNotification<T> notification, boolean overwriteAllowed) {
        if (this.enqueueFailure != null) {
            throw this.enqueueFailure;
        }
        this.enqueued.add(notification);
    }

    @Override
    public NotificationDataTypeRegistry dataTypeRegistry() {
        return this.registry;
    }

    // Everything below is outside what the Discord surface calls.

    @Override
    public void enqueueNotification(ResolvedNotification notification, boolean overwriteAllowed) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> void registerJsonPayload(String dataType, Class<T> type, NotificationProcessor<T> processor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> void registerJsonRenderable(String dataType, Class<T> type, NotificationRenderer<T> renderer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ResolvedNotification> resolveNotifications(UUID playerId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void pruneOrphanedTargets() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearNotification(String notificationKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteNotificationTargets(String notificationKey, Collection<UUID> playerIds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearNotifications(UUID playerId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearNotifications(String notificationDataType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearExpiredNotifications() {
        throw new UnsupportedOperationException();
    }

    @Override
    public NotificationCategoryRegistry categoryRegistry() {
        return new DefaultNotificationCategoryRegistry();
    }
}
