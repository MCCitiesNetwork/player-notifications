package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializationException;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

class InboxEntryRendererTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final Logger LOGGER = Logger.getLogger("test");

    private static InboxEntry entry(String dataType, String payload) {
        return new InboxEntry("k", Instant.now(), null, dataType, payload, 0, null);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("a data type with a registered renderer and serializer renders through them")
    void rendersThroughTheRegisteredRenderer() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping("greeting", String.class);
        registry.registerSerializer(String.class, new UnquotingSerializer());
        registry.registerRenderer(String.class, (payload, target) -> new RenderableNotification(
                Component.text("Greeting"), Component.text(payload)));

        RenderableNotification rendered =
                new InboxEntryRenderer(registry, LOGGER).render(entry("greeting", "\"hello\""), VIEWER);

        Assertions.assertEquals("Greeting", plain(rendered.title()));
        Assertions.assertEquals("hello", plain(rendered.body()));
    }

    @Test
    @DisplayName("decodePayload returns the decoded payload, so a caller can read fields off it")
    void decodePayloadYieldsThePayload() {
        // The seam MailChatRow uses to reach a mail's sender, which no rendered form carries.
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping("greeting", String.class);
        registry.registerSerializer(String.class, new UnquotingSerializer());

        Assertions.assertEquals(java.util.Optional.of("hello"),
                new InboxEntryRenderer(registry, LOGGER).decodePayload(entry("greeting", "\"hello\"")));
    }

    @Test
    @DisplayName("decodePayload is empty when the type is unmapped, unserializable, or fails to decode")
    void decodePayloadIsEmptyRatherThanThrowing() {
        NotificationDataTypeRegistry unmapped = new NotificationDataTypeRegistry();
        Assertions.assertEquals(java.util.Optional.empty(),
                new InboxEntryRenderer(unmapped, LOGGER).decodePayload(entry("ghost-module", "{}")));

        NotificationDataTypeRegistry noSerializer = new NotificationDataTypeRegistry();
        noSerializer.registerPayloadMapping("greeting", StringBuilder.class);
        Assertions.assertEquals(java.util.Optional.empty(),
                new InboxEntryRenderer(noSerializer, LOGGER).decodePayload(entry("greeting", "{}")));

        NotificationDataTypeRegistry throwing = new NotificationDataTypeRegistry();
        throwing.registerPayloadMapping("broken", String.class);
        throwing.registerSerializer(String.class, new ThrowingSerializer());
        Assertions.assertEquals(java.util.Optional.empty(),
                new InboxEntryRenderer(throwing, LOGGER).decodePayload(entry("broken", "not json")));
    }

    @Test
    @DisplayName("a data type with no registered payload mapping renders a placeholder naming the type")
    void unregisteredTypeRendersAPlaceholder() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();

        RenderableNotification rendered =
                new InboxEntryRenderer(registry, LOGGER).render(entry("ghost-module", "{}"), VIEWER);

        Assertions.assertEquals("Unreadable notification", plain(rendered.title()));
        Assertions.assertTrue(plain(rendered.body()).contains("ghost-module"),
                "the placeholder must name the data type it could not display");
    }

    @Test
    @DisplayName("a payload whose serializer throws renders the placeholder rather than propagating")
    void undecodablePayloadRendersAPlaceholder() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping("broken", String.class);
        registry.registerSerializer(String.class, new ThrowingSerializer());
        registry.registerRenderer(String.class, (payload, target) -> new RenderableNotification(
                Component.text("never"), Component.text("never")));

        RenderableNotification rendered =
                new InboxEntryRenderer(registry, LOGGER).render(entry("broken", "not json"), VIEWER);

        Assertions.assertEquals("Unreadable notification", plain(rendered.title()));
        Assertions.assertTrue(plain(rendered.body()).contains("broken"));
    }

    /** Strips the JSON quotes the notifPayload column stores a String payload with. */
    private static final class UnquotingSerializer implements PayloadSerializer<String> {

        @Override
        public String serialize(String payload) {
            return "\"" + payload + "\"";
        }

        @Override
        public String deserialize(String raw) {
            return raw.substring(1, raw.length() - 1);
        }
    }

    private static final class ThrowingSerializer implements PayloadSerializer<String> {

        @Override
        public String serialize(String payload) {
            return payload;
        }

        @Override
        public String deserialize(String raw) {
            throw new PayloadSerializationException("boom");
        }
    }
}
