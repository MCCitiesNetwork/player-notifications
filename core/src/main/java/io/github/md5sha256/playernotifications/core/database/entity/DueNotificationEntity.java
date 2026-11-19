package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One due, unseen notification for one player, as read by a batched due query: the
 * {@link NotificationEntity} columns plus the {@code playerUuid} the row was selected for, which a
 * multi-player query needs and a single-player one does not.
 */
public record DueNotificationEntity(
        @NotNull UUID playerUuid,
        @NotNull String notifKey,
        @NotNull Instant notifScheduledTime,
        @Nullable Instant notifExpiryTime,
        int notifTargetId,
        @NotNull String notifPayloadType,
        @NotNull String notifPayload,
        int notifPriority
) {

    /** The notification itself, without the player it was selected for. */
    public @NotNull NotificationEntity notification() {
        return new NotificationEntity(this.notifKey, this.notifScheduledTime, this.notifExpiryTime,
                this.notifTargetId, this.notifPayloadType, this.notifPayload, this.notifPriority);
    }
}
