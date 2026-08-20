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

    /**
     * Decodes an entry's stored payload, or empty when the data type has no payload mapping, no
     * serializer, or the stored JSON does not decode. Exposed because a caller may need a field the
     * rendered form does not carry — {@code MailChatRow} needs a mail's sender name, which exists in
     * the payload but only as part of a display string in the rendered title.
     */
    public @NotNull Optional<Object> decodePayload(@NotNull InboxEntry entry) {
        String dataType = entry.notifPayloadType();

        Optional<Class<?>> payloadClass = this.registry.resolvePayloadClass(dataType);
        if (payloadClass.isEmpty()) {
            this.logger.fine(() -> "No payload mapping for data type '" + dataType + "'");
            return Optional.empty();
        }

        Optional<? extends PayloadSerializer<?>> serializer = this.registry.getSerializer(payloadClass.get());
        if (serializer.isEmpty()) {
            this.logger.warning("No serializer registered for payload type " + payloadClass.get().getName());
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(serializer.get().deserialize(entry.notifPayload()));
        } catch (RuntimeException e) {
            this.logger.warning("Failed to deserialize inbox payload of type " + dataType
                    + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public @NotNull RenderableNotification render(@NotNull InboxEntry entry, @NotNull UUID viewer) {
        String dataType = entry.notifPayloadType();

        Optional<? extends NotificationRenderer<?>> renderer = this.registry.getRenderer(dataType);
        if (renderer.isEmpty()) {
            this.logger.fine(() -> "No renderer for data type '" + dataType + "'; rendering placeholder");
            return placeholder(dataType);
        }

        Optional<Object> payload = decodePayload(entry);
        if (payload.isEmpty()) {
            // decodePayload has already logged which of the three lookups failed.
            return placeholder(dataType);
        }

        try {
            return castRenderer(renderer.get()).render(payload.get(), viewer);
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
