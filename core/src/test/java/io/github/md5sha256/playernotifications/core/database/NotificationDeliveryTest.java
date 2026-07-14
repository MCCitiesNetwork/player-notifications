package io.github.md5sha256.playernotifications.core.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.serialize.JacksonPayloadSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

class NotificationDeliveryTest extends AbstractDatabaseTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    private static final String TYPE = "test";
    // notifPayload is a JSON column; a String payload is stored JSON-quoted and decoded on delivery.
    private static final String STORED = "\"hello\"";
    private static final String DECODED = "hello";

    @Test
    @DisplayName("processes a due notification for the target and prunes it on DELETE")
    void deliversAndPrunes() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.DELETE);
        NotificationDelivery delivery = deliveryFor(processor);
        insert("n1", TYPE, STORED, DUE, null, PLAYER_A, PLAYER_B);

        delivery.deliver(PLAYER_A, NOW);

        // Invoked once, for PLAYER_A only, with the decoded payload.
        Assertions.assertEquals(List.of(DECODED), processor.payloads);
        Assertions.assertEquals(List.of(PLAYER_A), processor.targets);
        // PLAYER_A pruned; the notification lives on for PLAYER_B.
        Assertions.assertTrue(keysFor(PLAYER_A).isEmpty());
        Assertions.assertEquals(List.of("n1"), keysFor(PLAYER_B));
    }

    @Test
    @DisplayName("RETAIN leaves the target in place")
    void retainKeepsTarget() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.RETAIN);
        NotificationDelivery delivery = deliveryFor(processor);
        insert("n1", TYPE, STORED, DUE, null, PLAYER_A);

        delivery.deliver(PLAYER_A, NOW);

        Assertions.assertEquals(1, processor.payloads.size());
        Assertions.assertEquals(List.of("n1"), keysFor(PLAYER_A));
    }

    @Test
    @DisplayName("delivering to the last target deletes the notification")
    void lastTargetDeletesNotification() {
        NotificationDelivery delivery = deliveryFor(new RecordingProcessor(NotificationDisposition.DELETE));
        insert("solo", TYPE, STORED, DUE, null, PLAYER_A);

        delivery.deliver(PLAYER_A, NOW);

        Assertions.assertFalse(exists("solo"));
    }

    @Test
    @DisplayName("does not deliver notifications scheduled in the future")
    void skipsFutureScheduled() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.DELETE);
        NotificationDelivery delivery = deliveryFor(processor);
        insert("future", TYPE, STORED, NOW.plus(1, ChronoUnit.HOURS), null, PLAYER_A);

        delivery.deliver(PLAYER_A, NOW);

        Assertions.assertTrue(processor.payloads.isEmpty());
        Assertions.assertEquals(List.of("future"), keysFor(PLAYER_A));
    }

    @Test
    @DisplayName("does not deliver expired notifications")
    void skipsExpired() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.DELETE);
        NotificationDelivery delivery = deliveryFor(processor);
        insert("expired", TYPE, STORED, DUE, NOW.minus(1, ChronoUnit.MINUTES), PLAYER_A);

        delivery.deliver(PLAYER_A, NOW);

        Assertions.assertTrue(processor.payloads.isEmpty());
        Assertions.assertTrue(exists("expired"));
    }

    @Test
    @DisplayName("retains notifications whose data type has no registered processor")
    void retainsWhenNoProcessor() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.DELETE);
        NotificationDelivery delivery = deliveryFor(processor);
        insert("unhandled", "other-type", STORED, DUE, null, PLAYER_A);

        delivery.deliver(PLAYER_A, NOW);

        Assertions.assertTrue(processor.payloads.isEmpty());
        Assertions.assertEquals(List.of("unhandled"), keysFor(PLAYER_A));
    }

    @Test
    @DisplayName("a service-enqueued notification is delivered to the processor for its data type")
    void enqueueThenDeliver() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.DELETE);
        service.dataTypeRegistry().registerPayloadMapping(TYPE, String.class);
        service.dataTypeRegistry().registerProcessor(String.class, processor);
        NotificationDelivery delivery =
                new NotificationDelivery(database, service.dataTypeRegistry(), Logger.getLogger("test"));

        service.enqueueNotification(new ResolvedNotification(
                "e2e", DUE, null, new NotificationTarget(List.of(PLAYER_A)), TYPE, STORED, 0), false);

        delivery.deliver(PLAYER_A, NOW);

        Assertions.assertEquals(1, processor.payloads.size());
        Assertions.assertEquals(List.of(PLAYER_A), processor.targets);
        // Delivered to the only target, so the notification is deleted.
        Assertions.assertFalse(exists("e2e"));
    }

    private static NotificationDelivery deliveryFor(RecordingProcessor processor) {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(TYPE, String.class);
        registry.registerProcessor(String.class, processor);
        registry.registerSerializer(String.class, new JacksonPayloadSerializer<>(new ObjectMapper(), String.class));
        return new NotificationDelivery(database, registry, Logger.getLogger("test"));
    }

    private static void insert(String key, String payloadType, String payload,
                               Instant scheduled, Instant expiry, UUID... players) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(players));
            wrapper.notificationMapper().insert(new NotificationEntity(
                    key, scheduled, expiry, targetId, payloadType, payload, 0));
            wrapper.session().commit();
        }
    }

    private static List<String> keysFor(UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByPlayer(player).stream()
                    .map(NotificationEntity::notifKey)
                    .toList();
        }
    }

    private static boolean exists(String key) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByKey(key) != null;
        }
    }

    private static final class RecordingProcessor implements NotificationProcessor<String> {

        private final List<String> payloads = new ArrayList<>();
        private final List<UUID> targets = new ArrayList<>();
        private final NotificationDisposition disposition;

        private RecordingProcessor(NotificationDisposition disposition) {
            this.disposition = disposition;
        }

        @Override
        public NotificationDisposition receiveNotification(String payload, UUID target) {
            this.payloads.add(payload);
            this.targets.add(target);
            return this.disposition;
        }
    }
}
