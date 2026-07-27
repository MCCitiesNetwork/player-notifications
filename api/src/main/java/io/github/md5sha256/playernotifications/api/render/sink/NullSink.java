package io.github.md5sha256.playernotifications.api.render.sink;

import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The sink backing a player's explicit mute. Discards the notification and reports
 * {@link DeliveryResult#DELIVERED}.
 *
 * <p>A player who deselects every medium in the preferences dialog is stored as the single preferred
 * medium {@code "none"} rather than as zero rows, because zero rows already means "has expressed no
 * preference" and falls back to {@code default-media}. Storing an explicit medium keeps the mute out of
 * the delivery path's special cases: {@code RenderingProcessor} resolves it like any other sink.
 *
 * <p>Reporting {@code DELIVERED} is deliberate. It makes the fan-out fold to
 * {@link io.github.md5sha256.playernotifications.api.processor.NotificationDisposition#DELETE}, so a
 * muted player's notifications are consumed instead of accumulating in the database until they expire.
 * A mute means "do not tell me", not "queue this up for later".
 *
 * <p>Not selectable in the preferences dialog: the mute is expressed by checking nothing, so listing it
 * as a checkbox would offer two ways to say the same thing.
 */
public final class NullSink implements NotificationSink {

    public static final String MEDIUM_KEY = "none";

    private static final Component DISPLAY_NAME = Component.text("None");
    private static final Component DESCRIPTION =
            Component.text("Notifications are discarded without being shown.");

    @Override
    public @NotNull String mediumKey() {
        return MEDIUM_KEY;
    }

    @Override
    public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification, @NotNull UUID target) {
        return DeliveryResult.DELIVERED;
    }

    @Override
    public @NotNull Component displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public @NotNull Component description() {
        return DESCRIPTION;
    }
}
