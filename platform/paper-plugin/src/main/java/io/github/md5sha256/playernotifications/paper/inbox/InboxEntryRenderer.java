package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Renders a stored {@link InboxEntry} for display, resolving payload class, {@link PayloadSerializer}
 * and {@link NotificationRenderer} exactly as {@code NotificationDelivery} does on the delivery path.
 *
 * <p>The inbox renders on <em>read</em>, not on write, so a renderer registered or changed today
 * affects entries stored long before it. Any of those three lookups being absent, or the decode
 * throwing, yields a placeholder naming the data type rather than hiding the entry: a hidden entry
 * would still be counted in {@code totalEntries} and read as a bug from the player's side.
 */
public final class InboxEntryRenderer {

    private final NotificationDataTypeRegistry registry;
    private final Logger logger;

    public InboxEntryRenderer(@NotNull NotificationDataTypeRegistry registry, @NotNull Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    public @NotNull RenderableNotification render(@NotNull InboxEntry entry, @NotNull UUID viewer) {
        String dataType = entry.notifPayloadType();

        Optional<Class<?>> payloadClass = this.registry.resolvePayloadClass(dataType);
        if (payloadClass.isEmpty()) {
            this.logger.fine(() -> "No payload mapping for data type '" + dataType + "'; rendering placeholder");
            return placeholder(dataType);
        }

        Optional<? extends NotificationRenderer<?>> renderer = this.registry.getRenderer(dataType);
        if (renderer.isEmpty()) {
            this.logger.fine(() -> "No renderer for data type '" + dataType + "'; rendering placeholder");
            return placeholder(dataType);
        }

        Optional<? extends PayloadSerializer<?>> serializer = this.registry.getSerializer(payloadClass.get());
        if (serializer.isEmpty()) {
            this.logger.warning("No serializer registered for payload type " + payloadClass.get().getName()
                    + "; rendering placeholder");
            return placeholder(dataType);
        }

        Object payload;
        try {
            payload = serializer.get().deserialize(entry.notifPayload());
        } catch (RuntimeException e) {
            this.logger.warning("Failed to deserialize inbox payload of type " + dataType
                    + "; rendering placeholder: " + e.getMessage());
            return placeholder(dataType);
        }

        try {
            return castRenderer(renderer.get()).render(payload, viewer);
        } catch (RuntimeException e) {
            this.logger.warning("Renderer for data type " + dataType + " threw; rendering placeholder: "
                    + e.getMessage());
            return placeholder(dataType);
        }
    }

    @SuppressWarnings("unchecked")
    private static @NotNull NotificationRenderer<Object> castRenderer(@NotNull NotificationRenderer<?> renderer) {
        return (NotificationRenderer<Object>) renderer;
    }

    private static @NotNull RenderableNotification placeholder(@NotNull String dataType) {
        return new RenderableNotification(
                Component.text("Unreadable notification"),
                Component.text("This notification's type (" + dataType
                        + ") cannot be displayed. It may come from a module that is no longer installed."));
    }
}
