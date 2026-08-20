package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.mapper.PlayerMuteMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * MariaDB-specific MyBatis mapper for the {@code PlayerNotificationMute} table.
 */
public interface MariaPlayerMuteMapper extends PlayerMuteMapper {

    @Override
    @Select("""
            SELECT COUNT(*) FROM PlayerNotificationMute WHERE playerUuid = #{playerUuid}
            """)
    int countByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Insert("""
            INSERT INTO PlayerNotificationMute (playerUuid, mutedTime) VALUES (#{playerUuid}, #{mutedTime})
            ON DUPLICATE KEY UPDATE mutedTime = VALUES(mutedTime)
            """)
    int insertMute(@Param("playerUuid") @NotNull UUID playerUuid, @Param("mutedTime") @NotNull Instant mutedTime);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationMute WHERE playerUuid = #{playerUuid}
            """)
    int deleteByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

}
