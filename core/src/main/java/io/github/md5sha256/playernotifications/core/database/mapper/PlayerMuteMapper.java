package io.github.md5sha256.playernotifications.core.database.mapper;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code PlayerNotificationMute} table, which stores
 * the player-level "do not disturb" flag as a single row per muted player. Presence of the row is the
 * mute; {@code mutedTime} is not read by any query. SQL annotations are supplied by database-specific
 * sub-interfaces.
 */
public interface PlayerMuteMapper {

    /**
     * The number of stored mute rows for the given player — {@code 0} if unmuted, {@code 1} if muted.
     */
    int countByPlayer(@NotNull UUID playerUuid);

    /**
     * Inserts (or, if already present, refreshes the timestamp of) the mute row for the given player.
     *
     * @return the number of rows affected
     */
    int insertMute(@NotNull UUID playerUuid, @NotNull Instant mutedTime);

    /**
     * Deletes the mute row for the given player, if any.
     *
     * @return the number of rows removed
     */
    int deleteByPlayer(@NotNull UUID playerUuid);

}
