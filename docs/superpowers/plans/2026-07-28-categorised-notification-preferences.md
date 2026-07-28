# Categorised Notification Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat per-player medium preference with a category x medium matrix, editable through two dialog pivots ("by delivery method" and "by notification type") over one staged edit session.

**Architecture:** A new `PlayerNotificationPreference(playerUuid, category, medium)` table (migrated from the old `(playerUuid, medium)` table via a reserved `*` fallback category) backs a category-aware `NotificationPreferences.preferredMedia(player, category)`. `NotificationCategories` (core, config-driven) resolves a payload's `dataType` to a category. `NotificationDelivery` resolves that category per notification and threads it through `RenderingProcessor` into the preference lookup — no change to any `NotificationRenderer` or `NotificationSink`. On the Paper side, a `PreferenceEditSession` per player stages checkbox edits from either pivot against the same in-memory matrix; five dialog screens plus a `PreferenceDialogRouter` replace the current single preferences dialog; `/notifications` gains `media`/`types`/`mute`/`reset` subcommands.

**Tech Stack:** Java 21, MyBatis over MariaDB, Configurate (YAML), Paper 1.21.8 Brigadier commands and dialog API, JUnit 5, Testcontainers (`mariadb:11.7`).

## Global Constraints

- Java 21 toolchain, UTF-8 source encoding (already enforced by `player-notifications-conventions`).
- Every non-null (`@NotNull`, reference-typed) `@Setting` field in a `@ConfigSerializable` record must also be annotated `@Required`.
- Configurate 4.2.0 has no `java.time.Duration` serializer — use `long`-seconds fields, not `Duration`, in any new config record.
- Adding a migration requires **both** a `V*.sql` file under `core/src/main/resources/sql/migrations/` **and** a `MigrationStep` entry in `MariaSchemaMigrator.DEFAULT_MIGRATIONS` — that list is hardcoded, not discovered from the classpath.
- A migration script is split on `;` by the migrator; a trigger body must stay a single statement (not relevant to this plan's migration, which has no trigger).
- `:core:test` requires a running Docker daemon (Testcontainers spins up a real `mariadb:11.7` container). Run `./gradlew :core:test` after every core-module task.
- When counting JUnit results on Windows, glob `*.xml` under the test results directory, not `TEST-*.xml` — `@Nested` classes produce shortened `__TEST-<hash>...` filenames that a `TEST-*.xml` glob silently omits.
- Do not use `git commit --amend`; create a new commit per task. Commit directly to `main` (no feature branches) per this repository's convention, with **no** `Claude-Session` trailer in the commit message.
- `NotificationPreferences` must remain usable as a lambda target (existing tests write `target -> Set.of("chat")`) — any added method must be a `default` method, not a second abstract method.
- `RenderingProcessor`'s and `NotificationDelivery`'s existing constructors must keep their exact arity and behavior — existing tests construct them directly and must keep compiling and passing unchanged.

---

### Task 1: Category-aware preference storage — schema, entity, mappers

**Files:**
- Create: `core/src/main/resources/sql/migrations/V3__categorised_preferences.sql`
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaSchemaMigrator.java`
- Create: `core/src/main/java/io/github/md5sha256/playernotifications/core/database/entity/PlayerNotificationPreferenceEntity.java`
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/PlayerNotificationPreferenceMapper.java`
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaPlayerNotificationPreferenceMapper.java`
- Modify: `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java` (only the `MapperTests` nested class — leave `DatabaseNotificationPreferencesTests` for Task 4)

**Interfaces:**
- Produces: `PlayerNotificationPreferenceEntity(String category, String medium)`; `PlayerNotificationPreferenceMapper` with `selectByPlayer(UUID) -> List<PlayerNotificationPreferenceEntity>`, `selectByPlayerAndCategory(UUID, String) -> List<String>`, `insertPreferences(UUID, String, Collection<String>) -> int`, `deleteByPlayerAndCategory(UUID, String) -> int`, `deleteByPlayer(UUID) -> int`.
- Consumes: nothing from other tasks.

- [ ] **Step 1: Write the failing mapper test**

Replace the `MapperTests` nested class in `PlayerNotificationPreferenceTest.java` (keep the outer class and the `DatabaseNotificationPreferencesTests` nested class untouched for now):

```java
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
```

Add the import `io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity` alongside the existing imports.

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest"`
Expected: compile failure — `selectByPlayerAndCategory`, `PlayerNotificationPreferenceEntity`, and the 3-arg `insertPreferences` do not exist yet.

- [ ] **Step 3: Create the migration file**

`core/src/main/resources/sql/migrations/V3__categorised_preferences.sql`:

```sql
CREATE TABLE PlayerNotificationPreference_v3
(
    playerUuid BINARY(16)  NOT NULL,
    category   VARCHAR(64) NOT NULL,
    medium     VARCHAR(64) NOT NULL,
    PRIMARY KEY (playerUuid, category, medium)
);

INSERT INTO PlayerNotificationPreference_v3 (playerUuid, category, medium)
SELECT playerUuid, '*', medium
FROM PlayerNotificationPreference;

DROP TABLE PlayerNotificationPreference;

RENAME TABLE PlayerNotificationPreference_v3 TO PlayerNotificationPreference;
```

- [ ] **Step 4: Register the migration step**

In `MariaSchemaMigrator.java`, extend `DEFAULT_MIGRATIONS`:

```java
    private static final List<MigrationStep> DEFAULT_MIGRATIONS = List.of(
            new MigrationStep(1, "initial schema", "V1__maria_initial_schema.sql"),
            new MigrationStep(2, "player notification preferences",
                    "V2__player_notification_preferences.sql"),
            new MigrationStep(3, "categorised notification preferences",
                    "V3__categorised_preferences.sql")
    );
```

- [ ] **Step 5: Create the entity**

`core/src/main/java/io/github/md5sha256/playernotifications/core/database/entity/PlayerNotificationPreferenceEntity.java`:

```java
package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;

/**
 * One row of the {@code PlayerNotificationPreference} table: a single medium a player has (explicitly
 * or via the {@code *} fallback) configured for one category.
 *
 * @param category the category key, or {@code *} for the pre-migration fallback row
 * @param medium   the medium key, or {@code none} for an explicit mute
 */
public record PlayerNotificationPreferenceEntity(
        @NotNull String category,
        @NotNull String medium
) {
}
```

- [ ] **Step 6: Update the neutral mapper interface**

`core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/PlayerNotificationPreferenceMapper.java`:

```java
package io.github.md5sha256.playernotifications.core.database.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code PlayerNotificationPreference} table, which
 * stores a player's preferred media per category as rows sharing a {@code playerUuid}. SQL annotations
 * are supplied by database-specific sub-interfaces.
 */
public interface PlayerNotificationPreferenceMapper {

    /**
     * Every stored row for the player, across every category (including the {@code *} fallback).
     */
    @NotNull List<PlayerNotificationPreferenceEntity> selectByPlayer(@NotNull UUID playerUuid);

    /**
     * The media stored for exactly the given category. Empty if the player has no rows for that exact
     * category — callers apply {@code *}/default fallback themselves.
     */
    @NotNull List<String> selectByPlayerAndCategory(@NotNull UUID playerUuid, @NotNull String category);

    /**
     * Inserts every given medium as a preference row for the player and category in a single multi-row
     * statement. The caller must ensure {@code media} is non-empty; an empty collection would produce
     * invalid SQL.
     *
     * @return the number of rows inserted
     */
    int insertPreferences(@NotNull UUID playerUuid, @NotNull String category, @NotNull Collection<String> media);

    /**
     * Deletes every preference row for the given player and category, e.g. before replacing them
     * wholesale or resetting the category to the server default.
     *
     * @return the number of rows removed
     */
    int deleteByPlayerAndCategory(@NotNull UUID playerUuid, @NotNull String category);

    /**
     * Deletes every preference row for the given player across all categories.
     *
     * @return the number of rows removed
     */
    int deleteByPlayer(@NotNull UUID playerUuid);

}
```

- [ ] **Step 7: Update the MariaDB mapper implementation**

`core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaPlayerNotificationPreferenceMapper.java`:

```java
package io.github.md5sha256.playernotifications.core.database.maria.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * MariaDB-specific MyBatis mapper for the {@code PlayerNotificationPreference} table.
 */
public interface MariaPlayerNotificationPreferenceMapper extends PlayerNotificationPreferenceMapper {

    @Override
    @Select("""
            SELECT category, medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    @ConstructorArgs({
            @Arg(column = "category", javaType = String.class),
            @Arg(column = "medium", javaType = String.class)
    })
    @NotNull List<PlayerNotificationPreferenceEntity> selectByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Select("""
            SELECT medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND category = #{category}
            """)
    @NotNull List<String> selectByPlayerAndCategory(@Param("playerUuid") @NotNull UUID playerUuid,
                                                     @Param("category") @NotNull String category);

    @Override
    @Insert("""
            <script>
            INSERT INTO PlayerNotificationPreference (playerUuid, category, medium)
            VALUES
            <foreach item="medium" collection="media" separator=",">
                (#{playerUuid}, #{category}, #{medium})
            </foreach>
            </script>
            """)
    int insertPreferences(@Param("playerUuid") @NotNull UUID playerUuid,
                          @Param("category") @NotNull String category,
                          @Param("media") @NotNull Collection<String> media);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND category = #{category}
            """)
    int deleteByPlayerAndCategory(@Param("playerUuid") @NotNull UUID playerUuid,
                                  @Param("category") @NotNull String category);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    int deleteByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest"`
Expected: PASS (5 tests in `MapperTests`; `DatabaseNotificationPreferencesTests` will fail to compile until Task 4 — that is expected and fixed there, not here). If your JDK/Gradle setup fails the whole module on the other nested class's compile error, temporarily comment out the body of `DatabaseNotificationPreferencesTests` (leave the class and `@Nested` annotation, remove its `@Test` methods) so this task's tests can run standalone; Task 4 restores it.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/resources/sql/migrations/V3__categorised_preferences.sql \
        core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaSchemaMigrator.java \
        core/src/main/java/io/github/md5sha256/playernotifications/core/database/entity/PlayerNotificationPreferenceEntity.java \
        core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/PlayerNotificationPreferenceMapper.java \
        core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaPlayerNotificationPreferenceMapper.java \
        core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java
git commit -m "feat: add category column to PlayerNotificationPreference"
```

---

### Task 2: Notification categories — config and resolution

**Files:**
- Create: `core/src/main/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoryDefinition.java`
- Create: `core/src/main/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoriesConfig.java`
- Create: `core/src/main/java/io/github/md5sha256/playernotifications/core/category/NotificationCategories.java`
- Test: `core/src/test/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoriesTest.java`

**Interfaces:**
- Consumes: `io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry` (existing, has `resolvePayloadClass(String) -> Optional<Class<?>>`).
- Produces: `NotificationCategories.UNCATEGORIZED = "uncategorized"`; `resolve(String dataType) -> String`; `categoryKeys() -> Set<String>`; `label(String categoryKey) -> String`; `description(String categoryKey) -> String`; `typesWithNoPayloadMapping(NotificationDataTypeRegistry) -> Set<String>`. Consumed by Task 5 (`NotificationDelivery`), Task 6 (plugin bootstrap), Task 9 (`PreferenceDialogs`).

- [ ] **Step 1: Write the failing test**

`core/src/test/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoriesTest.java`:

```java
package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

class NotificationCategoriesTest {

    private static NotificationCategories build(Map<String, NotificationCategoryDefinition> categories) {
        return new NotificationCategories(
                new NotificationCategoriesConfig("Other", categories), Logger.getLogger("test"));
    }

    @Test
    void resolvesConfiguredType() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals("economy", categories.resolve("mail"));
    }

    @Test
    void unclaimedTypeFallsBackToUncategorized() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals(NotificationCategories.UNCATEGORIZED, categories.resolve("nothing-registered"));
    }

    @Test
    void duplicateClaimKeepsTheFirstCategoryInConfigOrder() {
        Map<String, NotificationCategoryDefinition> ordered = new LinkedHashMap<>();
        ordered.put("economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")));
        ordered.put("moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("mail")));
        NotificationCategories categories = build(ordered);

        Assertions.assertEquals("economy", categories.resolve("mail"));
    }

    @Test
    void categoryKeysIncludesUncategorized() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))));

        Assertions.assertEquals(Set.of("economy", "uncategorized"), categories.categoryKeys());
    }

    @Test
    void labelAndDescriptionLookup() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals("Economy", categories.label("economy"));
        Assertions.assertEquals("Shop and payments", categories.description("economy"));
        Assertions.assertEquals("Other", categories.label(NotificationCategories.UNCATEGORIZED));
    }

    @Test
    void unknownCategoryKeyFallsBackToTheKeyItself() {
        NotificationCategories categories = build(Map.of());

        Assertions.assertEquals("vanished", categories.label("vanished"));
    }

    @Test
    void typesWithNoPayloadMappingDetectsMissingRegistrations() {
        Map<String, NotificationCategoryDefinition> config = Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")),
                "moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("warning")));
        NotificationCategories categories = build(config);

        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping("mail", String.class);

        Assertions.assertEquals(Set.of("warning"), categories.typesWithNoPayloadMapping(registry));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.category.NotificationCategoriesTest"`
Expected: compile failure — none of the three classes exist yet.

- [ ] **Step 3: Create the config records**

`core/src/main/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoryDefinition.java`:

```java
package io.github.md5sha256.playernotifications.core.category;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;

/**
 * One category declared in {@code categories.yml}: a player-facing label and description, and the
 * registry data types it groups together.
 */
@ConfigSerializable
public record NotificationCategoryDefinition(
        @Setting @Required String label,
        @Setting @Required String description,
        @Setting @Required List<String> types
) {
}
```

`core/src/main/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoriesConfig.java`:

```java
package io.github.md5sha256.playernotifications.core.category;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Map;

/**
 * Root of {@code categories.yml}: the label shown for data types no category claims, plus every
 * declared category keyed by its category key.
 */
@ConfigSerializable
public record NotificationCategoriesConfig(
        @Setting("uncategorized-label")
        @Required
        String uncategorizedLabel,

        @Setting
        @Required
        Map<String, NotificationCategoryDefinition> categories
) {
}
```

- [ ] **Step 4: Create `NotificationCategories`**

```java
package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves a registered {@code dataType} to the player-facing category it belongs to, as declared in
 * {@code categories.yml}. A data type no category claims resolves to {@link #UNCATEGORIZED}, which is
 * always a real, selectable category — a newly installed module's notifications are configurable
 * immediately, without an operator editing config first.
 *
 * <p>A data type claimed by two categories is a config error: the first category in declaration order
 * wins, and the collision is logged as a warning.
 */
public final class NotificationCategories {

    public static final String UNCATEGORIZED = "uncategorized";

    private final Map<String, String> dataTypeToCategory;
    private final Map<String, NotificationCategoryDefinition> definitions;
    private final String uncategorizedLabel;

    public NotificationCategories(@NotNull NotificationCategoriesConfig config, @NotNull Logger logger) {
        this.uncategorizedLabel = config.uncategorizedLabel();
        this.definitions = Map.copyOf(config.categories());

        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<String, NotificationCategoryDefinition> entry : config.categories().entrySet()) {
            String categoryKey = entry.getKey();
            for (String dataType : entry.getValue().types()) {
                String existing = mapping.putIfAbsent(dataType, categoryKey);
                if (existing != null) {
                    logger.warning("Data type '" + dataType + "' is claimed by both category '" + existing
                            + "' and '" + categoryKey + "'; keeping '" + existing + "'");
                }
            }
        }
        this.dataTypeToCategory = Map.copyOf(mapping);
    }

    /**
     * The category the given data type belongs to, or {@link #UNCATEGORIZED} if no category claims it.
     */
    @NotNull
    public String resolve(@NotNull String dataType) {
        return this.dataTypeToCategory.getOrDefault(dataType, UNCATEGORIZED);
    }

    /**
     * Every selectable category key, including {@link #UNCATEGORIZED}.
     */
    @NotNull
    public Set<String> categoryKeys() {
        Set<String> keys = new LinkedHashSet<>(this.definitions.keySet());
        keys.add(UNCATEGORIZED);
        return Set.copyOf(keys);
    }

    /**
     * The player-facing label for a category key, falling back to the key itself if the category has
     * vanished from config since a player last saw it.
     */
    @NotNull
    public String label(@NotNull String categoryKey) {
        if (UNCATEGORIZED.equals(categoryKey)) {
            return this.uncategorizedLabel;
        }
        NotificationCategoryDefinition definition = this.definitions.get(categoryKey);
        return definition != null ? definition.label() : categoryKey;
    }

    /**
     * The player-facing description for a category key, or an empty string for {@link #UNCATEGORIZED}
     * or a vanished category.
     */
    @NotNull
    public String description(@NotNull String categoryKey) {
        if (UNCATEGORIZED.equals(categoryKey)) {
            return "";
        }
        NotificationCategoryDefinition definition = this.definitions.get(categoryKey);
        return definition != null ? definition.description() : "";
    }

    /**
     * Every data type declared under some category in config that the given registry has no payload
     * mapping for. Intended to be checked once at startup, after feature modules have registered their
     * payload mappings, so an operator sees a standing misconfiguration rather than a silent no-op.
     */
    @NotNull
    public Set<String> typesWithNoPayloadMapping(@NotNull NotificationDataTypeRegistry registry) {
        Set<String> unmapped = new HashSet<>();
        for (String dataType : this.dataTypeToCategory.keySet()) {
            if (registry.resolvePayloadClass(dataType).isEmpty()) {
                unmapped.add(dataType);
            }
        }
        return Set.copyOf(unmapped);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.category.NotificationCategoriesTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/github/md5sha256/playernotifications/core/category/ \
        core/src/test/java/io/github/md5sha256/playernotifications/core/category/
git commit -m "feat: add config-driven notification category resolution"
```

---

### Task 3: Category-aware preference lookup in the API

**Files:**
- Modify: `api/src/main/java/io/github/md5sha256/playernotifications/api/render/NotificationPreferences.java`
- Modify: `api/src/main/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessor.java`
- Test: `api/src/test/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessorTest.java` (add one test; existing tests must keep passing unchanged)

**Interfaces:**
- Produces: `NotificationPreferences.preferredMedia(UUID, String category)` (default method); `RenderingProcessor` 5-arg constructor `(NotificationRenderer<T>, NotificationSinkRegistry, NotificationPreferences, @Nullable String category, Logger)`. Consumed by Task 5 (`NotificationDelivery`).
- Consumes: nothing new from other tasks.

- [ ] **Step 1: Write the failing test**

Add to `RenderingProcessorTest.java` (new test method; keep every existing method and the existing 4-arg `fixedPreferences` helper untouched):

```java
    @Test
    @DisplayName("passes the given category through to the two-argument preference lookup")
    void passesCategoryToPreferenceLookup() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);

        List<String> categoriesSeen = new ArrayList<>();
        NotificationPreferences preferences = new NotificationPreferences() {
            @Override
            public Set<String> preferredMedia(UUID player) {
                categoriesSeen.add(null);
                return Set.of();
            }

            @Override
            public Set<String> preferredMedia(UUID player, String category) {
                categoriesSeen.add(category);
                return Set.of("chat");
            }
        };

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, preferences, "economy", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.DELETE,
                processor.receiveNotification("payload", TARGET));
        Assertions.assertEquals(List.of("economy"), categoriesSeen);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.render.RenderingProcessorTest"`
Expected: compile failure — `NotificationPreferences.preferredMedia(UUID, String)` and the 5-arg `RenderingProcessor` constructor do not exist yet.

- [ ] **Step 3: Add the default method to `NotificationPreferences`**

```java
package io.github.md5sha256.playernotifications.api.render;

import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves the set of media a player wants to receive rendered notifications through. Set-valued:
 * multi-medium delivery (e.g. {@code chat} + {@code discord}) is the first-class case, not an edge
 * case.
 */
public interface NotificationPreferences {

    /**
     * Returns the media keys the given player currently prefers, with no notification-category context.
     * Implementations are expected to fall back to some configured default set when the player has
     * expressed no preference, so a player is never silently cut off from all notifications.
     */
    @NotNull Set<String> preferredMedia(@NotNull UUID player);

    /**
     * Returns the media keys the given player prefers for the given notification category. The default
     * implementation ignores the category and delegates to {@link #preferredMedia(UUID)}, so existing
     * single-argument implementations (including lambdas) keep compiling unchanged.
     */
    @NotNull
    default Set<String> preferredMedia(@NotNull UUID player, @NotNull String category) {
        return preferredMedia(player);
    }

}
```

- [ ] **Step 4: Add the category-aware constructor to `RenderingProcessor`**

Replace the existing constructor and the field declarations at the top of the class:

```java
    private final NotificationRenderer<T> renderer;
    private final NotificationSinkRegistry sinks;
    private final NotificationPreferences preferences;
    private final String category;
    private final Logger logger;

    /**
     * Constructs a processor with no notification-category context: preferred media are resolved via
     * {@link NotificationPreferences#preferredMedia(UUID)}.
     */
    public RenderingProcessor(@NotNull NotificationRenderer<T> renderer,
                              @NotNull NotificationSinkRegistry sinks,
                              @NotNull NotificationPreferences preferences,
                              @NotNull Logger logger) {
        this(renderer, sinks, preferences, null, logger);
    }

    /**
     * Constructs a processor that resolves preferred media for the given notification category via
     * {@link NotificationPreferences#preferredMedia(UUID, String)}. Pass {@code null} for
     * {@code category} to fall back to the category-agnostic lookup.
     */
    public RenderingProcessor(@NotNull NotificationRenderer<T> renderer,
                              @NotNull NotificationSinkRegistry sinks,
                              @NotNull NotificationPreferences preferences,
                              @Nullable String category,
                              @NotNull Logger logger) {
        this.renderer = renderer;
        this.sinks = sinks;
        this.preferences = preferences;
        this.category = category;
        this.logger = logger;
    }
```

Add the import `org.jetbrains.annotations.Nullable`.

Update the first line of `receiveNotification` to resolve media through the category when present:

```java
    @Override
    public @NotNull NotificationDisposition receiveNotification(@NotNull T payload, @NotNull UUID target) {
        Set<String> media = this.category != null
                ? this.preferences.preferredMedia(target, this.category)
                : this.preferences.preferredMedia(target);
        if (media.isEmpty()) {
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.render.RenderingProcessorTest"`
Expected: PASS (all existing tests plus the new one — 10 tests total).

- [ ] **Step 6: Run the full `:api:test` suite to confirm nothing else broke**

Run: `./gradlew :api:test`
Expected: BUILD SUCCESSFUL, 13 tests (12 existing + 1 new).

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/github/md5sha256/playernotifications/api/render/NotificationPreferences.java \
        api/src/main/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessor.java \
        api/src/test/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessorTest.java
git commit -m "feat: thread notification category through RenderingProcessor"
```

---

### Task 4: Rewrite `DatabaseNotificationPreferences` for categories

**Files:**
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/DatabaseNotificationPreferences.java`
- Modify: `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java` (rewrite the `DatabaseNotificationPreferencesTests` nested class)

**Interfaces:**
- Consumes: `PlayerNotificationPreferenceMapper` from Task 1; `NotificationPreferences.preferredMedia(UUID, String)` default method from Task 3.
- Produces: `DatabaseNotificationPreferences.ALL_CATEGORIES_KEY = "*"`; `preferredMedia(UUID, String) -> Set<String>` (exact category, else `*`, else configured default); `effectiveMediaByCategory(UUID, Set<String>) -> Map<String, Set<String>>`; `explicitlyConfiguredCategories(UUID, Set<String>) -> Set<String>`; `applyChanges(UUID, Map<String, Set<String>> explicitMedia, Set<String> categoriesToReset)`; `muteAll(UUID, Set<String> categoryKeys)`; `resetAll(UUID)`. **Removes** `setPreferredMedia(UUID, Set<String>)` — no longer used once Task 11 deletes the old dialog. Consumed by Task 6 (plugin bootstrap), Task 9 (`PreferenceDialogs`), Task 10 (dialogs), Task 11 (router).

- [ ] **Step 1: Write the failing tests**

Replace the `DatabaseNotificationPreferencesTests` nested class in `PlayerNotificationPreferenceTest.java`:

```java
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
```

Add the import `java.util.Map` to the test file if not already present.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest"`
Expected: compile failure — `applyChanges`, `effectiveMediaByCategory`, `explicitlyConfiguredCategories`, `muteAll`, `resetAll`, and `ALL_CATEGORIES_KEY` do not exist yet.

- [ ] **Step 3: Rewrite `DatabaseNotificationPreferences`**

```java
package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link NotificationPreferences} backed by the {@code PlayerNotificationPreference} table, resolved
 * per notification category. A player with no rows for a category falls back to the {@link
 * #ALL_CATEGORIES_KEY} rows (the pre-migration state, or a blanket choice), then to a configurable
 * default medium set, so a player is never silently cut off from all notifications.
 */
public class DatabaseNotificationPreferences implements NotificationPreferences {

    /**
     * Reserved category key meaning "applies to any category not otherwise configured". Only the V3
     * migration writes it (fanning out pre-migration rows); the dialogs never write it directly.
     */
    public static final String ALL_CATEGORIES_KEY = "*";

    private final Database database;
    private final Set<String> defaultMedia;

    public DatabaseNotificationPreferences(@NotNull Database database, @NotNull Collection<String> defaultMedia) {
        this.database = database;
        this.defaultMedia = Set.copyOf(defaultMedia);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
        return preferredMedia(player, ALL_CATEGORIES_KEY);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String category) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            if (!ALL_CATEGORIES_KEY.equals(category)) {
                List<String> exact = mapper.selectByPlayerAndCategory(player, category);
                if (!exact.isEmpty()) {
                    return Set.copyOf(exact);
                }
            }
            List<String> fallback = mapper.selectByPlayerAndCategory(player, ALL_CATEGORIES_KEY);
            if (!fallback.isEmpty()) {
                return Set.copyOf(fallback);
            }
            return this.defaultMedia;
        }
    }

    /**
     * Resolves the effective media for every given category in one database round trip: exact rows,
     * else the {@link #ALL_CATEGORIES_KEY} fallback, else the configured default. Used to load a
     * preference edit session's starting matrix.
     */
    public @NotNull Map<String, Set<String>> effectiveMediaByCategory(@NotNull UUID player,
                                                                       @NotNull Set<String> categoryKeys) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byCategory = groupByCategory(wrapper, player);
            Set<String> fallback = byCategory.getOrDefault(ALL_CATEGORIES_KEY, this.defaultMedia);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String category : categoryKeys) {
                Set<String> exact = byCategory.get(category);
                result.put(category, exact != null ? Set.copyOf(exact) : Set.copyOf(fallback));
            }
            return Map.copyOf(result);
        }
    }

    /**
     * Which of the given categories the player has exact stored rows for, as opposed to inheriting the
     * {@link #ALL_CATEGORIES_KEY} fallback or the configured default. Used to label a category
     * "server default" in the preferences dialogs.
     */
    public @NotNull Set<String> explicitlyConfiguredCategories(@NotNull UUID player,
                                                                @NotNull Set<String> categoryKeys) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byCategory = groupByCategory(wrapper, player);
            Set<String> configured = new HashSet<>();
            for (String category : categoryKeys) {
                if (byCategory.containsKey(category)) {
                    configured.add(category);
                }
            }
            return Set.copyOf(configured);
        }
    }

    private static @NotNull Map<String, Set<String>> groupByCategory(@NotNull SqlSessionWrapper wrapper,
                                                                       @NotNull UUID player) {
        List<PlayerNotificationPreferenceEntity> rows =
                wrapper.playerNotificationPreferenceMapper().selectByPlayer(player);
        Map<String, Set<String>> byCategory = new HashMap<>();
        for (PlayerNotificationPreferenceEntity row : rows) {
            byCategory.computeIfAbsent(row.category(), key -> new TreeSet<>()).add(row.medium());
        }
        return byCategory;
    }

    /**
     * Applies a batch of staged changes in one transaction. Categories in {@code categoriesToReset} have
     * their rows deleted, falling back to {@link #ALL_CATEGORIES_KEY}/the configured default again.
     * Every entry in {@code explicitMedia} wholesale-replaces that category's rows; an empty set is not
     * a valid value here — callers encode a mute as {@code {"none"}}.
     */
    public void applyChanges(@NotNull UUID player,
                             @NotNull Map<String, Set<String>> explicitMedia,
                             @NotNull Set<String> categoriesToReset) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            for (String category : categoriesToReset) {
                mapper.deleteByPlayerAndCategory(player, category);
            }
            for (Map.Entry<String, Set<String>> entry : explicitMedia.entrySet()) {
                mapper.deleteByPlayerAndCategory(player, entry.getKey());
                mapper.insertPreferences(player, entry.getKey(), entry.getValue());
            }
            wrapper.session().commit();
        }
    }

    /**
     * Immediately mutes every given category for the player in one transaction, storing an explicit
     * {@code none} row for each. Used by {@code /notifications mute}.
     */
    public void muteAll(@NotNull UUID player, @NotNull Set<String> categoryKeys) {
        Map<String, Set<String>> mutes = new LinkedHashMap<>();
        for (String category : categoryKeys) {
            mutes.put(category, Set.of(NullSink.MEDIUM_KEY));
        }
        applyChanges(player, mutes, Set.of());
    }

    /**
     * Immediately clears every stored row for the player, across every category. Used by
     * {@code /notifications reset}.
     */
    public void resetAll(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.playerNotificationPreferenceMapper().deleteByPlayer(player);
            wrapper.session().commit();
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest"`
Expected: PASS (5 mapper tests + 7 preference tests = 12 tests).

- [ ] **Step 5: Run the full `:core:test` suite**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL. Count `*.xml` result files, not `TEST-*.xml`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/github/md5sha256/playernotifications/core/DatabaseNotificationPreferences.java \
        core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java
git commit -m "feat: resolve and stage notification preferences per category"
```

---

### Task 5: Categorised dispatch in `NotificationDelivery`

**Files:**
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/NotificationDelivery.java`
- Test: `core/src/test/java/io/github/md5sha256/playernotifications/core/database/NotificationDeliveryPrecedenceTest.java` (add one test; existing tests must keep passing unchanged)

**Interfaces:**
- Consumes: `NotificationCategories.resolve(String) -> String` from Task 2; `RenderingProcessor` 5-arg constructor from Task 3.
- Produces: `NotificationDelivery` 6-arg constructor `(Database, NotificationDataTypeRegistry, @Nullable NotificationSinkRegistry, @Nullable NotificationPreferences, @Nullable NotificationCategories, Logger)`. The existing 3-arg and 5-arg constructors are unchanged in behavior (the 5-arg one now delegates to the 6-arg one with `categories = null`). Consumed by Task 6 (plugin bootstrap).

- [ ] **Step 1: Write the failing test**

Add to `NotificationDeliveryPrecedenceTest.java` (new test method; keep every existing method unchanged):

```java
    @Test
    @DisplayName("the rendering path resolves preferred media through the notification's category")
    void rendererPathResolvesCategory() {
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(chat);

        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(TYPE, String.class);
        registry.registerSerializer(String.class, new JacksonPayloadSerializer<>(new ObjectMapper(), String.class));
        registry.registerRenderer(String.class, (payload, target) -> new RenderableNotification(
                Component.text("title"), Component.text(payload)));

        List<String> categoriesSeen = new ArrayList<>();
        io.github.md5sha256.playernotifications.api.render.NotificationPreferences preferences =
                new io.github.md5sha256.playernotifications.api.render.NotificationPreferences() {
                    @Override
                    public Set<String> preferredMedia(UUID player) {
                        return Set.of();
                    }

                    @Override
                    public Set<String> preferredMedia(UUID player, String category) {
                        categoriesSeen.add(category);
                        return Set.of("chat");
                    }
                };

        io.github.md5sha256.playernotifications.core.category.NotificationCategories categories =
                new io.github.md5sha256.playernotifications.core.category.NotificationCategories(
                        new io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig(
                                "Other", java.util.Map.of(
                                        "economy", new io.github.md5sha256.playernotifications.core.category.NotificationCategoryDefinition(
                                                "Economy", "desc", List.of(TYPE)))),
                        Logger.getLogger("test"));

        NotificationDelivery delivery = new NotificationDelivery(
                database, registry, sinks, preferences, categories, Logger.getLogger("test"));
        insert("category-resolved", DUE, PLAYER);

        delivery.deliver(PLAYER, NOW);

        Assertions.assertEquals(List.of("economy"), categoriesSeen);
        Assertions.assertEquals(1, chat.received.size());
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.NotificationDeliveryPrecedenceTest"`
Expected: compile failure — the 6-arg `NotificationDelivery` constructor does not exist yet.

- [ ] **Step 3: Update `NotificationDelivery`**

Add the import `io.github.md5sha256.playernotifications.core.category.NotificationCategories;` alongside the existing imports.

Replace the constructor block and add the field:

```java
    private final Database database;
    private final NotificationDataTypeRegistry registry;
    private final NotificationSinkRegistry sinkRegistry;
    private final NotificationPreferences preferences;
    private final NotificationCategories categories;
    private final Logger logger;

    /**
     * Constructs a delivery loop with no rendering path: only explicitly registered
     * {@link NotificationProcessor}s are dispatched. A payload with only a {@link NotificationRenderer}
     * registered is retained, exactly as one with neither.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @NotNull Logger logger) {
        this(database, registry, null, null, null, logger);
    }

    /**
     * Constructs a delivery loop with the rendering path enabled but no category resolution: a payload
     * with a registered {@link NotificationRenderer} (and no explicit processor) is dispatched through a
     * {@link RenderingProcessor} built from the given sink registry and preferences, using the
     * category-agnostic {@link NotificationPreferences#preferredMedia(UUID)} lookup.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @Nullable NotificationSinkRegistry sinkRegistry,
                                @Nullable NotificationPreferences preferences,
                                @NotNull Logger logger) {
        this(database, registry, sinkRegistry, preferences, null, logger);
    }

    /**
     * Constructs a delivery loop with the rendering path and category resolution both enabled: each
     * notification's {@code notifPayloadType} is resolved to a category via {@code categories}, and
     * preferred media are looked up per category through
     * {@link NotificationPreferences#preferredMedia(UUID, String)}.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @Nullable NotificationSinkRegistry sinkRegistry,
                                @Nullable NotificationPreferences preferences,
                                @Nullable NotificationCategories categories,
                                @NotNull Logger logger) {
        this.database = database;
        this.registry = registry;
        this.sinkRegistry = sinkRegistry;
        this.preferences = preferences;
        this.categories = categories;
        this.logger = logger;
    }
```

Update the rendering branch inside `dispatch`:

```java
        // Otherwise, dispatch through the rendering path if a renderer is registered and the delivery
        // loop was constructed with the sink registry and preferences it requires.
        Optional<? extends NotificationRenderer<?>> renderer =
                registry.getRenderer(notification.notifPayloadType());
        if (renderer.isPresent() && this.sinkRegistry != null && this.preferences != null) {
            Object payload = decodePayload(notification.notifPayload(), payloadClass.get());
            if (payload == null) {
                return NotificationDisposition.RETAIN;
            }
            String category = this.categories != null
                    ? this.categories.resolve(notification.notifPayloadType())
                    : null;
            NotificationProcessor<?> renderingProcessor =
                    new RenderingProcessor<>(castRenderer(renderer.get()), this.sinkRegistry,
                            this.preferences, category, this.logger);
            return invoke(renderingProcessor, payload, target);
        }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.NotificationDeliveryPrecedenceTest"`
Expected: PASS (4 tests: 3 existing + 1 new).

- [ ] **Step 5: Run the full `:core:test` suite to confirm no regressions**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL. All previously-passing tests (`NotificationDeliveryTest`, `TypedPayloadDeliveryTest`, `ThirdPartyPayloadClassLoaderTest`, etc.) still pass unchanged, since they use the 3-arg constructor untouched by this task.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/github/md5sha256/playernotifications/core/NotificationDelivery.java \
        core/src/test/java/io/github/md5sha256/playernotifications/core/database/NotificationDeliveryPrecedenceTest.java
git commit -m "feat: resolve notification category during dispatch"
```

---

### Task 6: Load `categories.yml` and wire it into the plugin bootstrap

**Files:**
- Create: `platform/paper-plugin/src/main/resources/categories.yml`
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`

**Interfaces:**
- Consumes: `NotificationCategoriesConfig`, `NotificationCategories` from Task 2; `NotificationDelivery` 6-arg constructor from Task 5.
- Produces: `PlayerNotificationsPlugin.categories() -> NotificationCategories` accessor. Consumed by Task 11 (router/command wiring uses `this.categories` directly within the same class, not via the accessor).

This task has no automated test — `PlayerNotificationsPlugin` has no test source set and requires a live Paper server to exercise. Verify by hand: `./gradlew build` compiles, and a full manual check happens in Task 12.

- [ ] **Step 1: Create the default `categories.yml`**

`platform/paper-plugin/src/main/resources/categories.yml`:

```yaml
# PlayerNotifications categories
#
# Groups registered notification data types (see each module's documentation for its data type string)
# into player-facing categories. A data type not listed under any category here falls back to the
# "uncategorized-label" category below, so it is always configurable even before an operator edits this
# file.
uncategorized-label: "Other"

categories:
  mail:
    label: "Mail"
    description: "Essentials mail and other direct messages"
    types:
      - essentials-mail
```

- [ ] **Step 2: Add the `categories` field and loader to `PlayerNotificationsPlugin`**

Add the import `io.github.md5sha256.playernotifications.core.category.NotificationCategories;` and `io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig;`.

Add a field alongside the existing ones:

```java
    private NotificationCategories categories;
```

Add an accessor alongside the existing accessors:

```java
    /**
     * Resolves a registered {@code dataType} to its player-facing category, as declared in
     * {@code categories.yml}.
     */
    @NotNull
    public NotificationCategories categories() {
        return this.categories;
    }
```

Add the loader method alongside `loadPluginSettings()`:

```java
    private NotificationCategories loadCategories() throws IOException {
        ConfigurationNode root = copyDefaultsYaml("categories");
        NotificationCategoriesConfig config = root.get(NotificationCategoriesConfig.class);
        if (config == null) {
            throw new IOException("categories.yml could not be deserialized into NotificationCategoriesConfig");
        }
        return new NotificationCategories(config, getLogger());
    }
```

- [ ] **Step 3: Wire loading into `onEnable` and use the 6-arg `NotificationDelivery` constructor**

In the config-loading `try` block at the top of `onEnable`, load categories alongside the other settings:

```java
        DatabaseSettings databaseSettings;
        PluginSettings pluginSettings;
        NotificationCategories categories;
        try {
            databaseSettings = loadDatabaseSettings();
            pluginSettings = loadPluginSettings();
            categories = loadCategories();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load configuration; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.categories = categories;
```

Update the `NotificationDelivery` construction to use the new 6-arg constructor:

```java
        this.notificationDelivery = new NotificationDelivery(
                mariaDatabase,
                this.notificationService.dataTypeRegistry(),
                this.sinkRegistry,
                this.preferences,
                this.categories,
                getLogger()
        );
```

- [ ] **Step 4: Log unmapped category types after modules start**

At the end of `onEnable`, after `startModules();` and before the "PlayerNotifications enabled" log line, add:

```java
        warnAboutUnmappedCategoryTypes();
```

Add the method:

```java
    /**
     * Logs a warning naming every data type declared under some category in {@code categories.yml} that
     * no registered payload mapping exists for, once feature modules have had a chance to register
     * theirs. A standing misconfiguration an operator should fix, not a startup-order race — modules
     * that register later than this check will simply be caught on the next server restart.
     */
    private void warnAboutUnmappedCategoryTypes() {
        var unmapped = this.categories.typesWithNoPayloadMapping(this.notificationService.dataTypeRegistry());
        if (!unmapped.isEmpty()) {
            getLogger().warning(
                    "categories.yml references data types with no registered payload mapping: " + unmapped);
        }
    }
```

- [ ] **Step 5: Verify the module builds**

Run: `./gradlew :platform:paper-plugin:build`
Expected: BUILD SUCCESSFUL. `NotificationPreferencesDialog`'s constructor call in `registerCommands()` is untouched by this task and still compiles against the old class, which Task 11 removes.

- [ ] **Step 6: Commit**

```bash
git add platform/paper-plugin/src/main/resources/categories.yml \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java
git commit -m "feat: load categories.yml and wire it into notification delivery"
```

---

### Task 7: `PreferenceEditSession` — the staged edit matrix

**Files:**
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSession.java`
- Test: `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSessionTest.java`

**Interfaces:**
- Consumes: `io.github.md5sha256.playernotifications.api.render.sink.NullSink.MEDIUM_KEY` (existing, `"none"`).
- Produces: `PreferenceEditSession(UUID player, Map<String, Set<String>> initialEffectiveMedia, Set<String> explicitAtLoad, Set<String> fallbackMedia, Instant now)`; `player()`, `mediaFor(String)`, `setCategoryMedia(String, Set<String>, Instant)`, `resetCategory(String, Instant)`, `toggleCategoryMedium(String, String, boolean, Instant)`, `isDirty()`, `dirtyCount()`, `dirtyCategories()`, `categoriesToReset()`, `explicitChanges() -> Map<String, Set<String>>`, `isUsingServerDefault(String)`, `isExpired(Instant, Duration)`. Consumed by Task 8 (`PreferenceSessionManager`), Task 9 (`PreferenceDialogs`), Task 10 (dialogs).

This module has no `src/test` directory yet; creating one under `platform/paper-plugin/src/test/java/...` is picked up automatically by the `player-notifications-conventions` JUnit setup — no `build.gradle.kts` change needed.

- [ ] **Step 1: Write the failing test**

`platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSessionTest.java`:

```java
package io.github.md5sha256.playernotifications.paper.preferences.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class PreferenceEditSessionTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private static PreferenceEditSession newSession(Map<String, Set<String>> initial, Set<String> explicitAtLoad) {
        return new PreferenceEditSession(PLAYER, initial, explicitAtLoad, Set.of("chat"), NOW);
    }

    @Test
    void startsClean() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        Assertions.assertFalse(session.isDirty());
        Assertions.assertEquals(0, session.dirtyCount());
        Assertions.assertEquals(Set.of("chat"), session.mediaFor("economy"));
    }

    @Test
    void setCategoryMediaMarksItDirty() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        session.setCategoryMedia("economy", Set.of("discord"), NOW);

        Assertions.assertTrue(session.isDirty());
        Assertions.assertEquals(Set.of("economy"), session.dirtyCategories());
        Assertions.assertEquals(Set.of("discord"), session.mediaFor("economy"));
    }

    @Test
    void toggleCategoryMediumAddsAndRemoves() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        session.toggleCategoryMedium("economy", "discord", true, NOW);
        Assertions.assertEquals(Set.of("chat", "discord"), session.mediaFor("economy"));

        session.toggleCategoryMedium("economy", "chat", false, NOW);
        Assertions.assertEquals(Set.of("discord"), session.mediaFor("economy"));
    }

    @Test
    void emptyingACategoryStagesAMute() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        session.setCategoryMedia("economy", Set.of(), NOW);

        Assertions.assertEquals(Map.of("economy", Set.of("none")), session.explicitChanges());
        Assertions.assertEquals(Set.of(), session.categoriesToReset());
    }

    @Test
    void resetCategoryStagesAResetUsingFallbackMedia() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("discord")), Set.of("economy"));

        session.resetCategory("economy", NOW);

        Assertions.assertEquals(Set.of("chat"), session.mediaFor("economy"));
        Assertions.assertEquals(Set.of("economy"), session.categoriesToReset());
        Assertions.assertTrue(session.explicitChanges().isEmpty());
    }

    @Test
    void reSettingMediaAfterAResetCancelsTheReset() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("discord")), Set.of("economy"));

        session.resetCategory("economy", NOW);
        session.setCategoryMedia("economy", Set.of("dialog"), NOW);

        Assertions.assertEquals(Set.of(), session.categoriesToReset());
        Assertions.assertEquals(Map.of("economy", Set.of("dialog")), session.explicitChanges());
    }

    @Test
    void isUsingServerDefaultReflectsLoadStateAndStagedResets() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("discord"), "moderation", Set.of("chat")),
                Set.of("economy"));

        Assertions.assertFalse(session.isUsingServerDefault("economy"));
        Assertions.assertTrue(session.isUsingServerDefault("moderation"));

        session.resetCategory("economy", NOW);
        Assertions.assertTrue(session.isUsingServerDefault("economy"));

        session.setCategoryMedia("moderation", Set.of("discord"), NOW);
        Assertions.assertFalse(session.isUsingServerDefault("moderation"));
    }

    @Test
    void expiresAfterTheIdleTimeout() {
        PreferenceEditSession session = newSession(Map.of(), Set.of());

        Assertions.assertFalse(session.isExpired(NOW.plus(Duration.ofMinutes(5)), Duration.ofMinutes(15)));
        Assertions.assertTrue(session.isExpired(NOW.plus(Duration.ofMinutes(16)), Duration.ofMinutes(15)));
    }

    @Test
    void touchingResetsTheIdleClock() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")), Set.of("economy"));

        Instant later = NOW.plus(Duration.ofMinutes(10));
        session.setCategoryMedia("economy", Set.of("discord"), later);

        Assertions.assertFalse(session.isExpired(later.plus(Duration.ofMinutes(10)), Duration.ofMinutes(15)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSessionTest"`
Expected: compile failure — `PreferenceEditSession` does not exist yet.

- [ ] **Step 3: Implement `PreferenceEditSession`**

```java
package io.github.md5sha256.playernotifications.paper.preferences.session;

import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A player's in-progress edits to their category x medium preference matrix. Both preference dialogs
 * (by delivery method, by notification type) mutate the same session, so the two pivots can never
 * disagree, and nothing is written to the database until {@code Apply}.
 *
 * <p>Not thread-safe; callers (the dialog classes) only ever touch a session from the server main
 * thread.
 */
public final class PreferenceEditSession {

    private final UUID player;
    private final Map<String, Set<String>> media;
    private final Set<String> explicitAtLoad;
    private final Set<String> fallbackMedia;
    private final Set<String> dirtyCategories = new HashSet<>();
    private final Set<String> resetCategories = new HashSet<>();
    private Instant lastTouched;

    /**
     * @param initialEffectiveMedia the matrix as it would currently apply, per category (exact rows,
     *                              else the {@code *} fallback, else the configured default)
     * @param explicitAtLoad        the categories that had exact stored rows when this session was
     *                              loaded, used by {@link #isUsingServerDefault(String)}
     * @param fallbackMedia         the plain {@code *}/configured-default media, used to populate a
     *                              category when it is reset
     */
    public PreferenceEditSession(@NotNull UUID player,
                                 @NotNull Map<String, Set<String>> initialEffectiveMedia,
                                 @NotNull Set<String> explicitAtLoad,
                                 @NotNull Set<String> fallbackMedia,
                                 @NotNull Instant now) {
        this.player = player;
        this.media = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : initialEffectiveMedia.entrySet()) {
            this.media.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        this.explicitAtLoad = Set.copyOf(explicitAtLoad);
        this.fallbackMedia = Set.copyOf(fallbackMedia);
        this.lastTouched = now;
    }

    @NotNull
    public UUID player() {
        return this.player;
    }

    @NotNull
    public Set<String> mediaFor(@NotNull String category) {
        return Set.copyOf(this.media.getOrDefault(category, Set.of()));
    }

    /**
     * Overwrites one category's staged media, marking it dirty. An empty set stages a mute, not a
     * fall-through to the server default — only {@link #resetCategory(String, Instant)} does that.
     */
    public void setCategoryMedia(@NotNull String category, @NotNull Set<String> newMedia, @NotNull Instant now) {
        this.media.put(category, new TreeSet<>(newMedia));
        this.dirtyCategories.add(category);
        this.resetCategories.remove(category);
        this.lastTouched = now;
    }

    /**
     * Stages "use the server default" for one category: its staged media becomes the fallback media
     * captured at load time, and it is written by clearing its rows on {@code Apply} rather than by
     * writing the fallback media explicitly.
     */
    public void resetCategory(@NotNull String category, @NotNull Instant now) {
        this.media.put(category, new TreeSet<>(this.fallbackMedia));
        this.dirtyCategories.add(category);
        this.resetCategories.add(category);
        this.lastTouched = now;
    }

    /**
     * Toggles a single medium within a single category — the operation the "by delivery method" editor
     * performs on Save.
     */
    public void toggleCategoryMedium(@NotNull String category, @NotNull String medium, boolean enabled,
                                     @NotNull Instant now) {
        Set<String> current = new TreeSet<>(this.media.getOrDefault(category, Set.of()));
        if (enabled) {
            current.add(medium);
        } else {
            current.remove(medium);
        }
        setCategoryMedia(category, current, now);
    }

    public boolean isDirty() {
        return !this.dirtyCategories.isEmpty();
    }

    public int dirtyCount() {
        return this.dirtyCategories.size();
    }

    @NotNull
    public Set<String> dirtyCategories() {
        return Set.copyOf(this.dirtyCategories);
    }

    @NotNull
    public Set<String> categoriesToReset() {
        return Set.copyOf(this.resetCategories);
    }

    /**
     * Dirty categories that are explicit selections rather than resets, keyed to the media that should
     * be written wholesale. An empty selection is encoded as {@link NullSink#MEDIUM_KEY}.
     */
    @NotNull
    public Map<String, Set<String>> explicitChanges() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String category : this.dirtyCategories) {
            if (!this.resetCategories.contains(category)) {
                Set<String> selected = this.media.getOrDefault(category, Set.of());
                result.put(category, selected.isEmpty() ? Set.of(NullSink.MEDIUM_KEY) : Set.copyOf(selected));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Whether the given category is currently showing the server default rather than an explicit
     * choice — true if it was never explicitly configured and has not been touched, or if it has been
     * staged for reset.
     */
    public boolean isUsingServerDefault(@NotNull String category) {
        if (this.dirtyCategories.contains(category)) {
            return this.resetCategories.contains(category);
        }
        return !this.explicitAtLoad.contains(category);
    }

    public boolean isExpired(@NotNull Instant now, @NotNull Duration idleTimeout) {
        return Duration.between(this.lastTouched, now).compareTo(idleTimeout) > 0;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSessionTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSession.java \
        platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSessionTest.java
git commit -m "feat: add staged per-category preference edit session"
```

---

### Task 8: `PreferenceSessionManager` — per-player session lifecycle

**Files:**
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceSessionManager.java`
- Test: `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceSessionManagerTest.java`

**Interfaces:**
- Consumes: `PreferenceEditSession` from Task 7.
- Produces: `PreferenceSessionManager.IDLE_TIMEOUT = Duration.ofMinutes(15)`; `getOrCreate(UUID, Supplier<PreferenceEditSession>) -> PreferenceEditSession`; `get(UUID) -> Optional<PreferenceEditSession>`; `drop(UUID)`. Consumed by Task 9 (`PreferenceDialogs.withSession`), Task 10 (router), Task 11 (`PreferenceQuitListener`).

- [ ] **Step 1: Write the failing test**

`platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceSessionManagerTest.java`:

```java
package io.github.md5sha256.playernotifications.paper.preferences.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class PreferenceSessionManagerTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static PreferenceEditSession freshSession() {
        return new PreferenceEditSession(PLAYER, Map.of("economy", Set.of("chat")),
                Set.of("economy"), Set.of("chat"), Instant.now());
    }

    @Test
    void getOrCreateBuildsOnceThenReusesTheSameInstance() {
        PreferenceSessionManager manager = new PreferenceSessionManager();

        PreferenceEditSession first = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);
        PreferenceEditSession second = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        Assertions.assertSame(first, second);
    }

    @Test
    void getReturnsEmptyWhenNoSessionExists() {
        PreferenceSessionManager manager = new PreferenceSessionManager();

        Assertions.assertTrue(manager.get(PLAYER).isEmpty());
    }

    @Test
    void getReturnsTheStoredSession() {
        PreferenceSessionManager manager = new PreferenceSessionManager();
        PreferenceEditSession created = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        Optional<PreferenceEditSession> found = manager.get(PLAYER);

        Assertions.assertTrue(found.isPresent());
        Assertions.assertSame(created, found.get());
    }

    @Test
    void dropRemovesTheSession() {
        PreferenceSessionManager manager = new PreferenceSessionManager();
        manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        manager.drop(PLAYER);

        Assertions.assertTrue(manager.get(PLAYER).isEmpty());
    }

    @Test
    void getOrCreateRebuildsAnExpiredSession() {
        PreferenceSessionManager manager = new PreferenceSessionManager();
        Instant longAgo = Instant.now().minus(PreferenceSessionManager.IDLE_TIMEOUT).minusSeconds(60);
        PreferenceEditSession expired = new PreferenceEditSession(PLAYER, Map.of("economy", Set.of("chat")),
                Set.of("economy"), Set.of("chat"), longAgo);
        manager.getOrCreate(PLAYER, () -> expired);

        PreferenceEditSession rebuilt = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        Assertions.assertNotSame(expired, rebuilt);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManagerTest"`
Expected: compile failure — `PreferenceSessionManager` does not exist yet.

- [ ] **Step 3: Implement `PreferenceSessionManager`**

```java
package io.github.md5sha256.playernotifications.paper.preferences.session;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Holds one {@link PreferenceEditSession} per player who currently has a preferences dialog open. A
 * session left untouched for {@link #IDLE_TIMEOUT} is treated as gone: reopening the dialog starts
 * fresh from the database rather than resuming stale staged edits.
 */
public final class PreferenceSessionManager {

    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);

    private final Map<UUID, PreferenceEditSession> sessions = new ConcurrentHashMap<>();

    /**
     * Returns the player's current session if one exists and has not expired, otherwise builds a new one
     * via {@code factory} and stores it.
     */
    @NotNull
    public PreferenceEditSession getOrCreate(@NotNull UUID player, @NotNull Supplier<PreferenceEditSession> factory) {
        return this.sessions.compute(player, (uuid, existing) -> {
            Instant now = Instant.now();
            if (existing != null && !existing.isExpired(now, IDLE_TIMEOUT)) {
                return existing;
            }
            return factory.get();
        });
    }

    /**
     * The player's current session, or empty if none exists or it has expired. An expired session found
     * here is evicted.
     */
    @NotNull
    public Optional<PreferenceEditSession> get(@NotNull UUID player) {
        PreferenceEditSession session = this.sessions.get(player);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(Instant.now(), IDLE_TIMEOUT)) {
            this.sessions.remove(player);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void drop(@NotNull UUID player) {
        this.sessions.remove(player);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManagerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceSessionManager.java \
        platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceSessionManagerTest.java
git commit -m "feat: add per-player preference session lifecycle manager"
```

---

### Task 9: `PreferenceDialogs` — shared dialog helpers

**Files:**
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogs.java`
- Test: `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogsTest.java` (covers only the Bukkit-free static helpers — `selectableMedia`, `sortedCategoryKeys`, `inputKey`)

**Interfaces:**
- Consumes: `NotificationSinkRegistry`, `NullSink.MEDIUM_KEY` (existing, api module); `NotificationCategories` from Task 2; `DatabaseNotificationPreferences` from Task 4; `PreferenceSessionManager`, `PreferenceEditSession` from Tasks 7–8.
- Produces: `PreferenceDialogs.CALLBACK_LIFETIME`; `callbackOptions() -> ClickCallback.Options`; `message(Plugin, Player, Component)`; `selectableMedia(NotificationSinkRegistry) -> List<String>`; `sortedCategoryKeys(NotificationCategories) -> List<String>`; `mediumLabel(NotificationSinkRegistry, String) -> Component`; `categoryLabel(NotificationCategories, String) -> Component`; `inputKey(String prefix, int index) -> String`; `withSession(Plugin, PreferenceSessionManager, NotificationCategories, DatabaseNotificationPreferences, Player, Consumer<PreferenceEditSession>)`. Consumed by Task 10 (all five dialog classes and the router).

- [ ] **Step 1: Write the failing test for the pure-logic helpers**

`platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogsTest.java`:

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoryDefinition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

class PreferenceDialogsTest {

    private static NotificationSink stubSink(String key) {
        return new NotificationSink() {
            @Override
            public String mediumKey() {
                return key;
            }

            @Override
            public DeliveryResult deliver(RenderableNotification notification, UUID target) {
                return DeliveryResult.DELIVERED;
            }
        };
    }

    @Test
    void selectableMediaExcludesNullSinkAndSortsAlphabetically() {
        NotificationSinkRegistry registry = new NotificationSinkRegistry();
        registry.registerSink(stubSink("discord"));
        registry.registerSink(stubSink("chat"));
        registry.registerSink(new NullSink());

        List<String> selectable = PreferenceDialogs.selectableMedia(registry);

        Assertions.assertEquals(List.of("chat", "discord"), selectable);
    }

    @Test
    void sortedCategoryKeysIncludesUncategorizedAndSorts() {
        NotificationCategories categories = new NotificationCategories(
                new NotificationCategoriesConfig("Other", Map.of(
                        "moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("warning")),
                        "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")))),
                Logger.getLogger("test"));

        List<String> sorted = PreferenceDialogs.sortedCategoryKeys(categories);

        Assertions.assertEquals(List.of("economy", "moderation", "uncategorized"), sorted);
    }

    @Test
    void inputKeyIsPositionalAndPrefixed() {
        Assertions.assertEquals("medium_0", PreferenceDialogs.inputKey("medium", 0));
        Assertions.assertEquals("category_3", PreferenceDialogs.inputKey("category", 3));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogsTest"`
Expected: compile failure — `PreferenceDialogs` does not exist yet.

- [ ] **Step 3: Implement `PreferenceDialogs`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Helpers shared by the five preference dialog screens and their router: session loading, labels, and
 * the callback options every button uses.
 */
final class PreferenceDialogs {

    /**
     * How long a dialog button stays clickable after the dialog is shown. A dialog left open past this
     * makes its buttons inert; the player simply reopens it.
     */
    static final Duration CALLBACK_LIFETIME = Duration.ofHours(1);

    private PreferenceDialogs() {
    }

    @NotNull
    static ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(CALLBACK_LIFETIME)
                .build();
    }

    static void message(@NotNull Plugin plugin, @NotNull Player player, @NotNull Component component) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(component);
            }
        });
    }

    /**
     * The media offered as checkboxes/buttons: every registered medium except {@link NullSink}, whose
     * meaning is already carried by an empty selection. Sorted so dialog row order is stable between
     * openings.
     */
    @NotNull
    static List<String> selectableMedia(@NotNull NotificationSinkRegistry sinkRegistry) {
        Set<String> sorted = new TreeSet<>(sinkRegistry.registeredMedia());
        sorted.remove(NullSink.MEDIUM_KEY);
        return List.copyOf(sorted);
    }

    /**
     * Every selectable category key (including {@link NotificationCategories#UNCATEGORIZED}), sorted so
     * dialog row order is stable between openings.
     */
    @NotNull
    static List<String> sortedCategoryKeys(@NotNull NotificationCategories categories) {
        return List.copyOf(new TreeSet<>(categories.categoryKeys()));
    }

    @NotNull
    static Component mediumLabel(@NotNull NotificationSinkRegistry sinkRegistry, @NotNull String medium) {
        return sinkRegistry.getSink(medium)
                .map(NotificationSink::displayName)
                .orElseGet(() -> Component.text(medium));
    }

    @NotNull
    static Component categoryLabel(@NotNull NotificationCategories categories, @NotNull String category) {
        return Component.text(categories.label(category));
    }

    /**
     * A dialog input key for the row at the given position under the given prefix. Positional rather
     * than derived from the medium/category key: those keys are arbitrary strings, so any sanitising
     * transform risks two distinct keys colliding onto one input key. The dialog builder keeps a
     * key-to-value map alongside these.
     */
    @NotNull
    static String inputKey(@NotNull String prefix, int index) {
        return prefix + "_" + index;
    }

    /**
     * Resolves the player's current {@link PreferenceEditSession}, loading it from the database off the
     * main thread if none is currently staged, then invokes {@code onLoaded} back on the main thread.
     * Safe to call from a command executor or a dialog button callback.
     */
    static void withSession(@NotNull Plugin plugin,
                            @NotNull PreferenceSessionManager sessions,
                            @NotNull NotificationCategories categories,
                            @NotNull DatabaseNotificationPreferences preferences,
                            @NotNull Player player,
                            @NotNull Consumer<PreferenceEditSession> onLoaded) {
        var uuid = player.getUniqueId();
        var existing = sessions.get(uuid);
        if (existing.isPresent()) {
            onLoaded.accept(existing.get());
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> categoryKeys = categories.categoryKeys();
            Map<String, Set<String>> effective = preferences.effectiveMediaByCategory(uuid, categoryKeys);
            Set<String> explicitAtLoad = preferences.explicitlyConfiguredCategories(uuid, categoryKeys);
            Set<String> fallback = preferences.preferredMedia(uuid, DatabaseNotificationPreferences.ALL_CATEGORIES_KEY);
            PreferenceEditSession session = sessions.getOrCreate(uuid, () ->
                    new PreferenceEditSession(uuid, effective, explicitAtLoad, fallback, Instant.now()));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    onLoaded.accept(session);
                }
            });
        });
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogsTest"`
Expected: PASS (3 tests). `withSession` itself is exercised indirectly and manually in Task 12 — it needs a live `Player`/`Bukkit` scheduler and is not unit-testable here.

