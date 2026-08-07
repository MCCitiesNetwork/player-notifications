package io.github.md5sha256.playernotifications.core.database.mapper;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code NotificationTarget}
 * table, which stores target groups as sets of member rows sharing a target id.
 * SQL annotations are supplied by database-specific sub-interfaces.
 */
public interface NotificationTargetMapper {

    /**
     * Returns the next unused target-group id ({@code MAX(id) + 1}). Intended to
     * be called within the same transaction that inserts the group's members so
     * the allocated id is consumed before a concurrent enqueue can reuse it.
     */
    int nextTargetId();

    /**
     * Inserts every given player as a member of the target group in a single
     * multi-row statement. The caller must ensure {@code playerUuids} is
     * non-empty; an empty collection would produce invalid SQL.
     *
     * @return the number of member rows inserted
     */
    int insertMembers(int notifTargetId, @NotNull Collection<UUID> playerUuids);

    @NotNull List<UUID> selectPlayerUuids(int notifTargetId);

    /**
     * Removes the given players from the target group. The caller must ensure {@code playerUuids} is
     * non-empty; an empty collection would produce invalid SQL.
     *
     * @return the number of member rows removed
     */
    int deleteMembers(int notifTargetId, @NotNull Collection<UUID> playerUuids);

    /**
     * Stamps {@code seenTime} on one member row, if it is not already stamped. The first mark stays
     * authoritative, so a re-delivery race cannot move the timestamp forward.
     *
     * @return the number of rows updated: {@code 1} on the first mark, {@code 0} afterwards
     */
    int markSeen(int notifTargetId, @NotNull UUID playerUuid, @NotNull Instant seenTime);

    /**
     * Stamps {@code seenTime} on every still-unread row belonging to the given player.
     *
     * @return the number of rows updated
     */
    int markAllSeenForPlayer(@NotNull UUID playerUuid, @NotNull Instant seenTime);

    /**
     * Deletes the given player's already-seen member rows. Removing the last member of a group lets the
     * existing trigger dispose of the notification, so there is no second cleanup path.
     *
     * @return the number of rows removed
     */
    int deleteSeenForPlayer(@NotNull UUID playerUuid);

    /**
     * Returns the target-group ids that have member rows but no surviving notification.
     * {@code deleteExpired}, {@code deleteByKey}, {@code deleteByPayloadType} and {@code deleteByPlayer}
     * all remove {@code Notification} rows without touching this table — the trigger only fires the other
     * way round — so these rows would otherwise accumulate forever.
     *
     * <p>This is deliberately a <em>read</em> followed by per-group {@link #deleteByTargetId} calls
     * rather than one {@code DELETE … LEFT JOIN Notification}: MariaDB refuses a statement that reads
     * {@code Notification} when the delete then fires {@code trg_delete_targetless_notification}, which
     * writes it ("Can't update table 'Notification' in stored function/trigger because it is already
     * used by statement which invoked this stored function/trigger").
     */
    @NotNull List<Integer> selectOrphanedTargetIds();

    int deleteByTargetId(int notifTargetId);

}
