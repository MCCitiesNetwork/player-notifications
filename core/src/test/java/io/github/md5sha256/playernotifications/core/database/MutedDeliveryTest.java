package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import net.kyori.adventure.text.Component;
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
 * The mute gate in {@link NotificationDelivery#deliver(UUID, Instant)}: a globally muted player
 * receives nothing through any push path, including an explicitly registered processor, while their
 * notifications stay unread in the inbox and are delivered normally once unmuted.
 */
class MutedDeliveryTest extends AbstractDatabaseTest {

    private record Ping(String message) {
    }

    private record Poke(String message) {
    }

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    private static final class RecordingSink implements NotificationSink {

        private final String mediumKey;
        private final List<RenderableNotification> received = new ArrayList<>();

        RecordingSink(String mediumKey) {
            this.mediumKey = mediumKey;
        }

        @Override
        public @NotNull String mediumKey() {
            return this.mediumKey;
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            this.received.add(notification);
            return DeliveryResult.DELIVERED;
        }
    }

    private static void registerPing() {
        service.registerJsonRenderable("ping-" + UUID.randomUUID(), Ping.class, (payload, target) ->
                new RenderableNotification(Component.text("Ping"), Component.text(payload.message())));
    }

    private static NotificationDelivery delivery(NotificationSinkRegistry sinks,
                                                  DatabaseNotificationPreferences preferences) {
        return new NotificationDelivery(database, service.dataTypeRegistry(), sinks, preferences,
                Logger.getLogger("test"));
    }

    @Test
    @DisplayName("a muted player receives nothing through delivery, but the notification stays unread")
    void mutedPlayerReceivesNothingButKeepsTheNotification() {
        String dataType = "mute-ping-" + UUID.randomUUID();
        service.registerJsonRenderable(dataType, Ping.class, (payload, target) ->
                new RenderableNotification(Component.text("Ping"), Component.text(payload.message())));
        RecordingSink sink = new RecordingSink("recording");
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);

        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("recording"));
        preferences.mute(PLAYER);

        String key = "mp1-" + UUID.randomUUID();
        service.enqueueNotification(new TypedNotification<>(
                key, DUE, null, new NotificationTarget(List.of(PLAYER)), dataType,
                new Ping("hello"), 0), false);

        delivery(sinks, preferences).deliver(PLAYER, NOW);

        Assertions.assertEquals(0, sink.received.size());
        Assertions.assertEquals(1, service.unreadCount(PLAYER));
        Assertions.assertTrue(service.inbox(PLAYER, 1, 10).entries().stream()
                .anyMatch(entry -> entry.unread()));
    }

    @Test
    @DisplayName("unmuting restores delivery")
    void unmutingRestoresDelivery() {
        String dataType = "unmute-ping-" + UUID.randomUUID();
        service.registerJsonRenderable(dataType, Ping.class, (payload, target) ->
                new RenderableNotification(Component.text("Ping"), Component.text(payload.message())));
        RecordingSink sink = new RecordingSink("recording");
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);

        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("recording"));
        preferences.mute(PLAYER);

        String key = "mp2-" + UUID.randomUUID();
        service.enqueueNotification(new TypedNotification<>(
                key, DUE, null, new NotificationTarget(List.of(PLAYER)), dataType,
                new Ping("hello"), 0), false);

        delivery(sinks, preferences).deliver(PLAYER, NOW);
        Assertions.assertEquals(0, sink.received.size());

        preferences.unmute(PLAYER);
        delivery(sinks, preferences).deliver(PLAYER, NOW);

        Assertions.assertEquals(1, sink.received.size());
    }

    @Test
    @DisplayName("a muted player also bypasses an explicit processor")
    void mutedPlayerAlsoBypassesAnExplicitProcessor() {
        String dataType = "poke-" + UUID.randomUUID();
        List<UUID> invoked = new ArrayList<>();
        service.registerJsonPayload(dataType, Poke.class, (payload, target) -> {
            invoked.add(target);
            return NotificationDisposition.MARK_SEEN;
        });

        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        DatabaseNotificationPreferences preferences =
                new DatabaseNotificationPreferences(database, Set.of("recording"));
        preferences.mute(PLAYER);

        String key = "mp3-" + UUID.randomUUID();
        service.enqueueNotification(new TypedNotification<>(
                key, DUE, null, new NotificationTarget(List.of(PLAYER)), dataType,
                new Poke("hi"), 0), false);

        delivery(sinks, preferences).deliver(PLAYER, NOW);

        Assertions.assertTrue(invoked.isEmpty());
        Assertions.assertEquals(1, service.unreadCount(PLAYER));
    }
}