- [ ] **Step 5: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogs.java \
        platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogsTest.java
git commit -m "feat: add shared preference dialog helpers"
```

---

### Task 10: The five dialog screens and their router

**Files:**
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceRootDialog.java`
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/MediumPickerDialog.java`
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/MediumEditorDialog.java`
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryPickerDialog.java`
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryEditorDialog.java`
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogRouter.java`

**Interfaces:**
- Consumes: `PreferenceDialogs` from Task 9; `PreferenceEditSession`, `PreferenceSessionManager` from Tasks 7–8; `DatabaseNotificationPreferences.applyChanges/muteAll/resetAll` from Task 4; `NotificationCategories` from Task 2; `NotificationSinkRegistry` (existing).
- Produces: `PreferenceDialogRouter(Plugin, NotificationSinkRegistry, NotificationCategories, DatabaseNotificationPreferences)`; `sessions() -> PreferenceSessionManager`; `openRoot(Player)`, `openMediaPicker(Player)`, `openCategoryPicker(Player)`, `muteImmediately(Player)`, `resetImmediately(Player)`. Consumed by Task 11 (`NotificationsCommand`, `PreferenceQuitListener`, `PlayerNotificationsPlugin`).

This task's classes are Bukkit dialog UI with no automated test — the codebase's existing preferences dialog is likewise unverified by automated tests, checked by hand with `runServer`. Manual verification for all five screens happens in Task 12; this task only needs to compile.

The five dialog classes hold a reference to the single `PreferenceDialogRouter` for navigation, rather than referencing each other directly, avoiding a circular-constructor problem between screens that navigate to each other (root ↔ pickers ↔ editors).

- [ ] **Step 1: Implement `PreferenceRootDialog`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The root notification-preferences screen: choose a pivot ("by delivery method" or "by notification
 * type"), stage a global mute/reset, or apply/discard whatever is currently staged.
 */
final class PreferenceRootDialog {

    private static final Component TITLE = Component.text("Notification Preferences");
    private static final Component INTRO = Component.text(
            "Manage notifications by delivery method or by notification type. Changes are staged until"
                    + " you press Apply.");
    private static final Component BY_MEDIUM_LABEL = Component.text("By delivery method");
    private static final Component BY_CATEGORY_LABEL = Component.text("By notification type");
    private static final Component MUTE_ALL_LABEL = Component.text("Mute everything");
    private static final Component RESET_ALL_LABEL = Component.text("Reset all to server default");
    private static final Component DISCARD_LABEL = Component.text("Discard changes");
    private static final Component CLOSE_LABEL = Component.text("Close");

    private final PreferenceDialogRouter router;

    PreferenceRootDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSessionHandle handle) {
        var session = handle.session();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(BY_MEDIUM_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showMediaPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(BY_CATEGORY_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showCategoryPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(MUTE_ALL_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String category : this.router.categories().categoryKeys()) {
                        session.setCategoryMedia(category, Set.of(), now);
                    }
                    show(player, handle);
                }, PreferenceDialogs.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(RESET_ALL_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String category : this.router.categories().categoryKeys()) {
                        session.resetCategory(category, now);
                    }
                    show(player, handle);
                }, PreferenceDialogs.callbackOptions()))
                .build());
        if (session.isDirty()) {
            Component applyLabel = Component.text("Apply (" + session.dirtyCount() + " changed)");
            buttons.add(ActionButton.builder(applyLabel)
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.apply(player, session), PreferenceDialogs.callbackOptions()))
                    .build());
            buttons.add(ActionButton.builder(DISCARD_LABEL)
                    .action(DialogAction.customClick((response, audience) -> {
                        this.router.sessions().drop(player.getUniqueId());
                        PreferenceDialogs.message(this.router.plugin(), player,
                                Component.text("Changes discarded.", NamedTextColor.YELLOW));
                    }, PreferenceDialogs.callbackOptions()))
                    .build());
        }
        ActionButton close = ActionButton.builder(CLOSE_LABEL).build();

        DialogBase base = DialogBase.builder(TITLE)
                .body(List.of(DialogBody.plainMessage(INTRO)))
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(close).columns(1).build());
        });
        player.showDialog(dialog);
    }
}
```

