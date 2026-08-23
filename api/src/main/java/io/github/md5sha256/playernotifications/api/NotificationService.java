package io.github.md5sha256.playernotifications.api;

import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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
        return inbox(playerId, page, pageSize, (Collection<String>) null);
    }

    /**
     * The single-data-type form of {@link #inbox(UUID, int, int, Collection)}: {@code null} means
     * unfiltered, a non-null value filters to exactly that data type.
     */
    default @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize,
                                     @Nullable String dataType) {
        return inbox(playerId, page, pageSize, dataType == null ? null : List.of(dataType));
    }

    /**
     * The data-type-filtered form of {@link #inbox(UUID, int, int)}, restricting every count and row
     * to the given data types, so a filtered view such as {@code /mail} agrees with its own paging
     * rather than paging against the whole inbox.
     *
     * <p><strong>{@code null} means unfiltered; an empty collection means "match nothing".</strong>
     * These are deliberately different: a category may claim only data types nothing has registered,
     * and that screen must show an empty inbox rather than every notification.
     */
    @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize,
                             @Nullable Collection<String> dataTypes);

    /** The number of due, unexpired notifications the player has not yet seen. */
    default int unreadCount(@NotNull UUID playerId) {
        return unreadCount(playerId, (Collection<String>) null);
    }

    /** The single-data-type form of {@link #unreadCount(UUID, Collection)}. */
    default int unreadCount(@NotNull UUID playerId, @Nullable String dataType) {
        return unreadCount(playerId, dataType == null ? null : List.of(dataType));
    }

    /**
     * The data-type-filtered form of {@link #unreadCount(UUID)}; {@code null} means unfiltered, an
     * empty collection means "match nothing".
     */
    int unreadCount(@NotNull UUID playerId, @Nullable Collection<String> dataTypes);

    @NotNull Map<String, Integer> unreadCountsByDataType(@NotNull UUID playerId);

    /**
     * Marks one notification seen for one player. An unknown key, or a player the notification does not
     * target, affects zero rows and is a silent no-op — as {@link #deleteNotificationTarget} already is.
     * An already-seen notification keeps its original timestamp.
     */
    void markSeen(@NotNull String notificationKey, @NotNull UUID playerId);

    /**
     * Clears {@code seenTime} on one notification for one player, returning it to the unread state. An
     * unknown key, or a player the notification does not target, affects zero rows and is a silent
     * no-op, as {@link #markSeen} already is.
     *
     * <p>This is the exact inverse of {@link #markSeen}, timestamp included: nothing records that the
     * notification was ever read. It therefore becomes <em>due</em> again, so an unread notification
     * whose delivery is driven by a trigger (joining, today) is pushed a second time. That is what
     * unread means — the player asked to be reminded — but it is why this is a deliberate player
     * action rather than something any code path does on its behalf.
     */
    void markUnread(@NotNull String notificationKey, @NotNull UUID playerId);

    /** Marks every unread notification seen for the given player. */
    default void markAllSeen(@NotNull UUID playerId) {
        markAllSeen(playerId, (Collection<String>) null);
    }

    /** The single-data-type form of {@link #markAllSeen(UUID, Collection)}. */
    default void markAllSeen(@NotNull UUID playerId, @Nullable String dataType) {
        markAllSeen(playerId, dataType == null ? null : List.of(dataType));
    }

    /**
     * The data-type-filtered form of {@link #markAllSeen(UUID)}; {@code null} means unfiltered, an
     * empty collection means "match nothing" and so marks nothing.
     */
    void markAllSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes);

    /**
     * Dismisses the player's already-seen notifications: their target rows are deleted, so a dismissed
     * notification can never reappear, and a notification left with no targets is disposed of by the
     * existing trigger. Unread notifications are untouched.
     */
    default void dismissSeen(@NotNull UUID playerId) {
        dismissSeen(playerId, (Collection<String>) null);
    }

    /** The single-data-type form of {@link #dismissSeen(UUID, Collection)}. */
    default void dismissSeen(@NotNull UUID playerId, @Nullable String dataType) {
        dismissSeen(playerId, dataType == null ? null : List.of(dataType));
    }

    /**
     * The data-type-filtered form of {@link #dismissSeen(UUID)}; {@code null} means unfiltered, an
     * empty collection means "match nothing" and so dismisses nothing.
     */
    void dismissSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes);

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
