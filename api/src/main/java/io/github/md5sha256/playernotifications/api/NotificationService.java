package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Public API entry point for sending notifications to players.
 */
public interface NotificationService {

    void enqueueNotification(@NotNull ResolvedNotification notification, boolean overwriteAllowed);

    @NotNull
    List<ResolvedNotification> resolveAndClearNotifications(@NotNull UUID playerId);

    void clearNotification(@NotNull String notificationKey);

    void clearNotifications(@NotNull UUID playerId);

    void clearNotifications(@NotNull String notificationDataType);

    void clearExpiredNotifications();

    @NotNull NotificationDataTypeRegistry dataTypeRegistry();

}