- [ ] **Step 2: Implement `MediumPickerDialog`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * "By delivery method" picker: choose one registered medium to configure which notification categories
 * reach the player through it.
 */
final class MediumPickerDialog {

    private static final Component TITLE = Component.text("By Delivery Method");
    private static final Component INTRO = Component.text(
            "Choose a delivery method to configure which notifications reach you there.");
    private static final Component BACK_LABEL = Component.text("Back");

    private final PreferenceDialogRouter router;

    MediumPickerDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session) {
        List<String> media = PreferenceDialogs.selectableMedia(this.router.sinkRegistry());
        if (media.isEmpty()) {
            PreferenceDialogs.message(this.router.plugin(), player, Component.text(
                    "No notification media are available on this server.", NamedTextColor.RED));
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (String medium : media) {
            buttons.add(ActionButton.builder(PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), medium))
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.showMediaEditor(player, session, medium), PreferenceDialogs.callbackOptions()))
                    .build());
        }
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showRoot(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(TITLE)
                .body(List.of(DialogBody.plainMessage(INTRO)))
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(back).columns(1).build());
        });
        player.showDialog(dialog);
    }
}
```

- [ ] **Step 3: Implement `MediumEditorDialog`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editor for one medium: a checkbox per notification category, indicating whether it currently reaches
 * the player through this medium. Save writes into the session only; nothing is persisted until the
 * root screen's Apply.
 */
final class MediumEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SAVE_LABEL = Component.text("Save");

    private final PreferenceDialogRouter router;

    MediumEditorDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String mediumKey) {
        List<String> categoryKeys = PreferenceDialogs.sortedCategoryKeys(this.router.categories());
        Map<String, String> inputKeyToCategory = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(categoryKeys.size());
        for (int i = 0; i < categoryKeys.size(); i++) {
            String category = categoryKeys.get(i);
            String inputKey = PreferenceDialogs.inputKey("category", i);
            inputKeyToCategory.put(inputKey, category);
            boolean initial = session.mediaFor(category).contains(mediumKey);
            inputs.add(DialogInput.bool(inputKey, PreferenceDialogs.categoryLabel(this.router.categories(), category))
                    .initial(initial).build());
        }

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (Map.Entry<String, String> entry : inputKeyToCategory.entrySet()) {
                        boolean checked = Boolean.TRUE.equals(response.getBoolean(entry.getKey()));
                        session.toggleCategoryMedium(entry.getValue(), mediumKey, checked, now);
                    }
                    this.router.showMediaPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showMediaPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), mediumKey))
                .body(List.of(DialogBody.plainMessage(Component.text(
                        "Choose which notifications reach you through this delivery method."))))
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(List.of(save)).exitAction(back).columns(2).build());
        });
        player.showDialog(dialog);
    }
}
```

