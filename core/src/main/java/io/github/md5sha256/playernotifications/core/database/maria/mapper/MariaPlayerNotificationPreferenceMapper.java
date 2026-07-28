package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
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
            SELECT dataType, medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    @ConstructorArgs({
            @Arg(column = "dataType", javaType = String.class),
            @Arg(column = "medium", javaType = String.class)
    })
    @NotNull List<PlayerNotificationPreferenceEntity> selectByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Select("""
            SELECT medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND dataType = #{dataType}
            """)
    @NotNull List<String> selectByPlayerAndDataType(@Param("playerUuid") @NotNull UUID playerUuid,
                                                     @Param("dataType") @NotNull String dataType);

    @Override
    @Insert("""
            <script>
            INSERT INTO PlayerNotificationPreference (playerUuid, dataType, medium)
            VALUES
            <foreach item="medium" collection="media" separator=",">
                (#{playerUuid}, #{dataType}, #{medium})
            </foreach>
            </script>
            """)
    int insertPreferences(@Param("playerUuid") @NotNull UUID playerUuid,
                          @Param("dataType") @NotNull String dataType,
                          @Param("media") @NotNull Collection<String> media);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND dataType = #{dataType}
            """)
    int deleteByPlayerAndDataType(@Param("playerUuid") @NotNull UUID playerUuid,
                                  @Param("dataType") @NotNull String dataType);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    int deleteByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

}
