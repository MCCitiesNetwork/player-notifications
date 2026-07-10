package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;

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
    @Delete("""
            DELETE FROM NotificationTarget
            WHERE notifTargetId = #{notifTargetId}
            """)
    int deleteByTargetId(@Param("notifTargetId") int notifTargetId);

}