- [ ] **Step 4: Implement `CategoryPickerDialog`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * "By notification type" picker: choose one category to configure which media it reaches the player
 * through. A category label is suffixed "(server default)" when the player has not explicitly
 * configured it.
 */
final class CategoryPickerDialog {

    private static final Component TITLE = Component.text("By Notification Type");
    private static final Component INTRO = Component.text(
            "Choose a notification type to configure where it reaches you.");
    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SERVER_DEFAULT_SUFFIX = Component.text(" (server default)",
            NamedTextColor.GRAY);

    private final PreferenceDialogRouter router;

    CategoryPickerDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session) {
        List<String> categoryKeys = PreferenceDialogs.sortedCategoryKeys(this.router.categories());
        List<ActionButton> buttons = new ArrayList<>();
        for (String category : categoryKeys) {
            Component label = PreferenceDialogs.categoryLabel(this.router.categories(), category);
            if (session.isUsingServerDefault(category)) {
                label = label.append(SERVER_DEFAULT_SUFFIX);
            }
            buttons.add(ActionButton.builder(label)
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.showCategoryEditor(player, session, category), PreferenceDialogs.callbackOptions()))
                    .build());
        }
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showRoot(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(TITLE)
                .body(List.of(DialogBody.plainMessage(INTRO)))
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(back).columns(1).build());
        });
        player.showDialog(dialog);
    }
}
```

- [ ] **Step 5: Implement `CategoryEditorDialog`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Editor for one notification category: a checkbox per registered medium, plus "use server default" to
 * stage clearing this category's explicit configuration. Save writes into the session only; nothing is
 * persisted until the root screen's Apply.
 */
final class CategoryEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SAVE_LABEL = Component.text("Save");
    private static final Component USE_DEFAULT_LABEL = Component.text("Use server default");

    private final PreferenceDialogRouter router;

    CategoryEditorDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String categoryKey) {
        List<String> media = PreferenceDialogs.selectableMedia(this.router.sinkRegistry());
        Map<String, String> inputKeyToMedium = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(media.size());
        for (int i = 0; i < media.size(); i++) {
            String medium = media.get(i);
            String inputKey = PreferenceDialogs.inputKey("medium", i);
            inputKeyToMedium.put(inputKey, medium);
            boolean initial = session.mediaFor(categoryKey).contains(medium);
            inputs.add(DialogInput.bool(inputKey, PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), medium))
                    .initial(initial).build());
        }

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Set<String> selected = new TreeSet<>();
                    for (Map.Entry<String, String> entry : inputKeyToMedium.entrySet()) {
                        if (Boolean.TRUE.equals(response.getBoolean(entry.getKey()))) {
                            selected.add(entry.getValue());
                        }
                    }
                    session.setCategoryMedia(categoryKey, selected, Instant.now());
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton useDefault = ActionButton.builder(USE_DEFAULT_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    session.resetCategory(categoryKey, Instant.now());
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showCategoryPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(PreferenceDialogs.categoryLabel(this.router.categories(), categoryKey))
                .body(List.of(DialogBody.plainMessage(Component.text(
                        "Choose where this kind of notification reaches you."))))
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(List.of(save, useDefault)).exitAction(back)
                    .columns(2).build());
        });
        player.showDialog(dialog);
    }
}
```

