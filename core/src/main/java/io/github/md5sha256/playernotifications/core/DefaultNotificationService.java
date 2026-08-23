package io.github.md5sha256.playernotifications.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializationException;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.InboxNotificationEntity;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import io.github.md5sha256.playernotifications.core.serialize.JacksonPayloadSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    private final NotificationCategoryRegistry categoryRegistry;
    private final ObjectMapper objectMapper;

    public DefaultNotificationService(@NotNull Database database) {
        this(database, new NotificationDataTypeRegistry(), new DefaultNotificationCategoryRegistry());
    }

    public DefaultNotificationService(@NotNull Database database,
                                      @NotNull NotificationDataTypeRegistry dataTypeRegistry) {
        this(database, dataTypeRegistry, new DefaultNotificationCategoryRegistry());
    }

    public DefaultNotificationService(@NotNull Database database,
                                      @NotNull NotificationDataTypeRegistry dataTypeRegistry,
                                      @NotNull NotificationCategoryRegistry categoryRegistry) {
        this.database = database;
        this.dataTypeRegistry = dataTypeRegistry;
        this.categoryRegistry = categoryRegistry;
        // No Jackson modules are registered: the only payload this plugin serializes is String, and
        // records/POJOs are handled by Jackson's native reflection support. If payloads ever need
        // java.time (or similar) support, register that module explicitly here (e.g.
        // registerModule(new JavaTimeModule())) rather than relying on runtime module discovery,
        // which is non-deterministic under Bukkit's per-plugin class loaders.
        this.objectMapper = new ObjectMapper();
        // A default String serializer keeps plain-text payloads working: JSON-quoted on write,
        // unquoted on read, so the notifPayload JSON column stays valid.
        this.dataTypeRegistry.registerSerializer(String.class, jsonSerializer(String.class));
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
    @SuppressWarnings("unchecked")
    public <T> void enqueueNotification(@NotNull TypedNotification<T> notification, boolean overwriteAllowed) {
        PayloadSerializer<T> serializer = (PayloadSerializer<T>) this.dataTypeRegistry
                .getSerializer(notification.notifPayloadType())
                .orElseThrow(() -> new PayloadSerializationException(
                        "No serializer registered for data type " + notification.notifPayloadType()));
        String payload = serializer.serialize(notification.notifPayload());
        enqueueNotification(new ResolvedNotification(
                notification.notifKey(),
                notification.notifScheduledTime(),
                notification.notifExpiryTime(),
                notification.notifTarget(),
                notification.notifPayloadType(),
                payload,
                notification.notifPriority()
        ), overwriteAllowed);
    }

    @Override
    public <T> void registerJsonPayload(@NotNull String dataType, @NotNull Class<T> type,
                                        @NotNull NotificationProcessor<T> processor) {
        this.dataTypeRegistry.registerPayloadMapping(dataType, type);
        this.dataTypeRegistry.registerSerializer(type, jsonSerializer(type));
        this.dataTypeRegistry.registerProcessor(type, processor);
    }

    @Override
    public <T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type,
                                           @NotNull NotificationRenderer<T> renderer) {
        this.dataTypeRegistry.registerPayloadMapping(dataType, type);
        this.dataTypeRegistry.registerSerializer(type, jsonSerializer(type));
        this.dataTypeRegistry.registerRenderer(type, renderer);
    }

    private <T> PayloadSerializer<T> jsonSerializer(@NotNull Class<T> type) {
        return new JacksonPayloadSerializer<>(this.objectMapper, type);
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

    /** The largest page a caller can ask for, whatever they pass. Mirrored by {@code paper.ui.PageBounds}. */
    private static final int MAX_PAGE_SIZE = 20;

    @Override
    public @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize,
                                    @Nullable Collection<String> dataTypes) {
        int size = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        Instant now = Instant.now();
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationMapper mapper = wrapper.notificationMapper();
            int totalEntries = mapper.countInbox(playerId, now, dataTypes);
            int totalPages = Math.max(1, (totalEntries + size - 1) / size);
            int clampedPage = Math.clamp(page, 1, totalPages);
            int unread = mapper.countUnread(playerId, now, dataTypes);
            List<InboxNotificationEntity> rows =
                    mapper.selectInboxPage(playerId, now, size, (clampedPage - 1) * size, dataTypes);
            List<InboxEntry> entries = new ArrayList<>(rows.size());
            for (InboxNotificationEntity row : rows) {
                entries.add(new InboxEntry(
                        row.notifKey(),
                        row.notifScheduledTime(),
                        row.notifExpiryTime(),
                        row.notifPayloadType(),
                        row.notifPayload(),
                        row.notifPriority(),
                        row.seenTime()));
            }
            return new InboxPage(entries, clampedPage, size, totalEntries, unread);
        }
    }

    @Override
    public int unreadCount(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().countUnread(playerId, Instant.now(), dataTypes);
        }
    }

    @Override
    public void markSeen(@NotNull String notificationKey, @NotNull UUID playerId) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationEntity entity = wrapper.notificationMapper().selectByKey(notificationKey);
            if (entity != null) {
                wrapper.notificationTargetMapper().markSeen(entity.notifTargetId(), playerId, Instant.now());
            }
            wrapper.session().commit();
        }
    }

    @Override
    public void markUnread(@NotNull String notificationKey, @NotNull UUID playerId) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationEntity entity = wrapper.notificationMapper().selectByKey(notificationKey);
            if (entity != null) {
                wrapper.notificationTargetMapper().markUnread(entity.notifTargetId(), playerId);
            }
            wrapper.session().commit();
        }
    }

    @Override
    public void markAllSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.notificationTargetMapper().markAllSeenForPlayer(playerId, Instant.now(), dataTypes);
            wrapper.session().commit();
        }
    }

    @Override
    public void dismissSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
            if (dataTypes == null) {
                targetMapper.deleteSeenForPlayer(playerId);
            } else {
                // A filtered DELETE would need a subquery reading Notification, which MariaDB refuses
                // while trg_delete_targetless_notification writes it — the same constraint
                // pruneOrphanedTargets already works around. Select the keys, then delete per key.
                for (String key : targetMapper.selectSeenKeys(playerId, dataTypes)) {
                    deleteNotificationTargetWithin(wrapper, key, playerId);
                }
            }
            wrapper.session().commit();
        }
    }

    private void deleteNotificationTargetWithin(@NotNull SqlSessionWrapper wrapper, @NotNull String notificationKey,
                                                 @NotNull UUID playerId) {
        NotificationEntity entity = wrapper.notificationMapper().selectByKey(notificationKey);
        if (entity != null) {
            // Removing the last member triggers deletion of the notification itself (DB trigger).
            wrapper.notificationTargetMapper().deleteMembers(entity.notifTargetId(), List.of(playerId));
        }
    }

    @Override
    public void pruneOrphanedTargets() {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
            for (int targetId : targetMapper.selectOrphanedTargetIds()) {
                targetMapper.deleteByTargetId(targetId);
            }
            wrapper.session().commit();
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

    @Override
    public @NotNull NotificationCategoryRegistry categoryRegistry() {
        return this.categoryRegistry;
    }
}
