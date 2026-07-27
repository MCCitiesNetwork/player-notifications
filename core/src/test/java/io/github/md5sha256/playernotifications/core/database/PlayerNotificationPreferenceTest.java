package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

class PlayerNotificationPreferenceTest extends AbstractDatabaseTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();

    @Nested
    @DisplayName("PlayerNotificationPreferenceMapper")
    class MapperTests {

        @Test
        @DisplayName("insertPreferences then selectByPlayer round-trips every medium")
        void insertAndSelect() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                int inserted = mapper.insertPreferences(PLAYER_A, List.of("chat", "discord"));
                session.commit();
                Assertions.assertEquals(2, inserted);

                List<String> media = mapper.selectByPlayer(PLAYER_A);
                Assertions.assertEquals(2, media.size());
                Assertions.assertTrue(media.containsAll(List.of("chat", "discord")));
            }
        }

        @Test
        @DisplayName("selectByPlayer returns an empty list for a player with no rows")
        void selectByPlayerEmpty() {
            try (SqlSessionWrapper wrapper = database.openSession()) {
                Assertions.assertTrue(wrapper.playerNotificationPreferenceMapper()
                        .selectByPlayer(PLAYER_A).isEmpty());
            }
        }

        @Test
        @DisplayName("deleteByPlayer removes only that player's rows")
        void deleteByPlayer() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                mapper.insertPreferences(PLAYER_A, List.of("chat"));
                mapper.insertPreferences(PLAYER_B, List.of("dialog"));
                session.commit();

                int deleted = mapper.deleteByPlayer(PLAYER_A);
                session.commit();

                Assertions.assertEquals(1, deleted);
                Assertions.assertTrue(mapper.selectByPlayer(PLAYER_A).isEmpty());
                Assertions.assertEquals(List.of("dialog"), mapper.selectByPlayer(PLAYER_B));
            }
        }
    }

    @Nested
    @DisplayName("DatabaseNotificationPreferences")
    class DatabaseNotificationPreferencesTests {

        @Test
        @DisplayName("a player with no rows falls back to the configured default media")
        void fallsBackToDefault() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A));
        }

        @Test
        @DisplayName("a player with stored rows uses those instead of the default")
        void usesStoredPreferences() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.setPreferredMedia(PLAYER_A, Set.of("dialog", "discord"));

            Assertions.assertEquals(Set.of("dialog", "discord"), preferences.preferredMedia(PLAYER_A));
        }

        @Test
        @DisplayName("setPreferredMedia replaces any previously stored preferences wholesale")
        void replacesExistingPreferences() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.setPreferredMedia(PLAYER_A, Set.of("dialog"));
            preferences.setPreferredMedia(PLAYER_A, Set.of("discord"));

            Assertions.assertEquals(Set.of("discord"), preferences.preferredMedia(PLAYER_A));
        }

        @Test
        @DisplayName("replacing with an empty set clears preferences back to the default")
        void emptyReplaceFallsBackToDefault() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.setPreferredMedia(PLAYER_A, Set.of("dialog"));
            preferences.setPreferredMedia(PLAYER_A, Set.of());

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A));
        }
    }
}