- [ ] **Step 6: Implement `PreferenceDialogRouter`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the five preference dialog screens and the single {@link PreferenceSessionManager} they share,
 * and is the one object {@code NotificationsCommand} and {@code PreferenceQuitListener} need to hold.
 */
public final class PreferenceDialogRouter {

    private final Plugin plugin;
    private final NotificationSinkRegistry sinkRegistry;
    private final NotificationCategories categories;
    private final DatabaseNotificationPreferences preferences;
    private final PreferenceSessionManager sessions;

    private final PreferenceRootDialog rootDialog;
    private final MediumPickerDialog mediumPickerDialog;
    private final MediumEditorDialog mediumEditorDialog;
    private final CategoryPickerDialog categoryPickerDialog;
    private final CategoryEditorDialog categoryEditorDialog;

    public PreferenceDialogRouter(@NotNull Plugin plugin,
                                  @NotNull NotificationSinkRegistry sinkRegistry,
                                  @NotNull NotificationCategories categories,
                                  @NotNull DatabaseNotificationPreferences preferences) {
        this.plugin = plugin;
        this.sinkRegistry = sinkRegistry;
        this.categories = categories;
        this.preferences = preferences;
        this.sessions = new PreferenceSessionManager();
        this.rootDialog = new PreferenceRootDialog(this);
        this.mediumPickerDialog = new MediumPickerDialog(this);
        this.mediumEditorDialog = new MediumEditorDialog(this);
        this.categoryPickerDialog = new CategoryPickerDialog(this);
        this.categoryEditorDialog = new CategoryEditorDialog(this);
    }

