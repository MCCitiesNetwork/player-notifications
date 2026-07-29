package io.github.md5sha256.playernotifications.api;

import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import org.jetbrains.annotations.NotNull;

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
