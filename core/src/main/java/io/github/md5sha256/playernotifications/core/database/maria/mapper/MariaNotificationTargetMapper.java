package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * MariaDB-specific MyBatis mapper for the {@code NotificationTarget} table.
 */
public interface MariaNotificationTargetMapper extends NotificationTargetMapper {

    @Override
    @Select("""
            SELECT COALESCE(MAX(notifTargetId), 0) + 1
            FROM NotificationTarget
            """)
    int nextTargetId();

    @Override
    @Insert("""
            <script>
            INSERT INTO NotificationTarget (notifTargetId, playerUuid)
            VALUES
            <foreach item="playerUuid" collection="playerUuids" separator=",">
                (#{notifTargetId}, #{playerUuid})
            </foreach>
            </script>
            """)
    int insertMembers(@Param("notifTargetId") int notifTargetId,
                      @Param("playerUuids") @NotNull Collection<UUID> playerUuids);

    @Override
    @Select("""
            SELECT playerUuid
            FROM NotificationTarget
            WHERE notifTargetId = #{notifTargetId}
            """)
    @NotNull List<UUID> selectPlayerUuids(@Param("notifTargetId") int notifTargetId);

    @Override
    @Delete("""
            <script>
            DELETE FROM NotificationTarget
            WHERE notifTargetId = #{notifTargetId}
            AND playerUuid IN
            <foreach item="playerUuid" collection="playerUuids" open="(" separator="," close=")">
                #{playerUuid}
            </foreach>
            </script>
            """)
    int deleteMembers(@Param("notifTargetId") int notifTargetId,
                      @Param("playerUuids") @NotNull Collection<UUID> playerUuids);

    @Override
    @Update("""
            UPDATE NotificationTarget SET seenTime = #{seenTime}
            WHERE notifTargetId = #{notifTargetId} AND playerUuid = #{playerUuid} AND seenTime IS NULL
            """)
    int markSeen(@Param("notifTargetId") int notifTargetId,
                 @Param("playerUuid") @NotNull UUID playerUuid,
                 @Param("seenTime") @NotNull Instant seenTime);

    @Override
    @Update("""
            UPDATE NotificationTarget SET seenTime = NULL
            WHERE notifTargetId = #{notifTargetId} AND playerUuid = #{playerUuid} AND seenTime IS NOT NULL
            """)
    int markUnread(@Param("notifTargetId") int notifTargetId,
                   @Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Update("""
            <script>
            UPDATE NotificationTarget SET seenTime = #{seenTime}
            WHERE playerUuid = #{playerUuid} AND seenTime IS NULL
            <if test="dataType != null">
                AND EXISTS (
                    SELECT 1 FROM Notification n
                    WHERE n.notifTargetId = NotificationTarget.notifTargetId
                      AND n.notifPayloadType = #{dataType}
                )
            </if>
            </script>
            """)
    int markAllSeenForPlayer(@Param("playerUuid") @NotNull UUID playerUuid,
                             @Param("seenTime") @NotNull Instant seenTime,
                             @Param("dataType") @Nullable String dataType);

    @Override
    @Select("""
            <script>
            SELECT n.notifKey
            FROM Notification n
            INNER JOIN NotificationTarget t ON t.notifTargetId = n.notifTargetId
            WHERE t.playerUuid = #{playerUuid} AND t.seenTime IS NOT NULL
            <if test="dataType != null">AND n.notifPayloadType = #{dataType}</if>
            </script>
            """)
    @NotNull List<String> selectSeenKeys(@Param("playerUuid") @NotNull UUID playerUuid,
                                         @Param("dataType") @Nullable String dataType);

    @Override
    @Delete("""
            DELETE FROM NotificationTarget
            WHERE playerUuid = #{playerUuid} AND seenTime IS NOT NULL
            """)
    int deleteSeenForPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Select("""
            SELECT DISTINCT t.notifTargetId
            FROM NotificationTarget t
            LEFT JOIN Notification n ON n.notifTargetId = t.notifTargetId
            WHERE n.notifTargetId IS NULL
            """)
    @NotNull List<Integer> selectOrphanedTargetIds();

    @Override
    @Delete("""
            DELETE FROM NotificationTarget
            WHERE notifTargetId = #{notifTargetId}
            """)
    int deleteByTargetId(@Param("notifTargetId") int notifTargetId);

}