    @NotNull
    Plugin plugin() {
        return this.plugin;
    }

    @NotNull
    NotificationSinkRegistry sinkRegistry() {
        return this.sinkRegistry;
    }

    @NotNull
    NotificationCategories categories() {
        return this.categories;
    }

    @NotNull
    public PreferenceSessionManager sessions() {
        return this.sessions;
    }

    public void openRoot(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.categories, this.preferences,
                player, session -> this.rootDialog.show(player, new PreferenceEditSessionHandle(session)));
    }

    public void openMediaPicker(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.categories, this.preferences,
                player, session -> this.mediumPickerDialog.show(player, session));
    }

    public void openCategoryPicker(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.categories, this.preferences,
                player, session -> this.categoryPickerDialog.show(player, session));
    }

    void showRoot(@NotNull Player player, @NotNull PreferenceEditSession session) {
        this.rootDialog.show(player, new PreferenceEditSessionHandle(session));
    }

    void showMediaPicker(@NotNull Player player, @NotNull PreferenceEditSession session) {
        this.mediumPickerDialog.show(player, session);
    }

    void showMediaEditor(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String medium) {
        this.mediumEditorDialog.show(player, session, medium);
    }

    void showCategoryPicker(@NotNull Player player, @NotNull PreferenceEditSession session) {
        this.categoryPickerDialog.show(player, session);
    }

    void showCategoryEditor(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String category) {
        this.categoryEditorDialog.show(player, session, category);
    }

    void apply(@NotNull Player player, @NotNull PreferenceEditSession session) {
        Map<String, Set<String>> explicit = session.explicitChanges();
        Set<String> resets = session.categoriesToReset();
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                this.preferences.applyChanges(uuid, explicit, resets);
            } catch (RuntimeException ex) {
                this.plugin.getLogger().warning(
                        "Failed to apply notification preferences for " + uuid + ": " + ex.getMessage());
                PreferenceDialogs.message(this.plugin, player, Component.text(
                        "Could not save your notification preferences; please try again.",
                        NamedTextColor.RED));
                return;
            }
            this.sessions.drop(uuid);
            PreferenceDialogs.message(this.plugin, player,
                    Component.text("Notification preferences saved.", NamedTextColor.GREEN));
        });
    }

    /**
     * Immediately mutes every category for the player and discards any staged, unapplied session — the
     * one deliberate asymmetry with the root screen's staged "Mute everything" button.
     */
    public void muteImmediately(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        boolean hadSession = this.sessions.get(uuid).isPresent();
        Set<String> categoryKeys = this.categories.categoryKeys();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                this.preferences.muteAll(uuid, categoryKeys);
            } catch (RuntimeException ex) {
                this.plugin.getLogger().warning("Failed to mute notifications for " + uuid + ": " + ex.getMessage());
                PreferenceDialogs.message(this.plugin, player, Component.text(
                        "Could not mute your notifications; please try again.", NamedTextColor.RED));
                return;
            }
            this.sessions.drop(uuid);
            Component message = Component.text("All notifications muted.", NamedTextColor.YELLOW);
            if (hadSession) {
                message = message.append(Component.text(" Any unsaved preference changes were discarded.",
                        NamedTextColor.GRAY));
            }
            PreferenceDialogs.message(this.plugin, player, message);
        });
    }

    /**
     * Immediately clears every stored preference for the player and discards any staged, unapplied
     * session — the one deliberate asymmetry with the root screen's staged "Reset all" button.
     */
    public void resetImmediately(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        boolean hadSession = this.sessions.get(uuid).isPresent();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                this.preferences.resetAll(uuid);
            } catch (RuntimeException ex) {
                this.plugin.getLogger().warning(
                        "Failed to reset notification preferences for " + uuid + ": " + ex.getMessage());
                PreferenceDialogs.message(this.plugin, player, Component.text(
                        "Could not reset your notification preferences; please try again.", NamedTextColor.RED));
                return;
            }
            this.sessions.drop(uuid);
            Component message = Component.text("Notification preferences reset to the server default.",
                    NamedTextColor.GREEN);
            if (hadSession) {
                message = message.append(Component.text(" Any unsaved preference changes were discarded.",
                        NamedTextColor.GRAY));
            }
            PreferenceDialogs.message(this.plugin, player, message);
        });
    }
}
```

`PreferenceRootDialog.show` takes a small wrapper, `PreferenceEditSessionHandle`, purely so the "Mute everything"/"Reset all" buttons can call `show(player, handle)` again after mutating the session in place, without re-deriving it. Add it as a tiny package-private record in the same file as `PreferenceDialogRouter` (or its own file):

`platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceEditSessionHandle.java`:

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import org.jetbrains.annotations.NotNull;

/**
 * Trivial wrapper letting {@link PreferenceRootDialog#show} re-invoke itself after a button mutates the
 * session in place (Mute everything / Reset all), without needing a second parameter list.
 */
record PreferenceEditSessionHandle(@NotNull PreferenceEditSession session) {
}
```

