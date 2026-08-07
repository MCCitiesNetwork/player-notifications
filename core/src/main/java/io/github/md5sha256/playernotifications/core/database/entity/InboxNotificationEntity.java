package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Internal entity record for one row of the inbox read: a {@code Notification} joined to the viewing
 * player's {@code NotificationTarget} row, so it carries that player's {@code seenTime}.
 *
 * <p>Mirrors {@link io.github.md5sha256.playernotifications.api.InboxEntry}, which
 * {@code DefaultNotificationService} maps it onto — the entity-per-read convention this package
 * already follows for {@link NotificationEntity}.
 */
public record InboxNotificationEntity(
        @NotNull String notifKey,
        @NotNull Instant notifScheduledTime,
        @Nullable Instant notifExpiryTime,
        @NotNull String notifPayloadType,
        @NotNull String notifPayload,
        int notifPriority,
        @Nullable Instant seenTime
) {
}
