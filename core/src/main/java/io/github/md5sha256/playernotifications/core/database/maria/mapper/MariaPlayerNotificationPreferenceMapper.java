package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * MariaDB-specific MyBatis mapper for the {@code PlayerNotificationPreference} table.
 */
public interface MariaPlayerNotificationPreferenceMapper extends PlayerNotificationPreferenceMapper {

    @Override
    @Select("""
            SELECT medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    @NotNull List<String> selectByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Insert("""
            <script>
            INSERT INTO PlayerNotificationPreference (playerUuid, medium)
            VALUES
            <foreach item="medium" collection="media" separator=",">
                (#{playerUuid}, #{medium})
            </foreach>
            </script>
            """)
    int insertPreferences(@Param("playerUuid") @NotNull UUID playerUuid,
                          @Param("media") @NotNull Collection<String> media);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    int deleteByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

}