- [ ] **Step 7: Verify the module compiles**

Run: `./gradlew :platform:paper-plugin:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceRootDialog.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/MediumPickerDialog.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/MediumEditorDialog.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryPickerDialog.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryEditorDialog.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogRouter.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceEditSessionHandle.java
git commit -m "feat: add the five preference dialog screens and their router"
```

---

### Task 11: Wire commands, quit listener, and remove the old dialog

**Files:**
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/NotificationsCommand.java`
- Create: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceQuitListener.java`
- Delete: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/NotificationPreferencesDialog.java`
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`

**Interfaces:**
- Consumes: `PreferenceDialogRouter` from Task 10.
- Produces: `NotificationsCommand.create(PreferenceDialogRouter) -> LiteralCommandNode<CommandSourceStack>` with `media`, `types`, `mute`, `reset` subcommands added to the existing root/`preferences` literals; `PreferenceQuitListener(PreferenceSessionManager)`.

No automated test — command registration and the quit listener need a live server. Manually verified in Task 12.

- [ ] **Step 1: Rewrite `NotificationsCommand`**

```java
package io.github.md5sha256.playernotifications.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogRouter;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The player-facing {@code /notifications} command. The bare root and {@code preferences} open the
 * staged root dialog; {@code media}/{@code types} jump straight to the corresponding picker on the same
 * session; {@code mute}/{@code reset} write immediately and discard any open session, unlike their
 * staged root-screen equivalents.
 *
 * <p>Registered through Paper's Brigadier API rather than a {@code commands:} block, because this plugin
 * ships a {@code paper-plugin.yml}, which has no such block.
 */
public final class NotificationsCommand {

    public static final String PERMISSION = "playernotifications.command.preferences";

    public static final String DESCRIPTION = "Choose how you receive notifications";

    private static final Component PLAYERS_ONLY =
            Component.text("Only players have notification preferences.", NamedTextColor.RED);

    private NotificationsCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull PreferenceDialogRouter router) {
        return Commands.literal("notifications")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> run(context, router::openRoot))
                .then(Commands.literal("preferences").executes(context -> run(context, router::openRoot)))
                .then(Commands.literal("media").executes(context -> run(context, router::openMediaPicker)))
                .then(Commands.literal("types").executes(context -> run(context, router::openCategoryPicker)))
                .then(Commands.literal("mute").executes(context -> run(context, router::muteImmediately)))
                .then(Commands.literal("reset").executes(context -> run(context, router::resetImmediately)))
                .build();
    }

    private static int run(@NotNull CommandContext<CommandSourceStack> context, @NotNull Consumer<Player> action) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return 0;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
```

