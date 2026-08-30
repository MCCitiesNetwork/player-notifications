package io.github.md5sha256.playernotifications.paper.broadcast;

import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.localisation.TestMessages;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link BroadcastRenderer} — the renderer that makes a {@code --persistent} broadcast readable in the
 * inbox. Mirrors {@code MailRendererTest}, including the legacy section-sign case: MiniMessage
 * <em>throws</em> on a {@code §} code, and a broadcast stored before some future change could contain
 * one, so the fallback to literal text is load-bearing rather than defensive.
 */
class BroadcastRendererTest {

    private static final UUID TARGET = UUID.randomUUID();

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void rendersTheBodyAsMiniMessage() {
        BroadcastRenderer renderer = new BroadcastRenderer(TestMessages.shipped());

        RenderableNotification rendered =
                renderer.render(new BroadcastPayload("<red>server restarting</red>"), TARGET);

        assertEquals("server restarting", plain(rendered.body()));
        assertEquals(NamedTextColor.RED, rendered.body().color());
    }

    @Test
    void titleComesFromTheMessageContainer() {
        BroadcastRenderer renderer = new BroadcastRenderer(TestMessages.shipped());

        RenderableNotification rendered = renderer.render(new BroadcastPayload("hi"), TARGET);

        assertEquals("Broadcast", plain(rendered.title()));
    }

    @Test
    void aLegacySectionSignBodyFallsBackToLiteralTextRatherThanThrowing() {
        BroadcastRenderer renderer = new BroadcastRenderer(TestMessages.shipped());

        RenderableNotification rendered = renderer.render(new BroadcastPayload("§cred text"), TARGET);

        assertNotNull(rendered);
        assertEquals("§cred text", plain(rendered.body()));
    }

    @Test
    void rendersTheSameForEveryTarget() {
        BroadcastRenderer renderer = new BroadcastRenderer(TestMessages.shipped());

        RenderableNotification first = renderer.render(new BroadcastPayload("hi"), UUID.randomUUID());
        RenderableNotification second = renderer.render(new BroadcastPayload("hi"), UUID.randomUUID());

        assertEquals(plain(first.body()), plain(second.body()));
        assertEquals(plain(first.title()), plain(second.title()));
    }
}
