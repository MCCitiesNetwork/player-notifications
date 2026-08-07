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

    int deleteByTargetId(int notifTargetId);

}
