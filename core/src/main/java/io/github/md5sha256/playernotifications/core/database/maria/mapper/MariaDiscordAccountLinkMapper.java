package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.DiscordAccountLinkEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.DiscordAccountLinkMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * MariaDB-specific MyBatis mapper for the {@code DiscordAccountLink} table.
 *
 * @see DiscordAccountLinkEntity
 */
public interface MariaDiscordAccountLinkMapper extends DiscordAccountLinkMapper {

    @Override
    @Select("""
            SELECT playerUuid, discordId, linkedAt
            FROM DiscordAccountLink
            WHERE playerUuid = #{playerUuid}
            """)
    @ConstructorArgs({
            @Arg(column = "playerUuid", javaType = UUID.class),
            @Arg(column = "discordId", javaType = long.class),
            @Arg(column = "linkedAt", javaType = Instant.class)
    })
    @Nullable DiscordAccountLinkEntity selectByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Select("""
            SELECT playerUuid, discordId, linkedAt
            FROM DiscordAccountLink
            WHERE discordId = #{discordId}
            """)
    @ConstructorArgs({
            @Arg(column = "playerUuid", javaType = UUID.class),
            @Arg(column = "discordId", javaType = long.class),
            @Arg(column = "linkedAt", javaType = Instant.class)
    })
    @Nullable DiscordAccountLinkEntity selectByDiscordId(@Param("discordId") long discordId);

    @Override
    @Insert("""
            INSERT INTO DiscordAccountLink (playerUuid, discordId, linkedAt)
            VALUES (#{playerUuid}, #{discordId}, #{linkedAt})
            """)
    int insertLink(@Param("playerUuid") @NotNull UUID playerUuid,
                   @Param("discordId") long discordId,
                   @Param("linkedAt") @NotNull Instant linkedAt);

    @Override
    @Delete("""
            DELETE FROM DiscordAccountLink
            WHERE playerUuid = #{playerUuid}
            """)
    int deleteByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Delete("""
            DELETE FROM DiscordAccountLink
            WHERE discordId = #{discordId}
            """)
    int deleteByDiscordId(@Param("discordId") long discordId);

}
