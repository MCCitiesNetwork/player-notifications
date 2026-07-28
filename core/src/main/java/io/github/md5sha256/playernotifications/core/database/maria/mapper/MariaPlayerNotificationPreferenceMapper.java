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
            SELECT category, medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    @ConstructorArgs({
            @Arg(column = "category", javaType = String.class),
            @Arg(column = "medium", javaType = String.class)
    })
    @NotNull List<PlayerNotificationPreferenceEntity> selectByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Select("""
            SELECT medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND category = #{category}
            """)
    @NotNull List<String> selectByPlayerAndCategory(@Param("playerUuid") @NotNull UUID playerUuid,
                                                     @Param("category") @NotNull String category);

    @Override
    @Insert("""
            <script>
            INSERT INTO PlayerNotificationPreference (playerUuid, category, medium)
            VALUES
            <foreach item="medium" collection="media" separator=",">
                (#{playerUuid}, #{category}, #{medium})
            </foreach>
            </script>
            """)
    int insertPreferences(@Param("playerUuid") @NotNull UUID playerUuid,
                          @Param("category") @NotNull String category,
                          @Param("media") @NotNull Collection<String> media);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND category = #{category}
            """)
    int deleteByPlayerAndCategory(@Param("playerUuid") @NotNull UUID playerUuid,
                                  @Param("category") @NotNull String category);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    int deleteByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

}
