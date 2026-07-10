package io.github.md5sha256.playernotifications.api;

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

}
