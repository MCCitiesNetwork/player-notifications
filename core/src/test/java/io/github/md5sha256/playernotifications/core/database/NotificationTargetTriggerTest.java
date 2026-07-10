package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Exercises the {@code trg_delete_targetless_notification} trigger: a notification is removed
 * automatically once the last member of its target group is deleted.
 */
class NotificationTargetTriggerTest extends AbstractDatabaseTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();
    private static final UUID PLAYER_C = UUID.randomUUID();

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    @DisplayName("notification is deleted only once its last target member is removed")
    void deletesWhenLastMemberRemoved() throws SQLException {
        int targetId = insertNotification("trigger-incremental", PLAYER_A, PLAYER_B);

        deleteMember(targetId, PLAYER_A);
        Assertions.assertTrue(notificationExists("trigger-incremental"),
                "notification should survive while a target remains");

        deleteMember(targetId, PLAYER_B);
        Assertions.assertFalse(notificationExists("trigger-incremental"),
                "notification should be removed once no targets remain");
    }

    @Test
    @DisplayName("clearing a whole target group deletes its notification")
    void deletesWhenGroupClearedAtOnce() {
        int targetId = insertNotification("trigger-bulk", PLAYER_A, PLAYER_B, PLAYER_C);

        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.notificationTargetMapper().deleteByTargetId(targetId);
            wrapper.session().commit();
        }

        Assertions.assertFalse(notificationExists("trigger-bulk"));
    }

    @Test
    @DisplayName("emptying one group leaves notifications of other groups untouched")
    void leavesOtherNotifications() throws SQLException {
        int targetA = insertNotification("gone", PLAYER_A);
        insertNotification("kept", PLAYER_C);

        deleteMember(targetA, PLAYER_A);

        Assertions.assertFalse(notificationExists("gone"));
        Assertions.assertTrue(notificationExists("kept"));
    }

    private static int insertNotification(String key, UUID... players) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(players));
            wrapper.notificationMapper().insert(new NotificationEntity(
                    key, NOW, null, targetId, "chat", "{}", 0));
            wrapper.session().commit();
            return targetId;
        }
    }

    private static boolean notificationExists(String key) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByKey(key) != null;
        }
    }

    /**
     * Deletes a single {@code (notifTargetId, playerUuid)} membership row directly, so the trigger
     * fires per removed member (the mappers only expose whole-group deletion).
     */
    private static void deleteMember(int targetId, UUID player) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM NotificationTarget WHERE notifTargetId = ? AND playerUuid = ?")) {
            ps.setInt(1, targetId);
            ps.setBytes(2, toBytes(player));
            ps.executeUpdate();
        }
    }

    private static byte[] toBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}
