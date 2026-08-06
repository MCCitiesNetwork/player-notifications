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

    /** Stands in for the server's offline player cache, which needs a live server. */
    private static final TestNotificationRenderer RENDERER =
            new TestNotificationRenderer(uuid -> TARGET.equals(uuid) ? "Steve" : null);

    private static final TestNotificationRenderer NO_NAMES =
            new TestNotificationRenderer(uuid -> null);

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("the title marks the notification as a test, since it lands in the same inbox as real ones")
    void rendersTitle() {
        RenderableNotification rendered = RENDERER.render(new TestNotificationPayload("ping"), TARGET);

        Assertions.assertEquals("[Test] Test Notification", plain(rendered.title()));
    }

    @Test
    @DisplayName("the body explains what a test notification is and why it arrived")
    void bodyExplainsItself() {
        RenderableNotification rendered = RENDERER.render(new TestNotificationPayload("ping"), TARGET);

        String body = plain(rendered.body());
        Assertions.assertTrue(body.contains("test notification"), "body was: " + body);
        Assertions.assertTrue(body.contains("/notifications test"), "body was: " + body);
    }

    @Test
    @DisplayName("body carries the message and names the target")
    void bodyCarriesMessageAndTarget() {
        RenderableNotification rendered = RENDERER.render(new TestNotificationPayload("ping"), TARGET);

        String body = plain(rendered.body());
        Assertions.assertTrue(body.contains("ping"), "body was: " + body);
        Assertions.assertTrue(body.contains("Steve"), "body was: " + body);
        Assertions.assertFalse(body.contains(TARGET.toString()),
                "a raw uuid reaches Discord and mail verbatim; body was: " + body);
    }

    @Test
    @DisplayName("an unknown target falls back to its uuid rather than rendering 'null'")
    void unknownTargetFallsBackToUuid() {
        RenderableNotification rendered = NO_NAMES.render(new TestNotificationPayload("ping"), TARGET);

        Assertions.assertTrue(plain(rendered.body()).contains(TARGET.toString()));
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

        Assertions.assertTrue(plain(rendered.body()).contains("Steve"));
    }
}
