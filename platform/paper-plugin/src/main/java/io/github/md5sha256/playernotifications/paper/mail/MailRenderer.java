package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Renders a {@link MailPayload} for display in the inbox.
 *
 * <p>Only the inbox read path ever calls this: {@code MailPayload}'s data type is bound to an explicit
 * {@code NotificationProcessor} that always retains, which wins dispatch precedence and means mail is
 * never pushed through any sink. A renderer is registered all the same, since without one the inbox
 * would show the "unrenderable payload" placeholder instead of the mail.
 *
 * <p>Both components are built with {@link Component#text(String)} only — never MiniMessage or a
 * legacy serializer — because the sender and message are player-supplied and must not be interpretable
 * as formatting.
 */
public final class MailRenderer implements NotificationRenderer<MailPayload> {

    @Override
    public @NotNull RenderableNotification render(@NotNull MailPayload payload, @NotNull UUID target) {
        Component title = Component.text("Mail from " + payload.senderName());
        Component body = Component.text(payload.message());
        return new RenderableNotification(title, body);
    }
}
