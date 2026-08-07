package io.github.md5sha256.playernotifications.core.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.serialize.JacksonPayloadSerializer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Verifies {@link NotificationDelivery}'s dispatch precedence rule: an explicitly registered
 * {@link NotificationProcessor} wins over a registered {@link NotificationRenderer}; a renderer-only
 * payload dispatches through the rendering path; a payload with neither is retained.
 */
class NotificationDeliveryPrecedenceTest extends AbstractDatabaseTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);
    private static final String TYPE = "precedence-test";
    private static final String STORED = "\"hello\"";

    @Test
    @DisplayName("an explicit processor wins over a registered renderer for the same payload class")
    void explicitProcessorWins() {
        List<String> processorInvocations = new ArrayList<>();
        RecordingSink sink = new RecordingSink("chat", DeliveryResult.DELIVERED);
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);

        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(TYPE, String.class);
        registry.registerSerializer(String.class, new JacksonPayloadSerializer<>(new ObjectMapper(), String.class));
        registry.registerProcessor(String.class, (payload, target) -> {
            processorInvocations.add(payload);
            return NotificationDisposition.MARK_SEEN;
        });
        registry.registerRenderer(String.class, (payload, target) -> new RenderableNotification(
                Component.text("title"), Component.text(payload)));

        NotificationDelivery delivery = new NotificationDelivery(
                database, registry, sinks, target -> Set.of("chat"), Logger.getLogger("test"));
        insert("explicit-wins", DUE, PLAYER);

        delivery.deliver(PLAYER, NOW);

        Assertions.assertEquals(List.of("hello"), processorInvocations);
        Assertions.assertTrue(sink.received.isEmpty(), "the renderer/sink path must not run");
    }

    @Test
    @DisplayName("a renderer-only payload dispatches through the rendering path")
    void rendererOnlyDispatchesThroughRenderingPath() {
        RecordingSink sink = new RecordingSink("chat", DeliveryResult.DELIVERED);
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);

        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(TYPE, String.class);
        registry.registerSerializer(String.class, new JacksonPayloadSerializer<>(new ObjectMapper(), String.class));
        registry.registerRenderer(String.class, (payload, target) -> new RenderableNotification(
                Component.text("title"), Component.text(payload)));

        NotificationDelivery delivery = new NotificationDelivery(
                database, registry, sinks, target -> Set.of("chat"), Logger.getLogger("test"));
        insert("renderer-only", DUE, PLAYER);

        delivery.deliver(PLAYER, NOW);

        Assertions.assertEquals(1, sink.received.size());
        Assertions.assertFalse(exists("renderer-only"), "DELIVERED should consume the notification");
    }

    @Test
    @DisplayName("a payload with neither a processor nor a renderer is retained")
    void neitherRetains() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(TYPE, String.class);
        registry.registerSerializer(String.class, new JacksonPayloadSerializer<>(new ObjectMapper(), String.class));

        NotificationDelivery delivery = new NotificationDelivery(database, registry, Logger.getLogger("test"));
        insert("neither", DUE, PLAYER);

        delivery.deliver(PLAYER, NOW);

        Assertions.assertTrue(exists("neither"));
    }

    @Test
    @DisplayName("rendering path passes the notification's dataType directly to preferredMedia, no category involved")
    void rendererPathPassesDataTypeDirectly() {
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(chat);

        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(TYPE, String.class);
        registry.registerSerializer(String.class, new JacksonPayloadSerializer<>(new ObjectMapper(), String.class));
        registry.registerRenderer(String.class, (payload, target) -> new RenderableNotification(
                Component.text("title"), Component.text(payload)));

        List<String> dataTypesSeen = new ArrayList<>();
        io.github.md5sha256.playernotifications.api.render.NotificationPreferences preferences =
                new io.github.md5sha256.playernotifications.api.render.NotificationPreferences() {
                    @Override
                    public Set<String> preferredMedia(UUID player) {
                        dataTypesSeen.add(null);
                        return Set.of();
                    }

                    @Override
                    public Set<String> preferredMedia(UUID player, String dataType) {
                        dataTypesSeen.add(dataType);
                        return Set.of("chat");
                    }
                };

        NotificationDelivery delivery = new NotificationDelivery(
                database, registry, sinks, preferences, Logger.getLogger("test"));
        insert("category-resolved", DUE, PLAYER);

        delivery.deliver(PLAYER, NOW);

        Assertions.assertEquals(List.of(TYPE), dataTypesSeen);
        Assertions.assertEquals(1, chat.received.size());
    }

    private static void insert(String key, Instant scheduled, UUID... players) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(players));
            wrapper.notificationMapper().insert(new NotificationEntity(
                    key, scheduled, null, targetId, TYPE, STORED, 0));
            wrapper.session().commit();
        }
    }

    private static boolean exists(String key) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByKey(key) != null;
        }
    }

    private static final class RecordingSink implements NotificationSink {

        private final String mediumKey;
        private final DeliveryResult result;
        private final List<RenderableNotification> received = new ArrayList<>();

        private RecordingSink(String mediumKey, DeliveryResult result) {
            this.mediumKey = mediumKey;
            this.result = result;
        }

        @Override
        public String mediumKey() {
            return this.mediumKey;
        }

        @Override
        public DeliveryResult deliver(RenderableNotification notification, UUID target) {
            this.received.add(notification);
            return this.result;
        }
    }
}
