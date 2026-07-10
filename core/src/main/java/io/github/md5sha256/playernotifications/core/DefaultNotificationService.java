package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link NotificationService} implementation backed by a MyBatis
 * {@link Database}. Each public method runs as a single committed transaction.
 */
public class DefaultNotificationService implements NotificationService {

    private final Database database;
    private final NotificationDataTypeRegistry dataTypeRegistry;

    public DefaultNotificationService(@NotNull Database database) {
        this(database, new NotificationDataTypeRegistry());
    }

    public DefaultNotificationService(@NotNull Database database,
                                      @NotNull NotificationDataTypeRegistry dataTypeRegistry) {
        this.database = database;
        this.dataTypeRegistry = dataTypeRegistry;
    }

    @Override
    public void enqueueNotification(@NotNull ResolvedNotification notification, boolean overwriteAllowed) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationMapper notificationMapper = wrapper.notificationMapper();
            NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
            if (overwriteAllowed) {
                notificationMapper.deleteByKey(notification.notifKey());
            }
            int targetId = targetMapper.nextTargetId();
            List<UUID> members = notification.notifTarget().playerUUIDs();
            if (!members.isEmpty()) {
                targetMapper.insertMembers(targetId, members);
            }
            NotificationEntity entity = new NotificationEntity(
                    notification.notifKey(),
                    notification.notifScheduledTime(),
                    notification.notifExpiryTime(),
                    targetId,
                    notification.notifPayloadType(),
                    notification.notifPayload(),
                    notification.notifPriority()
            );
            notificationMapper.insert(entity);
            wrapper.session().commit();
        }
    }

    @Override
    public @NotNull List<ResolvedNotification> resolveNotifications(@NotNull UUID playerId) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationMapper notificationMapper = wrapper.notificationMapper();
            NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
            List<NotificationEntity> entities = notificationMapper.selectByPlayer(playerId);
            List<ResolvedNotification> resolved = new ArrayList<>(entities.size());
            for (NotificationEntity entity : entities) {
                List<UUID> members = targetMapper.selectPlayerUuids(entity.notifTargetId());
                resolved.add(new ResolvedNotification(
                        entity.notifKey(),
                        entity.notifScheduledTime(),
                        entity.notifExpiryTime(),
                        new NotificationTarget(members),
                        entity.notifPayloadType(),
                        entity.notifPayload(),
                        entity.notifPriority()
                ));
            }
            return resolved;
        }
    }

    @Override
    public void clearNotification(@NotNull String notificationKey) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.notificationMapper().deleteByKey(notificationKey);
            wrapper.session().commit();
        }
    }

    @Override
    public void deleteNotificationTarget(@NotNull String notificationKey, @NotNull UUID playerId) {
        deleteNotificationTargets(notificationKey, List.of(playerId));
    }

    @Override
    public void deleteNotificationTargets(@NotNull String notificationKey, @NotNull Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return;
        }
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationEntity entity = wrapper.notificationMapper().selectByKey(notificationKey);
            if (entity != null) {
                // Removing the last member triggers deletion of the notification itself (DB trigger).
                wrapper.notificationTargetMapper().deleteMembers(entity.notifTargetId(), playerIds);
            }
            wrapper.session().commit();
        }
    }

    @Override
    public void clearNotifications(@NotNull UUID playerId) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.notificationMapper().deleteByPlayer(playerId);
            wrapper.session().commit();
        }
    }

    @Override
    public void clearNotifications(@NotNull String notificationDataType) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.notificationMapper().deleteByPayloadType(notificationDataType);
            wrapper.session().commit();
        }
    }

    @Override
    public void clearExpiredNotifications() {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.notificationMapper().deleteExpired(Instant.now());
            wrapper.session().commit();
        }
    }

    @Override
    public @NotNull NotificationDataTypeRegistry dataTypeRegistry() {
        return this.dataTypeRegistry;
    }
}
