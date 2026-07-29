package io.github.md5sha256.playernotifications.paper.diagnostic;

import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class TestNotificationRendererTest {

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    private static final TestNotificationRenderer RENDERER = new TestNotificationRenderer();

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("renders a fixed title identifying the notification as a plugin test")
    void rendersTitle() {
        RenderableNotification rendered = RENDERER.render(new TestNotificationPayload("ping"), TARGET);

        Assertions.assertEquals("PlayerNotifications test", plain(rendered.title()));
    }

    @Test
    @DisplayName("body carries the message and the target uuid")
    void bodyCarriesMessageAndTarget() {
        RenderableNotification rendered = RENDERER.render(new TestNotificationPayload("ping"), TARGET);

        String body = plain(rendered.body());
        Assertions.assertTrue(body.contains("ping"), "body was: " + body);
        Assertions.assertTrue(body.contains(TARGET.toString()), "body was: " + body);
    }

    @Test
    @DisplayName("the message is bold, so sinks that map styles have something to map")
    void messageIsBold() {
        RenderableNotification rendered = RENDERER.render(new TestNotificationPayload("ping"), TARGET);

        Component messageChild = rendered.body().children().stream()
                .filter(child -> plain(child).contains("ping"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no child carried the message"));

        Assertions.assertEquals(TextDecoration.State.TRUE,
                messageChild.decoration(TextDecoration.BOLD));
    }

    @Test
    @DisplayName("an empty message still renders rather than throwing")
    void emptyMessageRenders() {
        RenderableNotification rendered = RENDERER.render(new TestNotificationPayload(""), TARGET);

        Assertions.assertTrue(plain(rendered.body()).contains(TARGET.toString()));
    }
}
