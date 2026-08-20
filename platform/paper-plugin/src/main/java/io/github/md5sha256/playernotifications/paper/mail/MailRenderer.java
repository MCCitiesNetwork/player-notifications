package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * <p>The body <b>is</b> parsed as MiniMessage, with the full standard tag set — the stored string was
 * produced by {@code MailFormatting.sanitize} at send time, which registered only the tags the sender
 * held a permission for and escaped everything else, so by the time it reaches here it is an
 * already-authorised document and there is no trust decision left to make. Parsing it with a reduced
 * tag set would instead silently change how an old mail reads.
 *
 * <p>Parsing is nonetheless guarded: MiniMessage <i>throws</i> on a legacy section-sign code, and a
 * mail stored before send-time sanitising existed — or one whose sanitising fell back to literal text —
 * can contain one. A parse failure falls back to the message as literal text, which is exactly what
 * such a mail was before, so an old mail can never fail to render.
 *
 * <p>The title is built with {@link Component#text(String)} only. A player name is not a formatting
 * document, and parsing it would be a way to smuggle tags past the message's permission gate.
 */
public final class MailRenderer implements NotificationRenderer<MailPayload> {

    @Override
    public @NotNull RenderableNotification render(@NotNull MailPayload payload, @NotNull UUID target) {
        Component title = Component.text("Mail from " + payload.senderName());
        Component body;
        try {
            body = MiniMessage.miniMessage().deserialize(payload.message());
        } catch (RuntimeException ex) {
            body = Component.text(payload.message());
        }
        return new RenderableNotification(title, body);
    }
}
