package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MariaDB-specific MyBatis mapper for the {@code Notification} table.
 *
 * @see NotificationEntity
 */
public interface MariaNotificationMapper extends NotificationMapper {

    @Override
    @Select("""
            SELECT notifKey, notifScheduledTime, notifExpiryTime, notifTargetId,
                   notifPayloadType, notifPayload, notifPriority
            FROM Notification
            WHERE notifKey = #{notifKey}
            """)
    @ConstructorArgs({
            @Arg(column = "notifKey", javaType = String.class),
            @Arg(column = "notifScheduledTime", javaType = Instant.class),
            @Arg(column = "notifExpiryTime", javaType = Instant.class),
            @Arg(column = "notifTargetId", javaType = int.class),
            @Arg(column = "notifPayloadType", javaType = String.class),
            @Arg(column = "notifPayload", javaType = String.class),
            @Arg(column = "notifPriority", javaType = int.class)
    })
    @Nullable NotificationEntity selectByKey(@Param("notifKey") @NotNull String notifKey);

    @Override
    @Select("""
            SELECT n.notifKey, n.notifScheduledTime, n.notifExpiryTime, n.notifTargetId,
                   n.notifPayloadType, n.notifPayload, n.notifPriority
            FROM Notification n
            INNER JOIN NotificationTarget t ON t.notifTargetId = n.notifTargetId
            WHERE t.playerUuid = #{playerId}
            ORDER BY n.notifPriority DESC, n.notifScheduledTime ASC
            """)
    @ConstructorArgs({
            @Arg(column = "notifKey", javaType = String.class),
            @Arg(column = "notifScheduledTime", javaType = Instant.class),
            @Arg(column = "notifExpiryTime", javaType = Instant.class),
            @Arg(column = "notifTargetId", javaType = int.class),
            @Arg(column = "notifPayloadType", javaType = String.class),
            @Arg(column = "notifPayload", javaType = String.class),
            @Arg(column = "notifPriority", javaType = int.class)
    })
    @NotNull List<NotificationEntity> selectByPlayer(@Param("playerId") @NotNull UUID playerId);

    @Override
    @Select("""
            SELECT n.notifKey, n.notifScheduledTime, n.notifExpiryTime, n.notifTargetId,
                   n.notifPayloadType, n.notifPayload, n.notifPriority
            FROM Notification n
            INNER JOIN NotificationTarget t ON t.notifTargetId = n.notifTargetId
            WHERE t.playerUuid = #{playerId}
            AND t.seenTime IS NULL
            AND n.notifScheduledTime <= #{now}
            AND (n.notifExpiryTime IS NULL OR n.notifExpiryTime > #{now})
            ORDER BY n.notifPriority DESC, n.notifScheduledTime ASC
            """)
    @ConstructorArgs({
            @Arg(column = "notifKey", javaType = String.class),
            @Arg(column = "notifScheduledTime", javaType = Instant.class),
            @Arg(column = "notifExpiryTime", javaType = Instant.class),
            @Arg(column = "notifTargetId", javaType = int.class),
            @Arg(column = "notifPayloadType", javaType = String.class),
            @Arg(column = "notifPayload", javaType = String.class),
            @Arg(column = "notifPriority", javaType = int.class)
    })
    @NotNull List<NotificationEntity> selectDueByPlayer(@Param("playerId") @NotNull UUID playerId,
                                                        @Param("now") @NotNull Instant now);

    @Override
    @Insert("""
            INSERT INTO Notification (notifKey, notifScheduledTime, notifExpiryTime, notifTargetId,
                                      notifPayloadType, notifPayload, notifPriority)
            VALUES (#{notifKey}, #{notifScheduledTime}, #{notifExpiryTime}, #{notifTargetId},
                    #{notifPayloadType}, #{notifPayload}, #{notifPriority})
            """)
    int insert(@NotNull NotificationEntity notification);

    @Override
    @Delete("""
            DELETE FROM Notification
            WHERE notifKey = #{notifKey}
            """)
    int deleteByKey(@Param("notifKey") @NotNull String notifKey);

    @Override
    @Delete("""
            DELETE n FROM Notification n
            INNER JOIN NotificationTarget t ON t.notifTargetId = n.notifTargetId
            WHERE t.playerUuid = #{playerId}
            """)
    int deleteByPlayer(@Param("playerId") @NotNull UUID playerId);

    @Override
    @Delete("""
            DELETE FROM Notification
            WHERE notifPayloadType = #{notifPayloadType}
            """)
    int deleteByPayloadType(@Param("notifPayloadType") @NotNull String notifPayloadType);

    @Override
    @Delete("""
            DELETE FROM Notification
            WHERE notifExpiryTime IS NOT NULL
            AND notifExpiryTime < #{now}
            """)
    int deleteExpired(@Param("now") @NotNull Instant now);

}
