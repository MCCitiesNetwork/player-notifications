package io.github.md5sha256.playernotifications.paper.diagnostic;

import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Renders a {@link TestNotificationPayload} into the medium-neutral form every sink consumes.
 *
 * <p>The message is deliberately bold: a plain unstyled body would exercise nothing in a sink's style
 * handling, and the point of this payload is to prove a sink works end to end. It uses no click events,
 * matching the {@link RenderableNotification} contract that sinks may drop them.
 */
public final class TestNotificationRenderer implements NotificationRenderer<TestNotificationPayload> {

    private static final Component TITLE =
            Component.text("PlayerNotifications test", NamedTextColor.GOLD);

    @Override
    public @NotNull RenderableNotification render(@NotNull TestNotificationPayload payload,
                                                  @NotNull UUID target) {
        Component body = Component.empty()
                .append(Component.text(payload.message())
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(Component.text("target: " + target, NamedTextColor.GRAY));
        return new RenderableNotification(TITLE, body);
    }
}
