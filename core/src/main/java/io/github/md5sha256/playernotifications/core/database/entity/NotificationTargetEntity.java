package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Internal entity record mapping to a single membership row of the
 * {@code NotificationTarget} DDL table. A logical target is the set of all rows
 * sharing a {@link #notifTargetId()}; each row contributes one
 * {@link #playerUuid() player} to that target.
 *
 * @param notifTargetId the target-group id
 * @param playerUuid    a member of the target group
 */
public record NotificationTargetEntity(
        int notifTargetId,
        @NotNull UUID playerUuid
) {
}
