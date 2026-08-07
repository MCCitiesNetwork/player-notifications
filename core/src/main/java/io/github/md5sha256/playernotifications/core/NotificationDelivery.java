package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderingProcessor;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Delivers a player's due notifications through the processors registered in the
 * {@link NotificationDataTypeRegistry}, and prunes each target whose processor flags the
 * notification for {@link NotificationDisposition#DELETE deletion}.
 *
 * <p>A notification is processed once per target: {@link #deliver(UUID)} resolves the notifications
 * currently due for one player, looks up the processor for each notification's payload type, invokes
 * it for that single player, and — when the processor returns {@code DELETE} — removes the player
 * from the notification's target group. Removing the last target deletes the notification itself
 * (enforced by a database trigger).
 *
 * <p>Processors are invoked outside any open database transaction, so their side effects (which may
 * marshal onto another thread) do not hold database resources.
 *
 * <p>Dispatch precedence: an explicitly registered {@link NotificationProcessor} always wins (so
 * {@code EssentialsMailProcessor} and other bespoke processors keep working unchanged). Otherwise, if a
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
     * Delivers every notification due for the given player as of {@code now}.
     */
    public void deliver(@NotNull UUID target, @NotNull Instant now) {
        List<NotificationEntity> due;
        try (SqlSessionWrapper wrapper = database.openSession()) {
            due = wrapper.notificationMapper().selectDueByPlayer(target, now);
        }

        List<NotificationEntity> toPrune = new ArrayList<>();
        for (NotificationEntity notification : due) {
            if (dispatch(notification, target) == NotificationDisposition.MARK_SEEN) {
                toPrune.add(notification);
            }
        }

        if (toPrune.isEmpty()) {
            return;
        }
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
            for (NotificationEntity notification : toPrune) {
                targetMapper.deleteMembers(notification.notifTargetId(), List.of(target));
            }
            wrapper.session().commit();
        }
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
