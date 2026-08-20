package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.database.mapper.NotificationMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerMuteMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.apache.ibatis.session.SqlSession;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

/**
 * Wraps a MyBatis {@link SqlSession}, exposing the notification mappers bound to
 * it. All mappers returned by one wrapper share the same session (and therefore
 * the same transaction), so a unit of work should acquire a single wrapper,
 * perform its reads/writes, {@link SqlSession#commit() commit}, and close.
 */
public interface SqlSessionWrapper extends Closeable {

    @NotNull SqlSession session();

    @NotNull NotificationMapper notificationMapper();

    @NotNull NotificationTargetMapper notificationTargetMapper();

    @NotNull PlayerNotificationPreferenceMapper playerNotificationPreferenceMapper();

    @NotNull PlayerMuteMapper playerMuteMapper();

    @Override
    void close();
}
