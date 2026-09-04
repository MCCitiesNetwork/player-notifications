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
        @DisplayName("insertPreferences then selectByPlayerAndDataType round-trips every medium")
        void insertAndSelectByDataType() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                int inserted = mapper.insertPreferences(PLAYER_A, "economy", List.of("chat", "discord"));
                session.commit();
                Assertions.assertEquals(2, inserted);

                List<String> media = mapper.selectByPlayerAndDataType(PLAYER_A, "economy");
                Assertions.assertEquals(2, media.size());
                Assertions.assertTrue(media.containsAll(List.of("chat", "discord")));
            }
        }

        @Test
        @DisplayName("selectByPlayerAndDataType returns an empty list for an unconfigured data type")
        void selectByPlayerAndDataTypeEmpty() {
            try (SqlSessionWrapper wrapper = database.openSession()) {
                Assertions.assertTrue(wrapper.playerNotificationPreferenceMapper()
                        .selectByPlayerAndDataType(PLAYER_A, "economy").isEmpty());
            }
        }

        @Test
        @DisplayName("selectByPlayer returns every row for the player across all data types")
        void selectByPlayerReturnsAllDataTypes() {
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
        @DisplayName("deleteByPlayerAndDataType removes only that data type's rows")
        void deleteByPlayerAndDataType() {
            try (SqlSessionWrapper wrapper = database.openSession();
                 SqlSession session = wrapper.session()) {
                PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
                mapper.insertPreferences(PLAYER_A, "economy", List.of("chat"));
                mapper.insertPreferences(PLAYER_A, "moderation", List.of("dialog"));
                session.commit();

                int deleted = mapper.deleteByPlayerAndDataType(PLAYER_A, "economy");
                session.commit();

                Assertions.assertEquals(1, deleted);
                Assertions.assertTrue(mapper.selectByPlayerAndDataType(PLAYER_A, "economy").isEmpty());
                Assertions.assertEquals(List.of("dialog"), mapper.selectByPlayerAndDataType(PLAYER_A, "moderation"));
            }
        }

        @Test
        @DisplayName("deleteByPlayer removes only that player's rows across every data type")
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
                Assertions.assertEquals(List.of("dialog"), mapper.selectByPlayerAndDataType(PLAYER_B, "economy"));
            }
        }
    }

    @Nested
    @DisplayName("DatabaseNotificationPreferences")
    class DatabaseNotificationPreferencesTests {

        @Test
        @DisplayName("a player with no rows falls back to the configured default media, for any data type")
        void fallsBackToDefault() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A));
            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "economy"));
        }

        @Test
        @DisplayName("an exact data type row wins over the '*' fallback and the configured default")
        void exactDataTypeWinsOverFallbackAndDefault() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of(
                    DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY, Set.of("dialog"),
                    "economy", Set.of("discord")), Set.of());

            Assertions.assertEquals(Set.of("discord"), preferences.preferredMedia(PLAYER_A, "economy"));
            Assertions.assertEquals(Set.of("dialog"), preferences.preferredMedia(PLAYER_A, "moderation"));
            Assertions.assertEquals(Set.of("dialog"), preferences.preferredMedia(PLAYER_A));
        }

        @Test
        @DisplayName("effectiveMediaByDataType resolves every requested data type in one call")
        void effectiveMediaByDataTypeResolvesEachDataType() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("discord")), Set.of());

            Map<String, Set<String>> effective = preferences.effectiveMediaByDataType(
                    PLAYER_A, Set.of("economy", "moderation"));

            Assertions.assertEquals(Set.of("discord"), effective.get("economy"));
            Assertions.assertEquals(Set.of("chat"), effective.get("moderation"));
        }

        @Test
        @DisplayName("explicitlyConfiguredDataTypes reports only data types with exact stored rows")
        void explicitlyConfiguredDataTypesReportsExactRowsOnly() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("discord")), Set.of());

            Set<String> configured = preferences.explicitlyConfiguredDataTypes(
                    PLAYER_A, Set.of("economy", "moderation"));

            Assertions.assertEquals(Set.of("economy"), configured);
        }

        @Test
        @DisplayName("applyChanges resets given data types and writes explicit media in one transaction")
        void applyChangesResetsAndWritesTogether() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of(
                    "economy", Set.of("discord"), "moderation", Set.of("dialog")), Set.of());

            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("chat")), Set.of("moderation"));

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "economy"));
            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "moderation"));
            Assertions.assertEquals(Set.of(), preferences.explicitlyConfiguredDataTypes(
                    PLAYER_A, Set.of("moderation")));
        }

        @Test
        @DisplayName("resetAll clears every row for the player across all data types")
        void resetAllClearsEveryDataType() {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.applyChanges(PLAYER_A, Map.of("economy", Set.of("discord")), Set.of());

            preferences.resetAll(PLAYER_A);

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "economy"));
        }
    }

    @Nested
    @DisplayName("DatabaseNotificationPreferences per-type delivery defaults")
    class TypeDefaults {

        private DatabaseNotificationPreferences withOverride(String dataType, String... media) {
            DatabaseNotificationPreferences preferences =
                    new DatabaseNotificationPreferences(database, Set.of("chat"));
            preferences.reloadTypeDefaults(Map.of(dataType, List.of(media)));
            return preferences;
        }

        @Test
        @DisplayName("an override applies to a player with no rows of their own")
        void overrideAppliesToAnUnconfiguredPlayer() {
            DatabaseNotificationPreferences preferences =
                    withOverride("restart-warning", "discord-dm");

            Assertions.assertEquals(Set.of("discord-dm"),
                    preferences.preferredMedia(PLAYER_A, "restart-warning"));
        }

        @Test
        @DisplayName("a data type the map does not name still falls back to the configured default")
        void unnamedDataTypeFallsBackToTheGlobalDefault() {
            DatabaseNotificationPreferences preferences =
                    withOverride("restart-warning", "discord-dm");

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A, "economy"));
        }

        @Test
        @DisplayName("an exact player row wins over the override")
        void exactRowWinsOverTheOverride() {
            DatabaseNotificationPreferences preferences =
                    withOverride("restart-warning", "discord-dm");
            preferences.applyChanges(PLAYER_A, Map.of("restart-warning", Set.of("chat")), Set.of());

            Assertions.assertEquals(Set.of("chat"),
                    preferences.preferredMedia(PLAYER_A, "restart-warning"));
        }

        @Test
        @DisplayName("the player's '*' rows win over the override")
        void blanketRowWinsOverTheOverride() {
            DatabaseNotificationPreferences preferences =
                    withOverride("restart-warning", "discord-dm");
            preferences.applyChanges(PLAYER_A, Map.of(
                    DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY, Set.of("dialog")), Set.of());

            Assertions.assertEquals(Set.of("dialog"),
                    preferences.preferredMedia(PLAYER_A, "restart-warning"));
        }

        @Test
        @DisplayName("an override of 'none' makes the type opt-in")
        void noneMakesATypeOptIn() {
            DatabaseNotificationPreferences preferences = withOverride("maintenance", "none");

            Assertions.assertEquals(Set.of("none"),
                    preferences.preferredMedia(PLAYER_A, "maintenance"));
        }

        @Test
        @DisplayName("reloading an empty map withdraws every override")
        void reloadingAnEmptyMapWithdrawsOverrides() {
            DatabaseNotificationPreferences preferences =
                    withOverride("restart-warning", "discord-dm");

            preferences.reloadTypeDefaults(Map.of());

            Assertions.assertEquals(Set.of("chat"),
                    preferences.preferredMedia(PLAYER_A, "restart-warning"));
        }

        @Test
        @DisplayName("the single-argument form never consults an override, even one keyed '*'")
        void theBlanketFormIgnoresOverrides() {
            DatabaseNotificationPreferences preferences = withOverride(
                    DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY, "discord-dm");

            Assertions.assertEquals(Set.of("chat"), preferences.preferredMedia(PLAYER_A));
        }

        @Test
        @DisplayName("effectiveMediaByDataType resolves overrides exactly as preferredMedia does")
        void effectiveMediaAgreesWithPreferredMedia() {
            DatabaseNotificationPreferences preferences =
                    withOverride("restart-warning", "discord-dm");

            Map<String, Set<String>> effective = preferences.effectiveMediaByDataType(
                    PLAYER_A, Set.of("restart-warning", "economy"));

            Assertions.assertEquals(Set.of("discord-dm"), effective.get("restart-warning"));
            Assertions.assertEquals(Set.of("chat"), effective.get("economy"));
            Assertions.assertEquals(preferences.preferredMedia(PLAYER_A, "restart-warning"),
                    effective.get("restart-warning"));
        }
    }
}
