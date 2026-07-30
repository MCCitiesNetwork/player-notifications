package io.github.md5sha256.playernotifications.core.database.maria;

import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import io.github.md5sha256.playernotifications.core.database.maria.mapper.MariaNotificationMapper;
import io.github.md5sha256.playernotifications.core.database.maria.mapper.MariaNotificationTargetMapper;
import io.github.md5sha256.playernotifications.core.database.maria.mapper.MariaPlayerNotificationPreferenceMapper;
import org.apache.ibatis.session.SqlSession;
import org.jetbrains.annotations.NotNull;

/**
 * MariaDB-backed {@link SqlSessionWrapper}. Each accessor resolves the
 * corresponding MariaDB mapper from the wrapped {@link SqlSession}.
 */
public record MariaSqlSession(@NotNull SqlSession session) implements SqlSessionWrapper {

    @Override
    public @NotNull NotificationMapper notificationMapper() {
        return session.getMapper(MariaNotificationMapper.class);
    }

    @Override
    public @NotNull NotificationTargetMapper notificationTargetMapper() {
        return session.getMapper(MariaNotificationTargetMapper.class);
    }

    @Override
    public @NotNull PlayerNotificationPreferenceMapper playerNotificationPreferenceMapper() {
        return session.getMapper(MariaPlayerNotificationPreferenceMapper.class);
    }

    @Override
    public void close() {
        session.close();
    }
}
