package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
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
 * End-to-end cover for the renderer/sink path: a payload registered through
 * {@code registerJsonRenderable} must be enqueued, resolved as due, decoded, rendered once, and fanned
 * out to the recipient's preferred media — with no processor registered anywhere.
 */
class RenderedDeliveryTest extends AbstractDatabaseTest {

    private record Ping(String message) {
    }

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    /** A sink that records what it was handed and reports whatever result the test asked for. */
    private static final class RecordingSink implements NotificationSink {

        private final String mediumKey;
        private final DeliveryResult result;
        private final List<RenderableNotification> received = new ArrayList<>();

        RecordingSink(String mediumKey, DeliveryResult result) {
            this.mediumKey = mediumKey;
            this.result = result;
        }

        @Override
        public @NotNull String mediumKey() {
            return this.mediumKey;
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            this.received.add(notification);
            return this.result;
        }
    }

    private static void registerPing() {
        service.registerJsonRenderable("ping", Ping.class, (payload, target) ->
                new RenderableNotification(
                        Component.text("Ping"), Component.text(payload.message())));
    }

    private static void enqueuePing(String key, String message) {
        service.enqueueNotification(new TypedNotification<>(
                key, DUE, null, new NotificationTarget(List.of(PLAYER)), "ping",
                new Ping(message), 0), false);
    }

    private static NotificationDelivery delivery(NotificationSinkRegistry sinks, Set<String> preferred) {
        NotificationPreferences preferences = player -> preferred;
        return new NotificationDelivery(database, service.dataTypeRegistry(), sinks, preferences,
                Logger.getLogger("test"));
    }

    private static boolean exists(String key) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByKey(key) != null;
        }
    }

    @Test
    @DisplayName("a renderable payload is rendered and delivered to the preferred medium's sink")
    void rendersAndDelivers() {
        registerPing();
        RecordingSink sink = new RecordingSink("recording", DeliveryResult.DELIVERED);
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);

        enqueuePing("p1", "hello");
        delivery(sinks, Set.of("recording")).deliver(PLAYER, NOW);

        Assertions.assertEquals(1, sink.received.size());
        Assertions.assertEquals("hello",
                PlainTextComponentSerializer.plainText().serialize(sink.received.get(0).body()));
    }

    @Test
    @DisplayName("a delivered notification is consumed, leaving nothing due for that player")
    void deliveredNotificationIsPruned() {
        registerPing();
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink("recording", DeliveryResult.DELIVERED));

        enqueuePing("p2", "hello");
        delivery(sinks, Set.of("recording")).deliver(PLAYER, NOW);

        Assertions.assertFalse(exists("p2"),
                "the last target was removed, so the trigger should have deleted the notification");
    }

    @Test
    @DisplayName("an unreachable medium retains the notification for a later attempt")
    void unreachableRetains() {
        registerPing();
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink("recording", DeliveryResult.UNREACHABLE));

        enqueuePing("p3", "hello");
        delivery(sinks, Set.of("recording")).deliver(PLAYER, NOW);

        Assertions.assertTrue(exists("p3"));
    }

    @Test
    @DisplayName("one medium delivering consumes the notification even when another is unreachable")
    void deleteWinsAcrossSinks() {
        registerPing();
        RecordingSink good = new RecordingSink("good", DeliveryResult.DELIVERED);
        RecordingSink bad = new RecordingSink("bad", DeliveryResult.UNREACHABLE);
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(good);
        sinks.registerSink(bad);

        enqueuePing("p4", "hello");
        delivery(sinks, Set.of("good", "bad")).deliver(PLAYER, NOW);

        Assertions.assertEquals(1, good.received.size());
        Assertions.assertEquals(1, bad.received.size());
        // Documented DELETE-wins fan-out: partial delivery is silent and the notification is consumed.
        Assertions.assertFalse(exists("p4"));
    }

    @Test
    @DisplayName("a preferred medium with no registered sink is skipped without failing delivery")
    void unknownMediumIsSkipped() {
        registerPing();
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();

        enqueuePing("p5", "hello");
        Assertions.assertDoesNotThrow(() -> delivery(sinks, Set.of("nonexistent")).deliver(PLAYER, NOW));

        Assertions.assertTrue(exists("p5"));
    }
}
