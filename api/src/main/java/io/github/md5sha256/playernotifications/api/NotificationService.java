package io.github.md5sha256.playernotifications.api;

import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Public API entry point for sending notifications to players.
 */
public interface NotificationService {

    void enqueueNotification(@NotNull ResolvedNotification notification, boolean overwriteAllowed);

    /**
     * Enqueues a notification with a typed payload, serializing it through the
     * {@link io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer} registered for
     * {@link TypedNotification#notifPayloadType()}.
     *
     * @throws io.github.md5sha256.playernotifications.api.serialize.PayloadSerializationException
     *         if no serializer is registered for the data type, or serialization fails
     */
    <T> void enqueueNotification(@NotNull TypedNotification<T> notification, boolean overwriteAllowed);

    /**
     * Registers a payload type served by the default JSON serializer: binds the data-type mapping, a
     * reflective JSON serializer for {@code type}, and the processor in one call. Authors needing
     * custom serialization use {@link #dataTypeRegistry()} directly with their own
     * {@link io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer}.
     */
    <T> void registerJsonPayload(@NotNull String dataType, @NotNull Class<T> type,
                                 @NotNull NotificationProcessor<T> processor);

    /**
     * The renderer-path counterpart of {@link #registerJsonPayload}: binds the data-type mapping, a
     * reflective JSON serializer for {@code type}, and a {@link NotificationRenderer} in one call.
     *
     * <p>Deliberately registers no {@link NotificationProcessor}. An explicit processor wins dispatch
     * precedence and bypasses preferences and sinks entirely, so registering one here would defeat the
     * point — payloads registered this way fan out to whichever media the recipient prefers.
     *
     * <p>{@code type} must be a class this payload owns. The registry keys serializers and renderers by
     * payload class, so two data types sharing one class silently share its handlers.
     */
    <T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type,
                                    @NotNull NotificationRenderer<T> renderer);

    /**
     * Resolves (without deleting) every notification currently targeting the given player. Use
     * {@link #clearNotifications(UUID)} to remove them.
     */
    @NotNull
    List<ResolvedNotification> resolveNotifications(@NotNull UUID playerId);

    /**
     * Reads one page of the given player's inbox: every notification currently targeting them that is
     * due and not yet expired, seen or not, newest first.
     *
     * <p>{@code page} is 1-based and clamped into {@code 1..totalPages}; {@code pageSize} is clamped
     * into {@code 1..20}. Clamping rather than throwing means a stale UI button asking for page 9 of a
     * now-4-page inbox gets page 4 instead of an error screen.
     */
    default @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize) {
        return inbox(playerId, page, pageSize, null);
    }

    /**
     * The data-type-filtered form of {@link #inbox(UUID, int, int)}: {@code dataType} of {@code null}
     * means unfiltered (identical to the three-argument form); a non-null value restricts every
     * count and row to that data type, so a filtered view such as {@code /mail} agrees with its own
     * paging rather than paging against the whole inbox.
     */
    @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize, @Nullable String dataType);

    /** The number of due, unexpired notifications the player has not yet seen. */
    default int unreadCount(@NotNull UUID playerId) {
        return unreadCount(playerId, null);
    }

    /** The data-type-filtered form of {@link #unreadCount(UUID)}; {@code null} means unfiltered. */
    int unreadCount(@NotNull UUID playerId, @Nullable String dataType);

    /**
     * Marks one notification seen for one player. An unknown key, or a player the notification does not
     * target, affects zero rows and is a silent no-op — as {@link #deleteNotificationTarget} already is.
     * An already-seen notification keeps its original timestamp.
     */
    void markSeen(@NotNull String notificationKey, @NotNull UUID playerId);

    /** Marks every unread notification seen for the given player. */
    default void markAllSeen(@NotNull UUID playerId) {
        markAllSeen(playerId, null);
    }

    /** The data-type-filtered form of {@link #markAllSeen(UUID)}; {@code null} means unfiltered. */
    void markAllSeen(@NotNull UUID playerId, @Nullable String dataType);

    /**
     * Dismisses the player's already-seen notifications: their target rows are deleted, so a dismissed
     * notification can never reappear, and a notification left with no targets is disposed of by the
     * existing trigger. Unread notifications are untouched.
     */
    default void dismissSeen(@NotNull UUID playerId) {
        dismissSeen(playerId, null);
    }

    /** The data-type-filtered form of {@link #dismissSeen(UUID)}; {@code null} means unfiltered. */
    void dismissSeen(@NotNull UUID playerId, @Nullable String dataType);

    /**
     * Deletes target rows whose notification no longer exists. Notification deletes do not cascade into
     * the target table, so these rows leak; this is intended to run on the same periodic task as
     * {@link #clearExpiredNotifications()}.
     */
    void pruneOrphanedTargets();

    void clearNotification(@NotNull String notificationKey);

    /**
     * Removes a single player from the target audience of the given notification. If this leaves the
     * notification with no remaining targets, the notification itself is deleted.
     */
    void deleteNotificationTarget(@NotNull String notificationKey, @NotNull UUID playerId);

    /**
     * Removes several players from the target audience of the given notification in one operation.
     * If this leaves the notification with no remaining targets, the notification itself is deleted.
     * An empty collection is a no-op.
     */
    void deleteNotificationTargets(@NotNull String notificationKey, @NotNull Collection<UUID> playerIds);

    void clearNotifications(@NotNull UUID playerId);

    void clearNotifications(@NotNull String notificationDataType);

    void clearExpiredNotifications();

    @NotNull NotificationDataTypeRegistry dataTypeRegistry();

    @NotNull NotificationCategoryRegistry categoryRegistry();

}
