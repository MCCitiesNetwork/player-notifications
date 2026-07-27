package io.github.md5sha256.playernotifications.api.render;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Converts a decoded notification payload into the medium-neutral {@link RenderableNotification} form.
 * Written once by the payload author, then reused by every {@link NotificationSink} the recipient has
 * selected.
 *
 * @param <T> the payload type this renderer accepts
 */
@FunctionalInterface
public interface NotificationRenderer<T> {

    /**
     * Renders the given payload for the given target. The target is provided so a payload can
     * personalise its rendering per recipient; delivery is already per-target, so this costs nothing.
     */
    @NotNull RenderableNotification render(@NotNull T payload, @NotNull UUID target);

}
