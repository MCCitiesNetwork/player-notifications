package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class PlayerNotificationPreferenceTest extends AbstractDatabaseTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();

    @Nested
    @DisplayName("PlayerNotificationPreferenceMapper")
    class MapperTests {

        @Test
        @DisplayName("insertPreferences then selectByPlayerAndCategory round-trips every medium")
        void insertAndSelectByCategory() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                int inserted = mapper.insertPreferences(PLAYER_A, "economy", List.of("chat", "discord"));
                session.commit();
                Assertions.assertEquals(2, inserted);

                List<String> media = mapper.selectByPlayerAndCategory(PLAYER_A, "economy");
                Assertions.assertEquals(2, media.size());
                Assertions.assertTrue(media.containsAll(List.of("chat", "discord")));
            }
        }

        @Test
        @DisplayName("selectByPlayerAndCategory returns an empty list for an unconfigured category")
        void selectByPlayerAndCategoryEmpty() {
            try (SqlSessionWrapper wrapper = database.openSession()) {
                Assertions.assertTrue(wrapper.playerNotificationPreferenceMapper()
                        .selectByPlayerAndCategory(PLAYER_A, "economy").isEmpty());
            }
        }

        @Test
        @DisplayName("selectByPlayer returns every row for the player across all categories")
        void selectByPlayerReturnsAllCategories() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                mapper.insertPreferences(PLAYER_A, "economy", List.of("chat"));
                mapper.insertPreferences(PLAYER_A, "moderation", List.of("dialog"));
                session.commit();

                List<PlayerNotificationPreferenceEntity> rows = mapper.selectByPlayer(PLAYER_A);
                Assertions.assertEquals(2, rows.size());
                Assertions.assertTrue(rows.contains(new PlayerNotificationPreferenceEntity("economy", "chat")));
                Assertions.assertTrue(rows.contains(new PlayerNotificationPreferenceEntity("moderation", "dialog")));
            }
        }

        @Test
        @DisplayName("deleteByPlayerAndCategory removes only that category's rows")
        void deleteByPlayerAndCategory() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                mapper.insertPreferences(PLAYER_A, "economy", List.of("chat"));
                mapper.insertPreferences(PLAYER_A, "moderation", List.of("dialog"));
                session.commit();

                int deleted = mapper.deleteByPlayerAndCategory(PLAYER_A, "economy");
                session.commit();

                Assertions.assertEquals(1, deleted);
                Assertions.assertTrue(mapper.selectByPlayerAndCategory(PLAYER_A, "economy").isEmpty());
                Assertions.assertEquals(List.of("dialog"), mapper.selectByPlayerAndCategory(PLAYER_A, "moderation"));
            }
        }

        @Test
        @DisplayName("deleteByPlayer removes only that player's rows across every category")
        void deleteByPlayer() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                mapper.insertPreferences(PLAYER_A, "economy", List.of("chat"));
                mapper.insertPreferences(PLAYER_B, "economy", List.of("dialog"));
                session.commit();

                int deleted = mapper.deleteByPlayer(PLAYER_A);
                session.commit();

                Assertions.assertEquals(1, deleted);
                Assertions.assertTrue(mapper.selectByPlayer(PLAYER_A).isEmpty());
                Assertions.assertEquals(List.of("dialog"), mapper.selectByPlayerAndCategory(PLAYER_B, "economy"));
            }
        }
    }

    @Nested
    @DisplayName("DatabaseNotificationPreferences")
    class DatabaseNotificationPreferencesTests {

        @Test
        @DisplayName("a player with no rows falls back to the configured default media, for any category")
        void fallsBackToDefault() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A));
            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "economy"));
        }

        @Test
        @DisplayName("an exact category row wins over the '*' fallback and the configured default")
        void exactCategoryWinsOverFallbackAndDefault() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of(
                    DatabaseNotificationPreferences.ALL_CATEGORIES_KEY, Set.of("dialog"),
                    "economy", Set.of("discord")), Set.of());

            Assertions.assertEquals(Set.of("discord"), preferences.preferredMedia(PLAYER_A, "economy"));
            Assertions.assertEquals(Set.of("dialog"), preferences.preferredMedia(PLAYER_A, "moderation"));
            Assertions.assertEquals(Set.of("dialog"), preferences.preferredMedia(PLAYER_A));
        }

        @Test
        @DisplayName("effectiveMediaByCategory resolves every requested category in one call")
        void effectiveMediaByCategoryResolvesEachCategory() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("discord")), Set.of());

            Map<String, Set<String>> effective = preferences.effectiveMediaByCategory(
                    PLAYER_A, Set.of("economy", "moderation"));

            Assertions.assertEquals(Set.of("discord"), effective.get("economy"));
            Assertions.assertEquals(Set.of("chat"), effective.get("moderation"));
        }

        @Test
        @DisplayName("explicitlyConfiguredCategories reports only categories with exact stored rows")
        void explicitlyConfiguredCategoriesReportsExactRowsOnly() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("discord")), Set.of());

            Set<String> configured = preferences.explicitlyConfiguredCategories(
                    PLAYER_A, Set.of("economy", "moderation"));

            Assertions.assertEquals(Set.of("economy"), configured);
        }

        @Test
        @DisplayName("applyChanges resets given categories and writes explicit media in one transaction")
        void applyChangesResetsAndWritesTogether() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of(
                    "economy", Set.of("discord"), "moderation", Set.of("dialog")), Set.of());

            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("chat")), Set.of("moderation"));

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "economy"));
            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "moderation"));
            Assertions.assertEquals(Set.of(), preferences.explicitlyConfiguredCategories(
                    PLAYER_A, Set.of("moderation")));
        }

        @Test
        @DisplayName("muteAll stores an explicit 'none' row for every given category")
        void muteAllMutesEveryCategory() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));

            preferences.muteAll(PLAYER_A, Set.of("economy", "moderation"));

            Assertions.assertEquals(Set.of("none"), preferences.preferredMedia(PLAYER_A, "economy"));
            Assertions.assertEquals(Set.of("none"), preferences.preferredMedia(PLAYER_A, "moderation"));
        }

        @Test
        @DisplayName("resetAll clears every row for the player across all categories")
        void resetAllClearsEveryCategory() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("discord")), Set.of());

            preferences.resetAll(PLAYER_A);

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "economy"));
        }
    }
}
