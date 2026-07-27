package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Internal entity record mapping to a single row of the {@code PlayerNotificationPreference} DDL
 * table. A player's full preference set is the collection of all rows sharing a {@link #playerUuid()},
 * mirroring how a {@code NotificationTarget} group is the set of rows sharing a target id.
 *
 * @param playerUuid the player this preference row belongs to
 * @param medium     one medium key (e.g. {@code "chat"}) the player prefers
 */
public record PlayerNotificationPreferenceEntity(
        @NotNull UUID playerUuid,
        @NotNull String medium
) {
}
