package io.github.md5sha256.playernotifications.core.database.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code Notification} table.
 * SQL annotations are supplied by database-specific sub-interfaces.
 *
 * @see NotificationEntity
 */
public interface NotificationMapper {

    @Nullable NotificationEntity selectByKey(@NotNull String notifKey);

    /**
     * Selects every notification whose target group contains the given player,
     * ordered by descending priority then ascending scheduled time.
     */
    @NotNull List<NotificationEntity> selectByPlayer(@NotNull UUID playerId);

    int insert(@NotNull NotificationEntity notification);

    int deleteByKey(@NotNull String notifKey);

    /** Deletes every notification whose target group contains the given player. */
    int deleteByPlayer(@NotNull UUID playerId);

    int deleteByPayloadType(@NotNull String notifPayloadType);

    /** Deletes notifications whose non-null expiry time is strictly before {@code now}. */
    int deleteExpired(@NotNull Instant now);

}
