package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Internal entity record mapping one-to-one to a row of the {@code Notification}
 * DDL table. Unlike the public
 * {@link io.github.md5sha256.playernotifications.api.Notification api.Notification}
 * record, this carries {@link #notifPayloadType()} so notifications can be
 * cleared by payload type.
 *
 * @param notifKey           the primary key
 * @param notifScheduledTime when the notification becomes deliverable
 * @param notifExpiryTime    when the notification expires, or {@code null} if it never does
 * @param notifTargetId      FK to the {@code NotificationTarget} group
 * @param notifPayloadType   the registered data-type string identifying the payload
 * @param notifPayload       the JSON-encoded payload
 * @param notifPriority      delivery priority; higher is delivered first
 */
public record NotificationEntity(
        @NotNull String notifKey,
        @NotNull Instant notifScheduledTime,
        @Nullable Instant notifExpiryTime,
        int notifTargetId,
        @NotNull String notifPayloadType,
        @NotNull String notifPayload,
        int notifPriority
) {
}
