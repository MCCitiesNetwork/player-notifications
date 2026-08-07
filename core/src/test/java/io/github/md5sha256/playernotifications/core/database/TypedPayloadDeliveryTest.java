package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializationException;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

class TypedPayloadDeliveryTest extends AbstractDatabaseTest {

    private record Greeting(String message, int count) {
    }

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    @Test
    @DisplayName("a typed payload enqueued via the service is delivered decoded to its processor")
    void typedRoundTrip() {
        List<Greeting> received = new ArrayList<>();
        service.registerJsonPayload("greeting", Greeting.class, (payload, target) -> {
            received.add(payload);
            return NotificationDisposition.MARK_SEEN;
        });

        service.enqueueNotification(new TypedNotification<>(
                "g1", DUE, null, new NotificationTarget(List.of(PLAYER)), "greeting",
                new Greeting("hi", 3), 0), false);

        delivery().deliver(PLAYER, NOW);

        Assertions.assertEquals(List.of(new Greeting("hi", 3)), received);
    }

    @Test
    @DisplayName("a deserialization failure retains the notification instead of crashing delivery")
    void deserializeFailureRetains() {
        service.registerJsonPayload("greeting", Greeting.class, (payload, target) -> {
            throw new AssertionError("processor must not run on undecodable payload");
        });

        // Persist a payload the Greeting serializer cannot decode (valid JSON, wrong shape).
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(PLAYER));
            wrapper.notificationMapper().insert(new io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity(
                    "bad", DUE, null, targetId, "greeting", "[1,2,3]", 0));
            wrapper.session().commit();
        }

        Assertions.assertDoesNotThrow(() -> delivery().deliver(PLAYER, NOW));
        Assertions.assertTrue(exists("bad"));
    }

    @Test
    @DisplayName("typed enqueue with no serializer registered throws PayloadSerializationException")
    void typedEnqueueWithoutSerializerThrows() {
        TypedNotification<Greeting> notification = new TypedNotification<>(
                "u1", DUE, null, new NotificationTarget(List.of(PLAYER)), "unregistered",
                new Greeting("hi", 3), 0);

        Assertions.assertThrows(PayloadSerializationException.class,
                () -> service.enqueueNotification(notification, false));
    }

    private static NotificationDelivery delivery() {
        return new NotificationDelivery(database, service.dataTypeRegistry(), Logger.getLogger("test"));
    }

    private static boolean exists(String key) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByKey(key) != null;
        }
    }
}