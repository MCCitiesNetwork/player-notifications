package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationMapper;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

class NotificationMapperTest extends AbstractDatabaseTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();
    private static final UUID PLAYER_C = UUID.randomUUID();

    // DATETIME has whole-second precision, so use truncated instants for round-trip equality.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    /**
     * Inserts a notification targeting the given players in one committed
     * transaction, mirroring how the service enqueues, and returns the allocated
     * target id.
     */
    private static int insertNotification(String key,
                                          int priority,
                                          Instant scheduled,
                                          Instant expiry,
                                          String payloadType,
                                          String payload,
                                          UUID... players) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            int targetId = wrapper.notificationTargetMapper().nextTargetId();
            wrapper.notificationTargetMapper().insertMembers(targetId, List.of(players));
            wrapper.notificationMapper().insert(new NotificationEntity(
                    key, scheduled, expiry, targetId, payloadType, payload, priority));
            wrapper.session().commit();
            return targetId;
        }
    }

    @Nested
    @DisplayName("NotificationTargetMapper")
    class NotificationTargetMapperTests {

        @Test
        @DisplayName("nextTargetId returns 1 when the table is empty")
        void nextTargetIdEmpty() {
            try (SqlSessionWrapper wrapper = database.openSession()) {
                Assertions.assertEquals(1, wrapper.notificationTargetMapper().nextTargetId());
            }
        }

        @Test
        @DisplayName("insertMembers persists every member and selectPlayerUuids returns them")
        void insertAndSelectMembers() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                NotificationTargetMapper mapper = wrapper.notificationTargetMapper();
                int inserted = mapper.insertMembers(7, List.of(PLAYER_A, PLAYER_B, PLAYER_C));
                session.commit();
                Assertions.assertEquals(3, inserted);

                List<UUID> members = mapper.selectPlayerUuids(7);
                Assertions.assertEquals(3, members.size());
                Assertions.assertTrue(members.containsAll(List.of(PLAYER_A, PLAYER_B, PLAYER_C)));
            }
        }

        @Test
        @DisplayName("nextTargetId is MAX(id) + 1 after inserts")
        void nextTargetIdAfterInsert() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                wrapper.notificationTargetMapper().insertMembers(5, List.of(PLAYER_A));
                session.commit();
                Assertions.assertEquals(6, wrapper.notificationTargetMapper().nextTargetId());
            }
        }

        @Test
        @DisplayName("deleteByTargetId removes only that group's members")
        void deleteByTargetId() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                NotificationTargetMapper mapper = wrapper.notificationTargetMapper();
                mapper.insertMembers(1, List.of(PLAYER_A, PLAYER_B));
                mapper.insertMembers(2, List.of(PLAYER_C));
                session.commit();

                int deleted = mapper.deleteByTargetId(1);
                session.commit();
                Assertions.assertEquals(2, deleted);
                Assertions.assertTrue(mapper.selectPlayerUuids(1).isEmpty());
                Assertions.assertEquals(1, mapper.selectPlayerUuids(2).size());
            }
        }
    }

    @Nested
    @DisplayName("NotificationMapper")
    class NotificationMapperTests {

        @Test
        @DisplayName("insert then selectByKey round-trips all columns")
        void insertAndSelectByKey() {
            Instant expiry = NOW.plus(1, ChronoUnit.HOURS);
            int targetId = insertNotification("key-1", 5, NOW, expiry, "chat", "{\"msg\":\"hi\"}", PLAYER_A);

            try (SqlSessionWrapper wrapper = database.openSession()) {
                NotificationEntity entity = wrapper.notificationMapper().selectByKey("key-1");
                Assertions.assertNotNull(entity);
                Assertions.assertEquals("key-1", entity.notifKey());
                Assertions.assertEquals(NOW, entity.notifScheduledTime());
                Assertions.assertEquals(expiry, entity.notifExpiryTime());
                Assertions.assertEquals(targetId, entity.notifTargetId());
                Assertions.assertEquals("chat", entity.notifPayloadType());
                Assertions.assertEquals("{\"msg\":\"hi\"}", entity.notifPayload());
                Assertions.assertEquals(5, entity.notifPriority());
            }
        }

        @Test
        @DisplayName("selectByKey returns null for a missing key")
        void selectByKeyMissing() {
            try (SqlSessionWrapper wrapper = database.openSession()) {
                Assertions.assertNull(wrapper.notificationMapper().selectByKey("nope"));
            }
        }

        @Test
        @DisplayName("nullable expiry round-trips as null")
        void nullExpiry() {
            insertNotification("key-null-expiry", 0, NOW, null, "chat", "{}", PLAYER_A);
            try (SqlSessionWrapper wrapper = database.openSession()) {
                NotificationEntity entity = wrapper.notificationMapper().selectByKey("key-null-expiry");
                Assertions.assertNotNull(entity);
                Assertions.assertNull(entity.notifExpiryTime());
            }
        }

        @Test
        @DisplayName("selectByPlayer joins through target membership, ordered by priority desc")
        void selectByPlayerOrdered() {
            insertNotification("low", 1, NOW, null, "chat", "{}", PLAYER_A);
            insertNotification("high", 10, NOW, null, "chat", "{}", PLAYER_A, PLAYER_B);
            insertNotification("other", 5, NOW, null, "chat", "{}", PLAYER_C);

            try (SqlSessionWrapper wrapper = database.openSession()) {
                List<NotificationEntity> forA = wrapper.notificationMapper().selectByPlayer(PLAYER_A);
                Assertions.assertEquals(2, forA.size());
                Assertions.assertEquals("high", forA.get(0).notifKey());
                Assertions.assertEquals("low", forA.get(1).notifKey());

                List<NotificationEntity> forB = wrapper.notificationMapper().selectByPlayer(PLAYER_B);
                Assertions.assertEquals(1, forB.size());
                Assertions.assertEquals("high", forB.get(0).notifKey());
            }
        }

        @Test
        @DisplayName("deleteByKey removes a single notification")
        void deleteByKey() {
            insertNotification("doomed", 0, NOW, null, "chat", "{}", PLAYER_A);
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                int deleted = wrapper.notificationMapper().deleteByKey("doomed");
                session.commit();
                Assertions.assertEquals(1, deleted);
                Assertions.assertNull(wrapper.notificationMapper().selectByKey("doomed"));
            }
        }

        @Test
        @DisplayName("deleteByPlayer removes every notification targeting the player")
        void deleteByPlayer() {
            insertNotification("a1", 0, NOW, null, "chat", "{}", PLAYER_A);
            insertNotification("a2", 0, NOW, null, "chat", "{}", PLAYER_A, PLAYER_B);
            insertNotification("c1", 0, NOW, null, "chat", "{}", PLAYER_C);

            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                int deleted = wrapper.notificationMapper().deleteByPlayer(PLAYER_A);
                session.commit();
                Assertions.assertEquals(2, deleted);
                Assertions.assertTrue(wrapper.notificationMapper().selectByPlayer(PLAYER_A).isEmpty());
                // A notification targeting only PLAYER_C is untouched.
                Assertions.assertEquals(1, wrapper.notificationMapper().selectByPlayer(PLAYER_C).size());
            }
        }

        @Test
        @DisplayName("deleteByPayloadType removes only matching types")
        void deleteByPayloadType() {
            insertNotification("chat-1", 0, NOW, null, "chat", "{}", PLAYER_A);
            insertNotification("toast-1", 0, NOW, null, "toast", "{}", PLAYER_A);

            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                int deleted = wrapper.notificationMapper().deleteByPayloadType("chat");
                session.commit();
                Assertions.assertEquals(1, deleted);
                Assertions.assertNull(wrapper.notificationMapper().selectByKey("chat-1"));
                Assertions.assertNotNull(wrapper.notificationMapper().selectByKey("toast-1"));
            }
        }

        @Test
        @DisplayName("deleteExpired removes only notifications past their expiry")
        void deleteExpired() {
            Instant past = NOW.minus(1, ChronoUnit.HOURS);
            Instant future = NOW.plus(1, ChronoUnit.HOURS);
            insertNotification("expired", 0, NOW, past, "chat", "{}", PLAYER_A);
            insertNotification("live", 0, NOW, future, "chat", "{}", PLAYER_A);
            insertNotification("never", 0, NOW, null, "chat", "{}", PLAYER_A);

            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                int deleted = wrapper.notificationMapper().deleteExpired(NOW);
                session.commit();
                Assertions.assertEquals(1, deleted);
                Assertions.assertNull(wrapper.notificationMapper().selectByKey("expired"));
                Assertions.assertNotNull(wrapper.notificationMapper().selectByKey("live"));
                Assertions.assertNotNull(wrapper.notificationMapper().selectByKey("never"));
            }
        }
    }
}
