package io.github.md5sha256.playernotifications.core.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.serialize.JacksonPayloadSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Covers the inbox's central change: delivery marks a target seen instead of deleting its row, so the
 * notification stays readable, and the delivery query then skips what has already been seen.
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-08-07-notification-inbox.md}, Task 3.
 */
class InboxDeliveryTest extends AbstractDatabaseTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    private static final String TYPE = "test";
    private static final String STORED = "\"hello\"";

    @Test
    @DisplayName("MARK_SEEN stamps seenTime and keeps the notification readable")
    void markSeenRetainsTheNotification() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.MARK_SEEN);
        insert("n1", DUE, null, PLAYER_A);

        deliveryFor(processor).deliver(PLAYER_A, NOW);

        Assertions.assertEquals(1, processor.targets.size());
        Assertions.assertNotNull(seenTime("n1", PLAYER_A), "expected seenTime to be stamped");
        Assertions.assertTrue(exists("n1"), "the notification must survive delivery");
    }

    @Test
    @DisplayName("a seen notification is not offered to the processor again")
    void seenNotificationIsNotRedelivered() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.MARK_SEEN);
        NotificationDelivery delivery = deliveryFor(processor);
        insert("n1", DUE, null, PLAYER_A);

        delivery.deliver(PLAYER_A, NOW);
        delivery.deliver(PLAYER_A, NOW.plusSeconds(60));

        Assertions.assertEquals(1, processor.targets.size(), "expected exactly one dispatch");
        // Skipped because it is seen, not because it was destroyed on the first pass.
        Assertions.assertTrue(exists("n1"));
        Assertions.assertNotNull(seenTime("n1", PLAYER_A));
    }

    @Test
    @DisplayName("RETAIN leaves seenTime null and the notification is offered again")
    void retainLeavesTheNotificationUnread() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.RETAIN);
        NotificationDelivery delivery = deliveryFor(processor);
        insert("n1", DUE, null, PLAYER_A);

        delivery.deliver(PLAYER_A, NOW);
        delivery.deliver(PLAYER_A, NOW.plusSeconds(60));

        Assertions.assertEquals(2, processor.targets.size());
        Assertions.assertNull(seenTime("n1", PLAYER_A));
    }

    @Test
    @DisplayName("marking one member seen leaves the other members of the group unread")
    void markingSeenIsPerPlayer() {
        RecordingProcessor processor = new RecordingProcessor(NotificationDisposition.MARK_SEEN);
        insert("shared", DUE, null, PLAYER_A, PLAYER_B);

        deliveryFor(processor).deliver(PLAYER_A, NOW);

        Assertions.assertNotNull(seenTime("shared", PLAYER_A));
        Assertions.assertNull(seenTime("shared", PLAYER_B));
    }

    private static NotificationDelivery deliveryFor(RecordingProcessor processor) {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(TYPE, String.class);
        registry.registerProcessor(String.class, processor);
        registry.registerSerializer(String.class,
                new JacksonPayloadSerializer<>(new ObjectMapper(), String.class));
        return new NotificationDelivery(database, registry, Logger.getLogger("test"));
    }

    private static void insert(String key, Instant scheduled, Instant expiry, UUID... players) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(players));
            wrapper.notificationMapper().insert(new NotificationEntity(
                    key, scheduled, expiry, targetId, TYPE, STORED, 0));
            wrapper.session().commit();
        }
    }

    private static boolean exists(String key) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByKey(key) != null;
        }
    }

    /** Reads {@code seenTime} straight from the table: no mapper exposes it as a read today. */
    private static Instant seenTime(String key, UUID player) {
        String sql = """
                SELECT t.seenTime
                FROM NotificationTarget t
                INNER JOIN Notification n ON n.notifTargetId = t.notifTargetId
                WHERE n.notifKey = ? AND t.playerUuid = UNHEX(REPLACE(?, '-', ''))
                """;
        try (Connection connection = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, player.toString());
            try (ResultSet rs = statement.executeQuery()) {
                Assertions.assertTrue(rs.next(), "no target row for " + key + " / " + player);
                java.sql.Timestamp stamp = rs.getTimestamp(1);
                return stamp == null ? null : stamp.toInstant();
            }
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingProcessor implements NotificationProcessor<String> {

        private final List<UUID> targets = new ArrayList<>();
        private final NotificationDisposition disposition;

        private RecordingProcessor(NotificationDisposition disposition) {
            this.disposition = disposition;
        }

        @Override
        public NotificationDisposition receiveNotification(String payload, UUID target) {
            this.targets.add(target);
            return this.disposition;
        }
    }
}