- [ ] **Step 2: Add `PreferenceQuitListener`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Drops a player's staged {@code PreferenceEditSession} on quit, so a player who leaves mid-edit does
 * not resume stale staged changes on rejoin, and so the session map does not grow unboundedly.
 */
public final class PreferenceQuitListener implements Listener {

    private final PreferenceSessionManager sessions;

    public PreferenceQuitListener(@NotNull PreferenceSessionManager sessions) {
        this.sessions = sessions;
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        this.sessions.drop(event.getPlayer().getUniqueId());
    }
}
```

- [ ] **Step 3: Delete the old dialog**

Delete `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/NotificationPreferencesDialog.java`.

- [ ] **Step 4: Wire the router, quit listener, and new command into `PlayerNotificationsPlugin`**

Add the import `io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogRouter;` and `io.github.md5sha256.playernotifications.paper.preferences.PreferenceQuitListener;`. Remove the now-unused import of `NotificationPreferencesDialog`.

Replace the field:

```java
    private PreferenceDialogRouter preferenceDialogRouter;
```

(Remove the `preferences` field's use in `registerCommands()`'s dialog construction — the field itself stays, since `preferences()` is still a public accessor and the router now owns preference reads/writes internally.)

Replace `registerCommands()`:

```java
    @SuppressWarnings("UnstableApiUsage")
    private void registerCommands() {
        this.preferenceDialogRouter = new PreferenceDialogRouter(
                this, this.sinkRegistry, this.categories, this.preferences);
        getServer().getPluginManager().registerEvents(
                new PreferenceQuitListener(this.preferenceDialogRouter.sessions()), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        NotificationsCommand.create(this.preferenceDialogRouter),
                        NotificationsCommand.DESCRIPTION,
                        List.of("notifs")
                ));
    }
```

- [ ] **Step 5: Build and run the full test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — no remaining references to `NotificationPreferencesDialog` anywhere in the tree.

Run: `./gradlew :core:test :api:test`
Expected: BUILD SUCCESSFUL. Count `*.xml` result files, not `TEST-*.xml`. Expect **20 tests in `:api:test`** (12 original + 1 category test) and **28 tests in `:core:test`** (12 preference tests + 4 precedence tests + the pre-existing 34 that are untouched by this plan — reconcile the exact count against your checkout's current baseline before relying on this number; treat it as a sanity check, not a hard assertion).

- [ ] **Step 6: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/NotificationsCommand.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceQuitListener.java \
        platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java
git rm platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/NotificationPreferencesDialog.java
git commit -m "feat: wire preference dialog router into commands and plugin lifecycle"
```

---

### Task 12: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full automated test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. `:core:test` requires a running Docker daemon.

- [ ] **Step 2: Manually verify the dialogs with a live server**

Run: `./gradlew :platform:paper-plugin:runServer` (needs a reachable MariaDB per `database.yml`). Join as a player with `playernotifications.command.preferences` (default `true`) and check:

- `/notifications` opens the root screen with "By delivery method", "By notification type", "Mute everything", "Reset all to server default", and no Apply/Discard buttons while nothing is staged.
- `/notifications media` jumps straight to the medium picker; opening a medium (e.g. Chat) shows one checkbox per category, all showing the server default (unconfigured) state initially.
- Toggling a category off in the Chat editor and hitting Save returns to the medium picker; reopening `/notifications types` and opening that category shows Chat unchecked, other media unchanged — the two pivots agree.
- Back on the root screen, "Apply (1 changed)" now appears; clicking it persists the change and returns to a clean root screen with no Apply/Discard.
- Repeat with "By notification type": open a category, toggle a medium, Save, verify the medium picker's corresponding checkbox reflects it before Apply.
- "Mute everything" then "Discard changes": verify preferences are unchanged (nothing was written).
- "Mute everything" then "Apply": verify every category now shows only "None" is effectively selected (no medium delivers) — reopen a category editor and see every checkbox unchecked.
- `/notifications mute`: verify it writes immediately (no Apply needed) and, if a dialog was left open with staged changes, those changes are gone on reopen.
- `/notifications reset`: verify it clears preferences immediately, and `/notifications types` shows every category back at "(server default)".
- Quit and rejoin mid-edit (stage a change, do not Apply, disconnect, reconnect): verify `/notifications` shows a clean session, not the stale staged change.
- Enqueue a notification with a data type declared in `categories.yml` (or rely on the `essentials-mail` example if the Essentials adapter module is loaded) and confirm it is still delivered/muted according to the category-specific preference, not the player's other categories.

- [ ] **Step 3: Confirm no stray references remain**

Run: `git grep -n "NotificationPreferencesDialog"`
Expected: no matches (the class and all references were removed in Task 11).

- [ ] **Step 4: Final commit (only if manual verification uncovered fixes)**

If Step 2 surfaces a bug, fix it, re-verify, and commit with a message describing the specific defect fixed — do not bundle unrelated cleanup into this commit.
