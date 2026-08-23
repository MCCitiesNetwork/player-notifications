package io.github.md5sha256.playernotifications.core.database.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.InboxNotificationEntity;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.entity.UnreadDataTypeCountEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * Selects the notifications targeting the given player that are currently due
     * for delivery: scheduled at or before {@code now} and not yet expired. Ordered
     * by descending priority then ascending scheduled time.
     */
    @NotNull List<NotificationEntity> selectDueByPlayer(@NotNull UUID playerId, @NotNull Instant now);

    /**
     * Reads one page of the given player's inbox: every notification targeting them that is scheduled
     * at or before {@code now} and not yet expired, seen or not, newest first. The ordering carries a
     * {@code notifKey} tiebreak so it is a total order — without one, two notifications sharing a
     * timestamp could swap between page reads and appear twice or not at all.
     *
     * @param dataTypes when non-null, restricts the page to notifications of those data types; an
     *                  empty collection matches nothing
     */
    @NotNull List<InboxNotificationEntity> selectInboxPage(@NotNull UUID playerId, @NotNull Instant now,
                                                           int limit, int offset,
                                                           @Nullable Collection<String> dataTypes);

    /** Counts what {@link #selectInboxPage} would return unpaged. */
    int countInbox(@NotNull UUID playerId, @NotNull Instant now, @Nullable Collection<String> dataTypes);

    /** Counts the subset of {@link #countInbox} the player has not yet seen. */
    int countUnread(@NotNull UUID playerId, @NotNull Instant now, @Nullable Collection<String> dataTypes);

    /**
     * The unfiltered {@link #countUnread} broken down by data type, in one read. Data types with
     * nothing unread produce no row at all rather than a zero.
     */
    @NotNull List<UnreadDataTypeCountEntity> countUnreadByDataType(@NotNull UUID playerId,
                                                                   @NotNull Instant now);

    int insert(@NotNull NotificationEntity notification);

    int deleteByKey(@NotNull String notifKey);

    /** Deletes every notification whose target group contains the given player. */
    int deleteByPlayer(@NotNull UUID playerId);

    int deleteByPayloadType(@NotNull String notifPayloadType);

    /** Deletes notifications whose non-null expiry time is strictly before {@code now}. */
    int deleteExpired(@NotNull Instant now);

}
