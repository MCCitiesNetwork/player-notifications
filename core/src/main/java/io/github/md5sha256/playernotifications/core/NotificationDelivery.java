package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.api.render.RenderingProcessor;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.DueNotificationEntity;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers a player's due, unseen notifications through the processors registered in the
 * {@link NotificationDataTypeRegistry}, and stamps each target whose processor reports the
 * notification {@link NotificationDisposition#MARK_SEEN seen}.
 *
 * <p>A notification is processed once per target: {@link #deliver(UUID)} resolves the notifications
 * currently due and still unseen for one player, looks up the processor for each notification's
 * payload type, invokes it for that single player, and — when the processor returns
 * {@code MARK_SEEN} — writes {@code seenTime} on that player's target row. The notification itself is
 * <em>not</em> deleted: it stays readable in the player's inbox until they dismiss it or it expires.
 * Because the due query filters on {@code seenTime IS NULL}, a seen notification is never pushed to
 * the player a second time.
 *
 * <p>Processors are invoked outside any open database transaction, so their side effects (which may
 * marshal onto another thread) do not hold database resources.
 *
 * <p>Before any of that, {@link #deliver(UUID, Instant)} checks whether the target is globally
 * muted ({@link NotificationPreferences#isMuted(UUID)}); a muted player's due notifications are left
 * untouched entirely — not decoded, not dispatched, not marked seen — so the mute gate applies
 * uniformly to the renderer path and to bespoke processors alike.
 *
 * <p>Dispatch precedence: an explicitly registered {@link NotificationProcessor} always wins (so
 * bespoke processors keep working unchanged, at the cost of bypassing preferences). Otherwise, if a
 * {@link NotificationRenderer} is registered for the payload class, the notification is dispatched
 * through a framework-supplied {@link RenderingProcessor}, which resolves preferred media for the
 * notification's {@code notifPayloadType} directly and fans it out to the target's preferred media.
 * Otherwise the notification is logged and retained.
 */
public class NotificationDelivery {

    private final Database database;
    private final NotificationDataTypeRegistry registry;
    private final NotificationSinkRegistry sinkRegistry;
    private final NotificationPreferences preferences;
    private final Logger logger;
    private final int batchSize;

    /** The chunk size when none is given; see {@code delivery-batch-size} in settings.yml. */
    public static final int DEFAULT_BATCH_SIZE = 200;

    /**
     * Constructs a delivery loop with no rendering path: only explicitly registered
     * {@link NotificationProcessor}s are dispatched. A payload with only a {@link NotificationRenderer}
     * registered is retained, exactly as one with neither.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @NotNull Logger logger) {
        this(database, registry, null, null, logger);
    }

    /**
     * Constructs a delivery loop with the rendering path enabled: a payload with a registered
     * {@link NotificationRenderer} (and no explicit processor) is dispatched through a
     * {@link RenderingProcessor} built from the given sink registry and preferences, resolving preferred
     * media directly against the notification's {@code notifPayloadType} via
     * {@link NotificationPreferences#preferredMedia(UUID, String)}.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @Nullable NotificationSinkRegistry sinkRegistry,
                                @Nullable NotificationPreferences preferences,
                                @NotNull Logger logger) {
        this(database, registry, sinkRegistry, preferences, logger, DEFAULT_BATCH_SIZE);
    }

    /**
     * As the five-argument constructor, with the number of targets {@link #deliver(Collection)} handles
     * per chunk: one due query and one seen-marking write each.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @Nullable NotificationSinkRegistry sinkRegistry,
                                @Nullable NotificationPreferences preferences,
                                @NotNull Logger logger,
                                int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        this.batchSize = batchSize;
        this.database = database;
        this.registry = registry;
        this.sinkRegistry = sinkRegistry;
        this.preferences = preferences;
        this.logger = logger;
    }

    /**
     * Delivers every notification currently due for the given player as of now.
     */
    public void deliver(@NotNull UUID target) {
        deliver(target, Instant.now());
    }

    /**
     * Delivers every due notification for each of {@code targets}, in chunks of the configured batch
     * size: per chunk, one query reads every target's due rows, each target is dispatched as
     * {@link #deliver(UUID, Instant)} would, and one write marks the chunk's seen rows. Duplicates are
     * collapsed. A target whose pass throws is logged and reported failed; a chunk whose query or write
     * throws reports every target in it failed, and the next chunk still runs.
     */
    public @NotNull BatchDeliveryReport deliver(@NotNull Collection<UUID> targets) {
        return deliver(targets, Instant.now());
    }

    /** {@link #deliver(Collection)} as of {@code now}. */
    public @NotNull BatchDeliveryReport deliver(@NotNull Collection<UUID> targets, @NotNull Instant now) {
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(targets));
        int attempted = 0;
        Set<UUID> failed = new LinkedHashSet<>();
        for (int start = 0; start < distinct.size(); start += this.batchSize) {
            List<UUID> chunk = distinct.subList(start, Math.min(start + this.batchSize, distinct.size()));
            Set<UUID> chunkFailed = new LinkedHashSet<>();
            try {
                attempted += deliverChunk(chunk, now, chunkFailed);
                failed.addAll(chunkFailed);
            } catch (RuntimeException e) {
                // The due query or the seen-marking write failed: nothing in this chunk is known to have
                // been recorded, so all of it counts as failed. Stored notifications stay unread.
                this.logger.log(Level.WARNING, "Batched delivery failed for a chunk of " + chunk.size()
                        + " players; they remain unread for their next join", e);
                failed.addAll(chunk);
            }
        }
        return new BatchDeliveryReport(attempted, failed);
    }

    /**
     * One chunk: one due query, per-player dispatch outside any session, one seen-marking write.
     *
     * @return how many of the chunk's players were attempted without throwing
     */
    private int deliverChunk(@NotNull List<UUID> chunk, @NotNull Instant now, @NotNull Set<UUID> failed) {
        Map<UUID, List<NotificationEntity>> dueByPlayer = new HashMap<>();
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            for (DueNotificationEntity row : wrapper.notificationMapper().selectDueByPlayers(chunk, now)) {
                dueByPlayer.computeIfAbsent(row.playerUuid(), player -> new ArrayList<>())
                        .add(row.notification());
            }
        }

        int attempted = 0;
        List<DueNotificationEntity> toMark = new ArrayList<>();
        for (UUID target : chunk) {
            try {
                if (this.preferences != null && this.preferences.isMuted(target)) {
                    this.logger.fine(() -> "Player " + target + " is muted; skipping delivery");
                } else {
                    for (NotificationEntity notification : dueByPlayer.getOrDefault(target, List.of())) {
                        if (dispatch(notification, target) == NotificationDisposition.MARK_SEEN) {
                            toMark.add(seenRow(target, notification));
                        }
                    }
                }
                attempted++;
            } catch (RuntimeException e) {
                this.logger.log(Level.WARNING, "Delivery to " + target
                        + " failed; its notifications remain unread for the next join", e);
                failed.add(target);
            }
        }

        if (!toMark.isEmpty()) {
            try (SqlSessionWrapper wrapper = this.database.openSession()) {
                NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
                for (DueNotificationEntity row : toMark) {
                    targetMapper.markSeen(row.notifTargetId(), row.playerUuid(), now);
                }
                wrapper.session().commit();
            }
        }
        return attempted;
    }

    private static @NotNull DueNotificationEntity seenRow(@NotNull UUID player,
                                                         @NotNull NotificationEntity notification) {
        return new DueNotificationEntity(player, notification.notifKey(), notification.notifScheduledTime(),
                notification.notifExpiryTime(), notification.notifTargetId(), notification.notifPayloadType(),
                notification.notifPayload(), notification.notifPriority());
    }

    /**
     * Delivers every notification due for the given player as of {@code now}.
     */
    public void deliver(@NotNull UUID target, @NotNull Instant now) {
        if (this.preferences != null && this.preferences.isMuted(target)) {
            this.logger.fine(() -> "Player " + target + " is muted; skipping delivery");
            return;
        }

        List<NotificationEntity> due;
        try (SqlSessionWrapper wrapper = database.openSession()) {
            due = wrapper.notificationMapper().selectDueByPlayer(target, now);
        }

        List<NotificationEntity> toMark = new ArrayList<>();
        for (NotificationEntity notification : due) {
            if (dispatch(notification, target) == NotificationDisposition.MARK_SEEN) {
                toMark.add(notification);
            }
        }

        if (toMark.isEmpty()) {
            return;
        }
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
            for (NotificationEntity notification : toMark) {
                targetMapper.markSeen(notification.notifTargetId(), target, now);
            }
            wrapper.session().commit();
        }
    }

    /**
     * Pushes a notification that is never stored to one player, through the media they prefer for
     * {@code dataType}. A muted player gets nothing, a silence is dropped, an unregistered medium is
     * skipped, and a throwing sink is logged without stopping the others — exactly as for a stored
     * notification, because it goes through the same {@link RenderingProcessor}. Nothing is marked seen — there
     * is no row to mark.
     *
     * @throws IllegalStateException if this loop was built without sinks and preferences
     */
    public void deliverTransient(@NotNull RenderableNotification notification, @NotNull String dataType,
                                 @NotNull UUID target) {
        if (this.sinkRegistry == null || this.preferences == null) {
            throw new IllegalStateException("Transient delivery needs a sink registry and preferences");
        }
        if (this.preferences.isMuted(target)) {
            this.logger.fine(() -> "Player " + target + " is muted; skipping transient delivery");
            return;
        }
        // The stored path's fan-out, reused with an identity renderer: one place decides how a
        // rendered notification reaches a player's media. Its disposition is meaningless here.
        new RenderingProcessor<RenderableNotification>((rendered, player) -> rendered, this.sinkRegistry,
                this.preferences, dataType, this.logger).receiveNotification(notification, target);
    }

    private @NotNull NotificationDisposition dispatch(@NotNull NotificationEntity notification,
                                                      @NotNull UUID target) {
        Optional<Class<?>> payloadClass = registry.resolvePayloadClass(notification.notifPayloadType());
        if (payloadClass.isEmpty()) {
            logger.fine(() -> "No payload mapping for data type '" + notification.notifPayloadType()
                    + "'; retaining " + notification.notifKey());
            return NotificationDisposition.RETAIN;
        }

        Optional<? extends NotificationProcessor<?>> processor =
                registry.getProcessor(notification.notifPayloadType());
        if (processor.isPresent()) {
            Object payload = decodePayload(notification.notifPayload(), payloadClass.get());
            if (payload == null) {
                return NotificationDisposition.RETAIN;
            }
            return invoke(processor.get(), payload, target);
        }

        Optional<? extends NotificationRenderer<?>> renderer =
                registry.getRenderer(notification.notifPayloadType());
        if (renderer.isPresent() && this.sinkRegistry != null && this.preferences != null) {
            Object payload = decodePayload(notification.notifPayload(), payloadClass.get());
            if (payload == null) {
                return NotificationDisposition.RETAIN;
            }
            NotificationProcessor<?> renderingProcessor =
                    new RenderingProcessor<>(castRenderer(renderer.get()), this.sinkRegistry,
                            this.preferences, notification.notifPayloadType(), this.logger);
            return invoke(renderingProcessor, payload, target);
        }

        logger.fine(() -> "No processor or renderer for data type '" + notification.notifPayloadType()
                + "'; retaining " + notification.notifKey());
        return NotificationDisposition.RETAIN;
    }

    @SuppressWarnings("unchecked")
    private @NotNull NotificationRenderer<Object> castRenderer(@NotNull NotificationRenderer<?> renderer) {
        return (NotificationRenderer<Object>) renderer;
    }

    private @Nullable Object decodePayload(@NotNull String rawPayload, @NotNull Class<?> payloadClass) {
        Optional<? extends PayloadSerializer<?>> serializer = registry.getSerializer(payloadClass);
        if (serializer.isEmpty()) {
            logger.warning("No serializer registered for payload type " + payloadClass.getName()
                    + "; retaining notification");
            return null;
        }
        try {
            return serializer.get().deserialize(rawPayload);
        } catch (RuntimeException e) {
            logger.warning("Failed to deserialize payload of type " + payloadClass.getName()
                    + "; retaining notification: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private @NotNull NotificationDisposition invoke(@NotNull NotificationProcessor<?> processor,
                                                    @NotNull Object payload,
                                                    @NotNull UUID target) {
        return ((NotificationProcessor<Object>) processor).receiveNotification(payload, target);
    }
}
