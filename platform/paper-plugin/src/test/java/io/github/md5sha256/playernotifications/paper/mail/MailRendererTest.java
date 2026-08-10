package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Covers {@link MailRenderer}: the title names the sender, the body is the message verbatim, and
 * neither is interpretable as formatting since the message is player-supplied.
 */
class MailRendererTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("title names the sender, body is the message")
    void titleAndBody() {
        UUID sender = UUID.randomUUID();
        MailPayload payload = new MailPayload(sender, "Steve", "hello there");

        RenderableNotification rendered = new MailRenderer().render(payload, UUID.randomUUID());

        Assertions.assertEquals("Mail from Steve", PLAIN.serialize(rendered.title()));
        Assertions.assertEquals("hello there", PLAIN.serialize(rendered.body()));
    }

    @Test
    @DisplayName("a player-supplied message renders formatting characters literally")
    void formattingCharactersAreLiteral() {
        UUID sender = UUID.randomUUID();
        MailPayload payload = new MailPayload(sender, "Steve", "§c&cnot red");

        RenderableNotification rendered = new MailRenderer().render(payload, UUID.randomUUID());

        Assertions.assertEquals("§c&cnot red", PLAIN.serialize(rendered.body()));
    }

    @Test
    @DisplayName("the renderer ignores the target: two different targets render identically")
    void targetIsIgnored() {
        MailPayload payload = new MailPayload(UUID.randomUUID(), "Steve", "hello there");
        MailRenderer renderer = new MailRenderer();

        RenderableNotification first = renderer.render(payload, UUID.randomUUID());
        RenderableNotification second = renderer.render(payload, UUID.randomUUID());

        Assertions.assertEquals(PLAIN.serialize(first.title()), PLAIN.serialize(second.title()));
        Assertions.assertEquals(PLAIN.serialize(first.body()), PLAIN.serialize(second.body()));
    }
}
