# Category Registry API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move preference storage/resolution from per-`category` to per-`dataType`, and make categories registrable in code via a new `api.category.NotificationCategoryRegistry`, merged at read time with `categories.yml`.

**Architecture:** `PlayerNotificationPreference` is re-keyed from `category` to `dataType` end-to-end (schema, entity, mapper, `DatabaseNotificationPreferences`, `RenderingProcessor`, `NotificationDelivery`). `NotificationDelivery`/`RenderingProcessor` stop resolving or needing a category at all. A new `api.category.NotificationCategoryRegistry` (backed by `DefaultNotificationCategoryRegistry`) lets module authors claim data types under categories in code; `core.category.NotificationCategories` becomes a read-side merge of that registry and `categories.yml`, with `resolve(dataType)` returning a `Set<String>` (many-to-many) instead of one `String`. Categories remain a pure display/grouping concept for the preference dialogs, which fan a category-level bulk edit out into per-`dataType` writes.

**Tech Stack:** Java 21, MyBatis (MariaDB), Configurate, JUnit 5 (Testcontainers for `:core:test`), Paper dialogs (`platform:paper-plugin`).

## Global Constraints

- `PlayerNotificationPreference` schema is edited in place in `V1__maria_initial_schema.sql` — no new migration file (project is still in prototyping, no data to preserve).
- `NotificationDelivery`'s 6-arg (category-resolving) constructor is deleted; the 5-arg rendering constructor becomes the "full" form.
- `RenderingProcessor`'s category-agnostic (4-arg) constructor is deleted; the dataType-taking constructor becomes the only one, and its `dataType` parameter is required (`@NotNull`), not nullable.
- `NotificationPreferences#preferredMedia(UUID, String)`'s signature shape is unchanged — only its second parameter's meaning changes from category to dataType. Its default delegating to the 1-arg overload is unchanged.
- No collision warning for a `dataType` claimed by two categories (membership is now a set). A collision on the category *key* itself (code vs. config both defining `economy`) still logs at `fine`, and config's label/description wins.
- The five preference dialog classes remain unverified by automated tests, per existing project convention — verify those tasks by hand with `:platform:paper-plugin:runServer` per the plan's final task, but they still need to compile and the described logic must be implemented for real, not stubbed.
- `:core:test` requires a running Docker daemon (Testcontainers `mariadb:11.7`). If Docker isn't available when a task's steps say to run `:core:test`, note it and move on — do not skip writing the test.
- Every new/renamed public method needs `@NotNull`/`@Nullable` annotations on reference-typed parameters and return types, matching the codebase's existing convention (`org.jetbrains.annotations`).

---

### Task 1: `NotificationDataTypeRegistry#dataTypes()` and the `api.category` registry

**Files:**
- Modify: `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistry.java`
- Create: `api/src/main/java/io/github/md5sha256/playernotifications/api/category/NotificationCategoryRegistry.java`
- Create: `api/src/main/java/io/github/md5sha256/playernotifications/api/category/DefaultNotificationCategoryRegistry.java`
- Test: `api/src/test/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistryTest.java` (create if it doesn't already cover `dataTypes()`; check first — if the file exists, add a test method to it instead of creating a duplicate)
- Test: `api/src/test/java/io/github/md5sha256/playernotifications/api/category/DefaultNotificationCategoryRegistryTest.java`

**Interfaces:**
- Produces: `NotificationDataTypeRegistry#dataTypes(): @NotNull Set<String>` — every `dataType` with a registered payload mapping. Later tasks (`PreferenceDialogs`, `PreferenceDialogRouter`, the dialogs, `PlayerNotificationsPlugin`) use this as "the full set of known data types".
- Produces: `NotificationCategoryRegistry` interface with `registerCategory`, `claimDataType`, `unclaimDataType`, `categoryKeys()`, `dataTypesFor(String)`, `label(String)`, `description(String)`.
- Produces: `DefaultNotificationCategoryRegistry` — the in-memory implementation, instantiated once per `NotificationService` (wired in Task 2).

- [ ] **Step 1: Check for an existing `NotificationDataTypeRegistry` test file, then write the failing test for `dataTypes()`**

First check whether `api/src/test/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistryTest.java` exists. If it does, add this method to it (matching its existing style); otherwise create it with this content:

```java
package io.github.md5sha256.playernotifications.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class NotificationDataTypeRegistryTest {

    @Test
    void dataTypesReturnsEveryRegisteredMapping() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping("mail", String.class);
        registry.registerPayloadMapping("warning", String.class);

        Assertions.assertEquals(Set.of("mail", "warning"), registry.dataTypes());
    }

    @Test
    void dataTypesIsEmptyForAFreshRegistry() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();

        Assertions.assertEquals(Set.of(), registry.dataTypes());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistryTest"`
Expected: FAIL — `dataTypes()` does not exist on `NotificationDataTypeRegistry`.

- [ ] **Step 3: Add `dataTypes()` to `NotificationDataTypeRegistry`**

Add this method to `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistry.java`, next to `resolvePayloadClass`:

```java
    /**
     * Every {@code dataType} with a registered payload mapping.
     */
    @NotNull
    public Set<String> dataTypes() {
        return Set.copyOf(this.payloadMapping.keySet());
    }
```

Add `import java.util.Set;` if not already present (it is — used elsewhere in the file's signatures).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistryTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing tests for `DefaultNotificationCategoryRegistry`**

```java
package io.github.md5sha256.playernotifications.api.category;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class DefaultNotificationCategoryRegistryTest {

    @Test
    void registerCategoryStoresLabelAndDescription() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.registerCategory("economy", "Economy", "Shop and payments");

        Assertions.assertEquals(Set.of("economy"), registry.categoryKeys());
        Assertions.assertEquals("Economy", registry.label("economy"));
        Assertions.assertEquals("Shop and payments", registry.description("economy"));
        Assertions.assertEquals(Set.of(), registry.dataTypesFor("economy"));
    }

    @Test
    void claimDataTypeRegistersAnUnknownCategoryWithEmptyLabelAndDescription() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.claimDataType("economy", "mail");

        Assertions.assertEquals(Set.of("economy"), registry.categoryKeys());
        Assertions.assertEquals("", registry.label("economy"));
        Assertions.assertEquals("", registry.description("economy"));
        Assertions.assertEquals(Set.of("mail"), registry.dataTypesFor("economy"));
    }

    @Test
    void claimDataTypeDoesNotOverwriteAnAlreadyRegisteredLabel() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.registerCategory("economy", "Economy", "Shop and payments");
        registry.claimDataType("economy", "mail");

        Assertions.assertEquals("Economy", registry.label("economy"));
    }

    @Test
    void aDataTypeCanBeClaimedByMultipleCategories() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.claimDataType("economy", "mail");
        registry.claimDataType("moderation", "mail");

        Assertions.assertEquals(Set.of("mail"), registry.dataTypesFor("economy"));
        Assertions.assertEquals(Set.of("mail"), registry.dataTypesFor("moderation"));
        Assertions.assertEquals(Set.of("economy", "moderation"), registry.categoryKeys());
    }

    @Test
    void unclaimDataTypeRemovesOnlyThatClaim() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.claimDataType("economy", "mail");
        registry.claimDataType("economy", "receipt");

        registry.unclaimDataType("economy", "mail");

        Assertions.assertEquals(Set.of("receipt"), registry.dataTypesFor("economy"));
    }

    @Test
    void unclaimDataTypeOnAnUnknownCategoryIsANoOp() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        Assertions.assertDoesNotThrow(() -> registry.unclaimDataType("nonexistent", "mail"));
    }

    @Test
    void dataTypesForAnUnknownCategoryIsEmpty() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        Assertions.assertEquals(Set.of(), registry.dataTypesFor("nonexistent"));
    }

    @Test
    void labelAndDescriptionForAnUnknownCategoryAreEmpty() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        Assertions.assertEquals("", registry.label("nonexistent"));
        Assertions.assertEquals("", registry.description("nonexistent"));
    }
}
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistryTest"`
Expected: FAIL to compile — `io.github.md5sha256.playernotifications.api.category` package does not exist yet.

- [ ] **Step 7: Write `NotificationCategoryRegistry`**

```java
package io.github.md5sha256.playernotifications.api.category;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Lets module authors declare notification categories and claim {@code dataType}s under them in code,
 * the same way payload types, processors, renderers, and sinks are registered. Categories are purely a
 * display/grouping concept for the preference dialogs — never a delivery-time lookup key — so a
 * {@code dataType} may be claimed by any number of categories with no collision to resolve.
 *
 * <p>{@code categories.yml} is merged with this registry at read time by
 * {@code core.category.NotificationCategories}; a module's code registrations and an operator's config
 * entries are both just claims layered on top of each other.
 */
public interface NotificationCategoryRegistry {

    /**
     * Registers (or re-registers) a category's display label and description. Calling this again for an
     * already-registered key overwrites its label/description, not its claimed data types.
     */
    void registerCategory(@NotNull String categoryKey, @NotNull String label, @NotNull String description);

    /**
     * Claims a {@code dataType} for a category. If {@code categoryKey} has not been registered via
     * {@link #registerCategory}, it is registered with an empty label/description rather than throwing —
     * mirroring how {@code NotificationDataTypeRegistry#registerPayloadMapping} has no precondition on
     * prior state.
     */
    void claimDataType(@NotNull String categoryKey, @NotNull String dataType);

    /**
     * Removes one category's claim on one {@code dataType}. A no-op if the category never claimed it.
     */
    void unclaimDataType(@NotNull String categoryKey, @NotNull String dataType);

    /**
     * Every category key registered in code, whether via {@link #registerCategory} or as a side effect
     * of {@link #claimDataType}.
     */
    @NotNull Set<String> categoryKeys();

    /**
     * Every {@code dataType} claimed by the given category. Empty for an unregistered category.
     */
    @NotNull Set<String> dataTypesFor(@NotNull String categoryKey);

    /**
     * The category's display label, or {@code ""} if it was never registered or only implicitly
     * registered via {@link #claimDataType}.
     */
    @NotNull String label(@NotNull String categoryKey);

    /**
     * The category's display description, or {@code ""} if it was never registered or only implicitly
     * registered via {@link #claimDataType}.
     */
    @NotNull String description(@NotNull String categoryKey);
}
```

- [ ] **Step 8: Write `DefaultNotificationCategoryRegistry`**

```java
package io.github.md5sha256.playernotifications.api.category;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link NotificationCategoryRegistry}. Not thread-confined — like
 * {@code NotificationDataTypeRegistry}, its backing maps are synchronized so registration calls from
 * different module class loaders at startup cannot race.
 */
public final class DefaultNotificationCategoryRegistry implements NotificationCategoryRegistry {

    private final Map<String, String> labels = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> descriptions = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Set<String>> dataTypesByCategory = Collections.synchronizedMap(new HashMap<>());

    @Override
    public void registerCategory(@NotNull String categoryKey, @NotNull String label, @NotNull String description) {
        this.labels.put(categoryKey, label);
        this.descriptions.put(categoryKey, description);
        this.dataTypesByCategory.computeIfAbsent(categoryKey, key -> Collections.synchronizedSet(new HashSet<>()));
    }

    @Override
    public void claimDataType(@NotNull String categoryKey, @NotNull String dataType) {
        this.labels.putIfAbsent(categoryKey, "");
        this.descriptions.putIfAbsent(categoryKey, "");
        this.dataTypesByCategory
                .computeIfAbsent(categoryKey, key -> Collections.synchronizedSet(new HashSet<>()))
                .add(dataType);
    }

    @Override
    public void unclaimDataType(@NotNull String categoryKey, @NotNull String dataType) {
        Set<String> claimed = this.dataTypesByCategory.get(categoryKey);
        if (claimed != null) {
            claimed.remove(dataType);
        }
    }

    @Override
    public @NotNull Set<String> categoryKeys() {
        return Set.copyOf(this.dataTypesByCategory.keySet());
    }

    @Override
    public @NotNull Set<String> dataTypesFor(@NotNull String categoryKey) {
        Set<String> claimed = this.dataTypesByCategory.get(categoryKey);
        return claimed != null ? Set.copyOf(claimed) : Set.of();
    }

    @Override
    public @NotNull String label(@NotNull String categoryKey) {
        return this.labels.getOrDefault(categoryKey, "");
    }

    @Override
    public @NotNull String description(@NotNull String categoryKey) {
        return this.descriptions.getOrDefault(categoryKey, "");
    }
}
```

- [ ] **Step 9: Run all the new tests to verify they pass**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistryTest" --tests "io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistryTest"`
Expected: PASS, all tests green.

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistry.java api/src/main/java/io/github/md5sha256/playernotifications/api/category api/src/test/java/io/github/md5sha256/playernotifications/api/category api/src/test/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistryTest.java
git commit -m "feat: add NotificationCategoryRegistry and NotificationDataTypeRegistry#dataTypes()"
```

---

### Task 2: Wire the category registry into `NotificationService`

**Files:**
- Modify: `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationService.java`
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/DefaultNotificationService.java`
- Test: `core/src/test/java/io/github/md5sha256/playernotifications/core/DefaultNotificationServiceTest.java` (add to existing file)

**Interfaces:**
- Consumes: `NotificationCategoryRegistry`, `DefaultNotificationCategoryRegistry` from Task 1.
- Produces: `NotificationService#categoryRegistry(): @NotNull NotificationCategoryRegistry`. Later tasks (`PlayerNotificationsPlugin`) call `notificationService.categoryRegistry()` the same way they already call `notificationService.dataTypeRegistry()`.

- [ ] **Step 1: Write the failing test**

Open `core/src/test/java/io/github/md5sha256/playernotifications/core/DefaultNotificationServiceTest.java`, find its existing `@BeforeEach`/setup pattern for constructing `DefaultNotificationService` (it already constructs one against a Testcontainers-backed `Database`), and add:

```java
    @Test
    void categoryRegistryIsNeverNullAndStartsEmpty() {
        Assertions.assertNotNull(this.service.categoryRegistry());
        Assertions.assertEquals(java.util.Set.of(), this.service.categoryRegistry().categoryKeys());
    }
```

Adjust the field/method name referencing the constructed service instance to match whatever the existing test class calls it (read the file first to match the exact field name before writing this step for real).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.DefaultNotificationServiceTest"`
Expected: FAIL to compile — `categoryRegistry()` does not exist.

- [ ] **Step 3: Add `categoryRegistry()` to the `NotificationService` interface**

In `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationService.java`, add the import `io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;` and this method to the interface, alongside `dataTypeRegistry()`:

```java
    @NotNull NotificationCategoryRegistry categoryRegistry();
```

- [ ] **Step 4: Implement it in `DefaultNotificationService`**

In `core/src/main/java/io/github/md5sha256/playernotifications/core/DefaultNotificationService.java`, add the import `io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;` and `io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;`, then:

```java
    private final NotificationCategoryRegistry categoryRegistry;
```

as a new field, initialized in both constructors:

```java
    public DefaultNotificationService(@NotNull Database database) {
        this(database, new NotificationDataTypeRegistry(), new DefaultNotificationCategoryRegistry());
    }

    public DefaultNotificationService(@NotNull Database database,
                                      @NotNull NotificationDataTypeRegistry dataTypeRegistry) {
        this(database, dataTypeRegistry, new DefaultNotificationCategoryRegistry());
    }

    public DefaultNotificationService(@NotNull Database database,
                                      @NotNull NotificationDataTypeRegistry dataTypeRegistry,
                                      @NotNull NotificationCategoryRegistry categoryRegistry) {
        this.database = database;
        this.dataTypeRegistry = dataTypeRegistry;
        this.categoryRegistry = categoryRegistry;
        this.objectMapper = new ObjectMapper();
        this.dataTypeRegistry.registerSerializer(String.class, jsonSerializer(String.class));
    }
```

(Keep the existing comments on the object mapper/serializer lines — only the constructor structure changes.) Then add the accessor next to `dataTypeRegistry()`:

```java
    @Override
    public @NotNull NotificationCategoryRegistry categoryRegistry() {
        return this.categoryRegistry;
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.DefaultNotificationServiceTest"`
Expected: PASS (requires Docker for the full class; the new test method itself only needs the already-running fixture).

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationService.java core/src/main/java/io/github/md5sha256/playernotifications/core/DefaultNotificationService.java core/src/test/java/io/github/md5sha256/playernotifications/core/DefaultNotificationServiceTest.java
git commit -m "feat: expose NotificationCategoryRegistry from NotificationService"
```

---

### Task 3: `RenderingProcessor` drops its category constructor and calls `preferredMedia(target, dataType)` directly

**Files:**
- Modify: `api/src/main/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessor.java`
- Modify: `api/src/main/java/io/github/md5sha256/playernotifications/api/render/NotificationPreferences.java` (javadoc only)
- Modify: `api/src/test/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessorTest.java`

**Interfaces:**
- Produces: `RenderingProcessor<T>(NotificationRenderer<T> renderer, NotificationSinkRegistry sinks, NotificationPreferences preferences, @NotNull String dataType, Logger logger)` — the **only** constructor, `dataType` required. Task 6 (`NotificationDelivery`) is the only production caller and passes `notification.notifPayloadType()`.

- [ ] **Step 1: Update every existing test call site to the new required-dataType constructor**

`RenderingProcessorTest.java` currently has 8 call sites using the old 4-arg (category-agnostic) constructor `new RenderingProcessor<>(RENDERER, sinks, <preferences>, <logger>)`, in these methods: `deliversToEverySink`, `deleteWinsOnMixedResults`, `retainWhenNoneDeliver`, `warnsWhenAllUnsupported`, `noWarningWhenAllUnreachable`, `unknownMediumSkipped`, `throwingSinkIsContained`, `noPreferredMediaRetains`. In each, insert a dataType string literal `"test-type"` as the 4th argument, before the logger, e.g.:

```java
        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, fixedPreferences("chat", "discord"), "test-type", Logger.getLogger("test"));
```

(and likewise for the other 7 call sites — `fixedPreferences(...)` only implements the 1-arg `preferredMedia(UUID)` overload, so the dataType value passed is irrelevant to those tests' behavior; they exercise sink fan-out, not preference lookup.)

Then replace the `passesCategoryToPreferenceLookup` test (which already uses the 5-arg constructor) with a rewritten version asserting the dataType reaches `preferredMedia` directly, with no category framing:

```java
    @Test
    @DisplayName("passes the given dataType through to the two-argument preference lookup")
    void passesDataTypeToPreferenceLookup() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);

        List<String> dataTypesSeen = new ArrayList<>();
        NotificationPreferences preferences = new NotificationPreferences() {
            @Override
            public Set<String> preferredMedia(UUID player) {
                dataTypesSeen.add(null);
                return Set.of();
            }

            @Override
            public Set<String> preferredMedia(UUID player, String dataType) {
                dataTypesSeen.add(dataType);
                return Set.of("chat");
            }
        };

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, preferences, "economy-payout", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.DELETE,
                processor.receiveNotification("payload", TARGET));
        Assertions.assertEquals(List.of("economy-payout"), dataTypesSeen);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.render.RenderingProcessorTest"`
Expected: FAIL to compile — the 5-arg constructor doesn't accept a `String` in that position yet as the sole/required constructor (compile succeeds against the *old* code actually, since the 5-arg ctor already exists — but the old 4-arg call sites now fail because Step 3 will delete the 4-arg constructor first; if compilation currently succeeds because both constructors still exist, skip ahead — the meaningful failure comes after Step 3 removes the 4-arg constructor and old call sites elsewhere, e.g. `NotificationDelivery`, stop compiling). Note: this test class alone may already compile against unmodified `RenderingProcessor`; the real failure signal for this task is in Step 4's diff removing the 4-arg constructor — proceed to implement, then verify everything compiles and passes together in Step 5.

- [ ] **Step 3: Rewrite `RenderingProcessor` to a single required-dataType constructor**

Replace the class's constructors and `category` field in `api/src/main/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessor.java`:

```java
    private final NotificationRenderer<T> renderer;
    private final NotificationSinkRegistry sinks;
    private final NotificationPreferences preferences;
    private final String dataType;
    private final Logger logger;

    /**
     * Constructs a processor that resolves preferred media for the given payload's {@code dataType} via
     * {@link NotificationPreferences#preferredMedia(UUID, String)}.
     */
    public RenderingProcessor(@NotNull NotificationRenderer<T> renderer,
                              @NotNull NotificationSinkRegistry sinks,
                              @NotNull NotificationPreferences preferences,
                              @NotNull String dataType,
                              @NotNull Logger logger) {
        this.renderer = renderer;
        this.sinks = sinks;
        this.preferences = preferences;
        this.dataType = dataType;
        this.logger = logger;
    }

    @Override
    public @NotNull NotificationDisposition receiveNotification(@NotNull T payload, @NotNull UUID target) {
        Set<String> media = this.preferences.preferredMedia(target, this.dataType);
```

Remove the now-unused `import org.jetbrains.annotations.Nullable;` if nothing else in the file uses it (check — it isn't used elsewhere in this file).

- [ ] **Step 4: Update `NotificationPreferences` javadoc**

In `api/src/main/java/io/github/md5sha256/playernotifications/api/render/NotificationPreferences.java`, change the `@param`/prose on the two-arg `preferredMedia` overload from "category" to "dataType" wording (signature itself is unchanged):

```java
    @NotNull
    default Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType) {
        return preferredMedia(player);
    }
```

(rename the parameter and any javadoc text referencing "category" to "dataType"; if there's no javadoc on this method currently, none needs to be added beyond the parameter rename).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :api:test --tests "io.github.md5sha256.playernotifications.api.render.RenderingProcessorTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessor.java api/src/main/java/io/github/md5sha256/playernotifications/api/render/NotificationPreferences.java api/src/test/java/io/github/md5sha256/playernotifications/api/render/RenderingProcessorTest.java
git commit -m "refactor: RenderingProcessor requires a dataType, drops category constructor"
```

---

### Task 4: Rename the `PlayerNotificationPreference` schema, entity, and mappers from `category` to `dataType`

**Files:**
- Modify: `core/src/main/resources/sql/migrations/V1__maria_initial_schema.sql`
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/database/entity/PlayerNotificationPreferenceEntity.java`
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/PlayerNotificationPreferenceMapper.java`
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaPlayerNotificationPreferenceMapper.java`
- Test: `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java` (its `MapperTests` nested class — rename call sites, this task does not yet touch `DatabaseNotificationPreferencesTests`, that's Task 5)

**Interfaces:**
- Produces: `PlayerNotificationPreferenceEntity(String dataType, String medium)`, `PlayerNotificationPreferenceMapper#selectByPlayerAndDataType`/`#deleteByPlayerAndDataType`/`#insertPreferences(UUID, String dataType, Collection<String>)`. Task 5 (`DatabaseNotificationPreferences`) consumes these directly.

- [ ] **Step 1: Update the `MapperTests` nested class's call sites (failing until Steps 3-5 land)**

Open `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java`, find the `MapperTests` nested class, and rename every call to `mapper.selectByPlayerAndCategory(...)` → `mapper.selectByPlayerAndDataType(...)`, `mapper.deleteByPlayerAndCategory(...)` → `mapper.deleteByPlayerAndDataType(...)`, and every `entity.category()` accessor call → `entity.dataType()`. Keep `mapper.insertPreferences(...)`'s call sites as-is (method name unchanged) — only its 2nd argument's conceptual meaning changes, not the call syntax. Do not touch `DatabaseNotificationPreferencesTests` yet (Task 5).

- [ ] **Step 2: Run the mapper tests to verify they fail**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest*MapperTests*"`
Expected: FAIL to compile (needs Docker for the full suite; a compile failure occurs even without Docker, which is the signal this step needs).

- [ ] **Step 3: Edit the migration SQL**

In `core/src/main/resources/sql/migrations/V1__maria_initial_schema.sql`, replace the `PlayerNotificationPreference` table definition:

```sql
CREATE TABLE IF NOT EXISTS PlayerNotificationPreference
(
    playerUuid BINARY(16)  NOT NULL,
    dataType   VARCHAR(64) NOT NULL,
    medium     VARCHAR(64) NOT NULL,
    PRIMARY KEY (playerUuid, dataType, medium)
);
```

- [ ] **Step 4: Rename the entity field**

`core/src/main/java/io/github/md5sha256/playernotifications/core/database/entity/PlayerNotificationPreferenceEntity.java`:

```java
package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;

/**
 * One row of the {@code PlayerNotificationPreference} table: a single medium a player has (explicitly
 * or via the {@code *} fallback) configured for one data type.
 *
 * @param dataType the data type key, or {@code *} for the pre-migration fallback row
 * @param medium   the medium key, or {@code none} for an explicit mute
 */
public record PlayerNotificationPreferenceEntity(
        @NotNull String dataType,
        @NotNull String medium
) {
}
```

- [ ] **Step 5: Rename the neutral mapper interface's methods**

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
 * stores a player's preferred media per {@code dataType} as rows sharing a {@code playerUuid}. SQL
 * annotations are supplied by database-specific sub-interfaces.
 */
public interface PlayerNotificationPreferenceMapper {

    /**
     * Every stored row for the player, across every data type (including the {@code *} fallback).
     */
    @NotNull List<PlayerNotificationPreferenceEntity> selectByPlayer(@NotNull UUID playerUuid);

    /**
     * The media stored for exactly the given data type. Empty if the player has no rows for that exact
     * data type — callers apply {@code *}/default fallback themselves.
     */
    @NotNull List<String> selectByPlayerAndDataType(@NotNull UUID playerUuid, @NotNull String dataType);

    /**
     * Inserts every given medium as a preference row for the player and data type in a single multi-row
     * statement. The caller must ensure {@code media} is non-empty; an empty collection would produce
     * invalid SQL.
     *
     * @return the number of rows inserted
     */
    int insertPreferences(@NotNull UUID playerUuid, @NotNull String dataType, @NotNull Collection<String> media);

    /**
     * Deletes every preference row for the given player and data type, e.g. before replacing them
     * wholesale or resetting the data type to the server default.
     *
     * @return the number of rows removed
     */
    int deleteByPlayerAndDataType(@NotNull UUID playerUuid, @NotNull String dataType);

    /**
     * Deletes every preference row for the given player across all data types.
     *
     * @return the number of rows removed
     */
    int deleteByPlayer(@NotNull UUID playerUuid);

}
```

- [ ] **Step 6: Rename the MariaDB mapper's SQL and methods**

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
            SELECT dataType, medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    @ConstructorArgs({
            @Arg(column = "dataType", javaType = String.class),
            @Arg(column = "medium", javaType = String.class)
    })
    @NotNull List<PlayerNotificationPreferenceEntity> selectByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

    @Override
    @Select("""
            SELECT medium
            FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND dataType = #{dataType}
            """)
    @NotNull List<String> selectByPlayerAndDataType(@Param("playerUuid") @NotNull UUID playerUuid,
                                                     @Param("dataType") @NotNull String dataType);

    @Override
    @Insert("""
            <script>
            INSERT INTO PlayerNotificationPreference (playerUuid, dataType, medium)
            VALUES
            <foreach item="medium" collection="media" separator=",">
                (#{playerUuid}, #{dataType}, #{medium})
            </foreach>
            </script>
            """)
    int insertPreferences(@Param("playerUuid") @NotNull UUID playerUuid,
                          @Param("dataType") @NotNull String dataType,
                          @Param("media") @NotNull Collection<String> media);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid} AND dataType = #{dataType}
            """)
    int deleteByPlayerAndDataType(@Param("playerUuid") @NotNull UUID playerUuid,
                                  @Param("dataType") @NotNull String dataType);

    @Override
    @Delete("""
            DELETE FROM PlayerNotificationPreference
            WHERE playerUuid = #{playerUuid}
            """)
    int deleteByPlayer(@Param("playerUuid") @NotNull UUID playerUuid);

}
```

- [ ] **Step 7: Run the mapper tests to verify they pass**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest*MapperTests*"`
Expected: PASS if Docker is running; if Docker is unavailable, note that and proceed — the code changes are still correct pending later full-suite verification (Task 14).

- [ ] **Step 8: Commit**

```bash
git add core/src/main/resources/sql/migrations/V1__maria_initial_schema.sql core/src/main/java/io/github/md5sha256/playernotifications/core/database/entity/PlayerNotificationPreferenceEntity.java core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/PlayerNotificationPreferenceMapper.java core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaPlayerNotificationPreferenceMapper.java core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java
git commit -m "refactor: rename PlayerNotificationPreference's category column to dataType"
```

---

### Task 5: Re-key `DatabaseNotificationPreferences` to `dataType`

**Files:**
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/DatabaseNotificationPreferences.java`
- Modify: `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java` (its `DatabaseNotificationPreferencesTests` nested class)

**Interfaces:**
- Consumes: `PlayerNotificationPreferenceMapper#selectByPlayerAndDataType`/`#deleteByPlayerAndDataType` from Task 4.
- Produces: `DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY`, `#effectiveMediaByDataType(UUID, Set<String>)`, `#explicitlyConfiguredDataTypes(UUID, Set<String>)`, `#applyChanges(UUID, Map<String,Set<String>>, Set<String> dataTypesToReset)`, `#muteAll(UUID, Set<String> dataTypes)`. Task 10 (`PreferenceDialogs`/`PreferenceDialogRouter`) is the consumer.

- [ ] **Step 1: Rewrite the `DatabaseNotificationPreferencesTests` nested class's call sites**

In `PlayerNotificationPreferenceTest.java`'s `DatabaseNotificationPreferencesTests` nested class, rename every reference: `DatabaseNotificationPreferences.ALL_CATEGORIES_KEY` → `ALL_DATA_TYPES_KEY`; `effectiveMediaByCategory` → `effectiveMediaByDataType`; `explicitlyConfiguredCategories` → `explicitlyConfiguredDataTypes`; `muteAll(uuid, categoryKeys)` → `muteAll(uuid, dataTypes)`; and rename local variables/test data from category-flavored strings (e.g. `"economy"`, `"moderation"`) to dataType-flavored ones only if the test's own naming implies category semantics — otherwise the string literals themselves can stay unchanged (a `dataType` is just as valid an arbitrary string as a `category` key was). Test method names like `effectiveMediaByCategoryResolvesEachCategory` → `effectiveMediaByDataTypeResolvesEachDataType`, `explicitlyConfiguredCategoriesReportsExactRowsOnly` → `explicitlyConfiguredDataTypesReportsExactRowsOnly`, `muteAllMutesEveryCategory` → `muteAllMutesEveryDataType`, `resetAllClearsEveryCategory` → `resetAllClearsEveryDataType`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest*DatabaseNotificationPreferencesTests*"`
Expected: FAIL to compile.

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
 * per {@code dataType}. A player with no rows for a {@code dataType} falls back to the {@link
 * #ALL_DATA_TYPES_KEY} rows (a blanket choice), then to a configurable default medium set, so a player
 * is never silently cut off from all notifications.
 */
public class DatabaseNotificationPreferences implements NotificationPreferences {

    /**
     * Reserved {@code dataType} key meaning "applies to any data type not otherwise configured" — a
     * blanket choice. Nothing in the dialogs writes it directly.
     */
    public static final String ALL_DATA_TYPES_KEY = "*";

    private final Database database;
    private volatile Set<String> defaultMedia;

    public DatabaseNotificationPreferences(@NotNull Database database, @NotNull Collection<String> defaultMedia) {
        this.database = database;
        this.defaultMedia = Set.copyOf(defaultMedia);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
        return preferredMedia(player, ALL_DATA_TYPES_KEY);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            if (!ALL_DATA_TYPES_KEY.equals(dataType)) {
                List<String> exact = mapper.selectByPlayerAndDataType(player, dataType);
                if (!exact.isEmpty()) {
                    return Set.copyOf(exact);
                }
            }
            List<String> fallback = mapper.selectByPlayerAndDataType(player, ALL_DATA_TYPES_KEY);
            if (!fallback.isEmpty()) {
                return Set.copyOf(fallback);
            }
            return this.defaultMedia;
        }
    }

    /**
     * Resolves the effective media for every given data type in one database round trip: exact rows,
     * else the {@link #ALL_DATA_TYPES_KEY} fallback, else the configured default. Used to load a
     * preference edit session's starting matrix.
     */
    public @NotNull Map<String, Set<String>> effectiveMediaByDataType(@NotNull UUID player,
                                                                       @NotNull Set<String> dataTypes) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byDataType = groupByDataType(wrapper, player);
            Set<String> fallback = byDataType.getOrDefault(ALL_DATA_TYPES_KEY, this.defaultMedia);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String dataType : dataTypes) {
                Set<String> exact = byDataType.get(dataType);
                result.put(dataType, exact != null ? Set.copyOf(exact) : Set.copyOf(fallback));
            }
            return Map.copyOf(result);
        }
    }

    /**
     * Which of the given data types the player has exact stored rows for, as opposed to inheriting the
     * {@link #ALL_DATA_TYPES_KEY} fallback or the configured default. Used to label a data type "server
     * default" in the preferences dialogs.
     */
    public @NotNull Set<String> explicitlyConfiguredDataTypes(@NotNull UUID player,
                                                               @NotNull Set<String> dataTypes) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byDataType = groupByDataType(wrapper, player);
            Set<String> configured = new HashSet<>();
            for (String dataType : dataTypes) {
                if (byDataType.containsKey(dataType)) {
                    configured.add(dataType);
                }
            }
            return Set.copyOf(configured);
        }
    }

    private static @NotNull Map<String, Set<String>> groupByDataType(@NotNull SqlSessionWrapper wrapper,
                                                                       @NotNull UUID player) {
        List<PlayerNotificationPreferenceEntity> rows =
                wrapper.playerNotificationPreferenceMapper().selectByPlayer(player);
        Map<String, Set<String>> byDataType = new HashMap<>();
        for (PlayerNotificationPreferenceEntity row : rows) {
            byDataType.computeIfAbsent(row.dataType(), key -> new TreeSet<>()).add(row.medium());
        }
        return byDataType;
    }

    /**
     * Applies a batch of staged changes in one transaction. Data types in {@code dataTypesToReset} have
     * their rows deleted, falling back to {@link #ALL_DATA_TYPES_KEY}/the configured default again.
     * Every entry in {@code explicitMedia} wholesale-replaces that data type's rows; an empty set is not
     * a valid value here — callers encode a mute as {@code {"none"}}.
     */
    public void applyChanges(@NotNull UUID player,
                             @NotNull Map<String, Set<String>> explicitMedia,
                             @NotNull Set<String> dataTypesToReset) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            for (String dataType : dataTypesToReset) {
                mapper.deleteByPlayerAndDataType(player, dataType);
            }
            for (Map.Entry<String, Set<String>> entry : explicitMedia.entrySet()) {
                mapper.deleteByPlayerAndDataType(player, entry.getKey());
                mapper.insertPreferences(player, entry.getKey(), entry.getValue());
            }
            wrapper.session().commit();
        }
    }

    /**
     * Immediately mutes every given data type for the player in one transaction, storing an explicit
     * {@code none} row for each. Used by {@code /notifications mute}.
     */
    public void muteAll(@NotNull UUID player, @NotNull Set<String> dataTypes) {
        Map<String, Set<String>> mutes = new LinkedHashMap<>();
        for (String dataType : dataTypes) {
            mutes.put(dataType, Set.of(NullSink.MEDIUM_KEY));
        }
        applyChanges(player, mutes, Set.of());
    }

    /**
     * Immediately clears every stored row for the player, across every data type. Used by
     * {@code /notifications reset}.
     */
    public void resetAll(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.playerNotificationPreferenceMapper().deleteByPlayer(player);
            wrapper.session().commit();
        }
    }

    /**
     * Replaces the configured default media, e.g. after {@code settings.yml} is reloaded. Takes effect
     * for any {@link #preferredMedia} call made after this returns; in-flight calls may still observe
     * the previous value.
     */
    public void reloadDefaultMedia(@NotNull Collection<String> defaultMedia) {
        this.defaultMedia = Set.copyOf(defaultMedia);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.PlayerNotificationPreferenceTest"`
Expected: PASS if Docker is running.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/github/md5sha256/playernotifications/core/DatabaseNotificationPreferences.java core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java
git commit -m "refactor: re-key DatabaseNotificationPreferences from category to dataType"
```

---

### Task 6: Simplify `NotificationDelivery` — drop category resolution entirely

**Files:**
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/NotificationDelivery.java`
- Modify: `core/src/test/java/io/github/md5sha256/playernotifications/core/database/NotificationDeliveryPrecedenceTest.java`

**Interfaces:**
- Consumes: `RenderingProcessor`'s new required-dataType constructor (Task 3).
- Produces: `NotificationDelivery`'s 5-arg constructor `(Database, NotificationDataTypeRegistry, @Nullable NotificationSinkRegistry, @Nullable NotificationPreferences, Logger)` as the full rendering-capable form; the 6-arg category-resolving constructor is deleted. Task 8 (`PlayerNotificationsPlugin`) is the consumer.

- [ ] **Step 1: Rewrite `rendererPathResolvesCategory` to assert direct dataType pass-through**

Open `core/src/test/java/io/github/md5sha256/playernotifications/core/database/NotificationDeliveryPrecedenceTest.java`. Find the test currently named `rendererPathResolvesCategory` (around lines 107-151), which builds a `NotificationCategories` from a `NotificationCategoriesConfig` and constructs `NotificationDelivery` via the 6-arg constructor, then asserts `categoriesSeen` equals `List.of("economy")`. Read the full test first to see its exact `TYPE`/renderer/preferences fixture setup, then rewrite it to:

```java
    @Test
    @DisplayName("rendering path passes the notification's dataType directly to preferredMedia, no category involved")
    void rendererPathPassesDataTypeDirectly() {
        // Reuse this test class's existing TYPE constant, renderer, sink registry, and database fixtures
        // (read the surrounding class to match field/helper names exactly) — only the preferences fake
        // and the NotificationDelivery construction change.
        List<String> dataTypesSeen = new ArrayList<>();
        NotificationPreferences preferences = new NotificationPreferences() {
            @Override
            public Set<String> preferredMedia(UUID player) {
                dataTypesSeen.add(null);
                return Set.of();
            }

            @Override
            public Set<String> preferredMedia(UUID player, String dataType) {
                dataTypesSeen.add(dataType);
                return Set.of("chat");
            }
        };

        NotificationDelivery delivery = new NotificationDelivery(
                database, registry, sinkRegistry, preferences, Logger.getLogger("test"));

        // ... keep this test's existing enqueue + delivery invocation and DELETE/DELIVERED assertions,
        // matching however the rest of the class exercises delivery (read it first) ...

        Assertions.assertEquals(List.of(TYPE), dataTypesSeen);
    }
```

Keep every other test in this file (the explicit-processor-wins, renderer-only-dispatches, neither-retains cases) unchanged — they don't touch categories.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.NotificationDeliveryPrecedenceTest"`
Expected: FAIL to compile — `NotificationDelivery`'s 5-arg constructor still expects `@Nullable NotificationPreferences` as the last-but-one param and there's no ambiguity yet, but the 6-arg category constructor this test no longer uses must still be removed; compile first against old code to confirm the *old* 6-arg-based test compiled, then proceed to Step 3 to make the new version fail meaningfully once the 6-arg constructor is gone in Step 3's diff.

- [ ] **Step 3: Rewrite `NotificationDelivery`**

Remove the `categories` field, the 6-arg constructor, and the `import io.github.md5sha256.playernotifications.core.category.NotificationCategories;` import. The class becomes:

```java
package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderingProcessor;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.NotificationTargetMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Delivers a player's due notifications through the processors registered in the
 * {@link NotificationDataTypeRegistry}, and prunes each target whose processor flags the
 * notification for {@link NotificationDisposition#DELETE deletion}.
 *
 * <p>A notification is processed once per target: {@link #deliver(UUID)} resolves the notifications
 * currently due for one player, looks up the processor for each notification's payload type, invokes
 * it for that single player, and — when the processor returns {@code DELETE} — removes the player
 * from the notification's target group. Removing the last target deletes the notification itself
 * (enforced by a database trigger).
 *
 * <p>Processors are invoked outside any open database transaction, so their side effects (which may
 * marshal onto another thread) do not hold database resources.
 *
 * <p>Dispatch precedence: an explicitly registered {@link NotificationProcessor} always wins (so
 * {@code EssentialsMailProcessor} and other bespoke processors keep working unchanged). Otherwise, if a
 * {@link NotificationRenderer} is registered for the payload class, the notification is dispatched
 * through a framework-supplied {@link RenderingProcessor}, which resolves preferred media for the
 * notification's {@code notifPayloadType} directly and fans it out to the target's preferred media.
 * Otherwise the notification is logged and retained.
 */
public class NotificationDelivery {

    private final Database database;
    private final NotificationDataTypeRegistry registry;
    private final NotificationSinkRegistry sinkRegistry;
    private final NotificationPreferences preferences;
    private final Logger logger;

    /**
     * Constructs a delivery loop with no rendering path: only explicitly registered
     * {@link NotificationProcessor}s are dispatched. A payload with only a {@link NotificationRenderer}
     * registered is retained, exactly as one with neither.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @NotNull Logger logger) {
        this(database, registry, null, null, logger);
    }

    /**
     * Constructs a delivery loop with the rendering path enabled: a payload with a registered
     * {@link NotificationRenderer} (and no explicit processor) is dispatched through a
     * {@link RenderingProcessor} built from the given sink registry and preferences, resolving preferred
     * media directly against the notification's {@code notifPayloadType} via
     * {@link NotificationPreferences#preferredMedia(UUID, String)}.
     */
    public NotificationDelivery(@NotNull Database database,
                                @NotNull NotificationDataTypeRegistry registry,
                                @Nullable NotificationSinkRegistry sinkRegistry,
                                @Nullable NotificationPreferences preferences,
                                @NotNull Logger logger) {
        this.database = database;
        this.registry = registry;
        this.sinkRegistry = sinkRegistry;
        this.preferences = preferences;
        this.logger = logger;
    }

    /**
     * Delivers every notification currently due for the given player as of now.
     */
    public void deliver(@NotNull UUID target) {
        deliver(target, Instant.now());
    }

    /**
     * Delivers every notification due for the given player as of {@code now}.
     */
    public void deliver(@NotNull UUID target, @NotNull Instant now) {
        List<NotificationEntity> due;
        try (SqlSessionWrapper wrapper = database.openSession()) {
            due = wrapper.notificationMapper().selectDueByPlayer(target, now);
        }

        List<NotificationEntity> toPrune = new ArrayList<>();
        for (NotificationEntity notification : due) {
            if (dispatch(notification, target) == NotificationDisposition.DELETE) {
                toPrune.add(notification);
            }
        }

        if (toPrune.isEmpty()) {
            return;
        }
        try (SqlSessionWrapper wrapper = database.openSession()) {
            NotificationTargetMapper targetMapper = wrapper.notificationTargetMapper();
            for (NotificationEntity notification : toPrune) {
                targetMapper.deleteMembers(notification.notifTargetId(), List.of(target));
            }
            wrapper.session().commit();
        }
    }

    private @NotNull NotificationDisposition dispatch(@NotNull NotificationEntity notification,
                                                      @NotNull UUID target) {
        Optional<Class<?>> payloadClass = registry.resolvePayloadClass(notification.notifPayloadType());
        if (payloadClass.isEmpty()) {
            logger.fine(() -> "No payload mapping for data type '" + notification.notifPayloadType()
                    + "'; retaining " + notification.notifKey());
            return NotificationDisposition.RETAIN;
        }

        Optional<? extends NotificationProcessor<?>> processor =
                registry.getProcessor(notification.notifPayloadType());
        if (processor.isPresent()) {
            Object payload = decodePayload(notification.notifPayload(), payloadClass.get());
            if (payload == null) {
                return NotificationDisposition.RETAIN;
            }
            return invoke(processor.get(), payload, target);
        }

        Optional<? extends NotificationRenderer<?>> renderer =
                registry.getRenderer(notification.notifPayloadType());
        if (renderer.isPresent() && this.sinkRegistry != null && this.preferences != null) {
            Object payload = decodePayload(notification.notifPayload(), payloadClass.get());
            if (payload == null) {
                return NotificationDisposition.RETAIN;
            }
            NotificationProcessor<?> renderingProcessor =
                    new RenderingProcessor<>(castRenderer(renderer.get()), this.sinkRegistry,
                            this.preferences, notification.notifPayloadType(), this.logger);
            return invoke(renderingProcessor, payload, target);
        }

        logger.fine(() -> "No processor or renderer for data type '" + notification.notifPayloadType()
                + "'; retaining " + notification.notifKey());
        return NotificationDisposition.RETAIN;
    }

    @SuppressWarnings("unchecked")
    private @NotNull NotificationRenderer<Object> castRenderer(@NotNull NotificationRenderer<?> renderer) {
        return (NotificationRenderer<Object>) renderer;
    }

    private @Nullable Object decodePayload(@NotNull String rawPayload, @NotNull Class<?> payloadClass) {
        Optional<? extends PayloadSerializer<?>> serializer = registry.getSerializer(payloadClass);
        if (serializer.isEmpty()) {
            logger.warning("No serializer registered for payload type " + payloadClass.getName()
                    + "; retaining notification");
            return null;
        }
        try {
            return serializer.get().deserialize(rawPayload);
        } catch (RuntimeException e) {
            logger.warning("Failed to deserialize payload of type " + payloadClass.getName()
                    + "; retaining notification: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private @NotNull NotificationDisposition invoke(@NotNull NotificationProcessor<?> processor,
                                                    @NotNull Object payload,
                                                    @NotNull UUID target) {
        return ((NotificationProcessor<Object>) processor).receiveNotification(payload, target);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.database.NotificationDeliveryPrecedenceTest" --tests "io.github.md5sha256.playernotifications.core.database.NotificationDeliveryTest"`
Expected: PASS if Docker is running (`NotificationDeliveryTest` only uses the 3-arg no-render constructor and is otherwise unaffected — confirm it still compiles and passes).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/github/md5sha256/playernotifications/core/NotificationDelivery.java core/src/test/java/io/github/md5sha256/playernotifications/core/database/NotificationDeliveryPrecedenceTest.java
git commit -m "refactor: NotificationDelivery drops category resolution, dispatches dataType directly"
```

---

### Task 7: `core.category.NotificationCategories` becomes a many-to-many merge of config and the code registry

**Files:**
- Modify: `core/src/main/java/io/github/md5sha256/playernotifications/core/category/NotificationCategories.java`
- Modify: `core/src/test/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoriesTest.java`

**Interfaces:**
- Consumes: `NotificationCategoryRegistry` from Task 1.
- Produces: `NotificationCategories(NotificationCategoriesConfig, NotificationCategoryRegistry, Logger)`; `resolve(String): @NotNull Set<String>`; `dataTypesForCategory(String categoryKey, Set<String> allKnownDataTypes): @NotNull Set<String>` — new, needed by Task 12's dialog fan-out and mixed-state logic. Task 8 (`PlayerNotificationsPlugin`) and Task 11/12 (dialogs) are consumers.

- [ ] **Step 1: Rewrite the test file**

```java
package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

class NotificationCategoriesTest {

    private static NotificationCategories build(Map<String, NotificationCategoryDefinition> categories) {
        return build(categories, new DefaultNotificationCategoryRegistry());
    }

    private static NotificationCategories build(Map<String, NotificationCategoryDefinition> categories,
                                                 NotificationCategoryRegistry registry) {
        return new NotificationCategories(
                new NotificationCategoriesConfig("Other", categories), registry, Logger.getLogger("test"));
    }

    @Test
    void resolvesConfiguredType() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals(Set.of("economy"), categories.resolve("mail"));
    }

    @Test
    void unclaimedTypeFallsBackToUncategorized() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals(Set.of(NotificationCategories.UNCATEGORIZED), categories.resolve("nothing-registered"));
    }

    @Test
    void aDataTypeClaimedByTwoCategoriesResolvesToBoth() {
        Map<String, NotificationCategoryDefinition> ordered = new LinkedHashMap<>();
        ordered.put("economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")));
        ordered.put("moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("mail")));
        NotificationCategories categories = build(ordered);

        Assertions.assertEquals(Set.of("economy", "moderation"), categories.resolve("mail"));
    }

    @Test
    void aDataTypeClaimedByOneConfigAndOneCodeCategoryResolvesToBoth() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.claimDataType("essentials-mail-adapter", "mail");
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))), registry);

        Assertions.assertEquals(Set.of("economy", "essentials-mail-adapter"), categories.resolve("mail"));
    }

    @Test
    void categoryKeysIncludesUncategorizedAndCodeRegisteredCategories() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.registerCategory("essentials-mail-adapter", "Mail", "desc");
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))), registry);

        Assertions.assertEquals(Set.of("economy", "essentials-mail-adapter", "uncategorized"),
                categories.categoryKeys());
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
    void configWinsLabelOnKeyCollisionWithCode() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.registerCategory("economy", "Code Economy Label", "code desc");
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Config Economy Label", "config desc", List.of())),
                registry);

        Assertions.assertEquals("Config Economy Label", categories.label("economy"));
        Assertions.assertEquals("config desc", categories.description("economy"));
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

    @Test
    void dataTypesForCategoryReturnsMembersAndUncategorizedReturnsTheComplement() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))));
        Set<String> allKnownDataTypes = Set.of("mail", "warning");

        Assertions.assertEquals(Set.of("mail"), categories.dataTypesForCategory("economy", allKnownDataTypes));
        Assertions.assertEquals(Set.of("warning"),
                categories.dataTypesForCategory(NotificationCategories.UNCATEGORIZED, allKnownDataTypes));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.category.NotificationCategoriesTest"`
Expected: FAIL to compile — old constructor signature, `resolve` returns `String` not `Set<String>`, `dataTypesForCategory` doesn't exist.

- [ ] **Step 3: Rewrite `NotificationCategories`**

```java
package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves a registered {@code dataType} to every player-facing category that claims it — a read-side
 * merge, built once at startup (and on {@code /notifications reload}), of {@code categories.yml} and the
 * programmatic {@link NotificationCategoryRegistry}. A data type no category claims resolves to
 * {@link #UNCATEGORIZED}, which is always a real, selectable category — a newly installed module's
 * notifications are configurable immediately, without an operator editing config first.
 *
 * <p>A {@code dataType} may be claimed by any number of categories; membership is a set, so there is no
 * collision to resolve. A collision on the category <em>key</em> itself (code and config both defining
 * {@code economy}) is not an error: config's label/description wins, and the collision is logged at
 * {@code fine}.
 */
public final class NotificationCategories {

    public static final String UNCATEGORIZED = "uncategorized";

    private final Map<String, Set<String>> dataTypeToCategories;
    private final Map<String, String> labels;
    private final Map<String, String> descriptions;
    private final Set<String> categoryKeys;
    private final String uncategorizedLabel;

    public NotificationCategories(@NotNull NotificationCategoriesConfig config,
                                  @NotNull NotificationCategoryRegistry registry,
                                  @NotNull Logger logger) {
        this.uncategorizedLabel = config.uncategorizedLabel();

        Map<String, String> labels = new HashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, Set<String>> dataTypeToCategories = new HashMap<>();

        for (String categoryKey : registry.categoryKeys()) {
            keys.add(categoryKey);
            labels.put(categoryKey, registry.label(categoryKey));
            descriptions.put(categoryKey, registry.description(categoryKey));
            for (String dataType : registry.dataTypesFor(categoryKey)) {
                dataTypeToCategories.computeIfAbsent(dataType, key -> new HashSet<>()).add(categoryKey);
            }
        }

        for (Map.Entry<String, NotificationCategoryDefinition> entry : config.categories().entrySet()) {
            String categoryKey = entry.getKey();
            if (keys.contains(categoryKey)) {
                logger.fine(() -> "Category '" + categoryKey
                        + "' is defined both in code and in categories.yml; using config's label/description");
            }
            keys.add(categoryKey);
            labels.put(categoryKey, entry.getValue().label());
            descriptions.put(categoryKey, entry.getValue().description());
            for (String dataType : entry.getValue().types()) {
                dataTypeToCategories.computeIfAbsent(dataType, key -> new HashSet<>()).add(categoryKey);
            }
        }

        this.categoryKeys = Set.copyOf(keys);
        this.labels = Map.copyOf(labels);
        this.descriptions = Map.copyOf(descriptions);
        Map<String, Set<String>> frozen = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : dataTypeToCategories.entrySet()) {
            frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        this.dataTypeToCategories = Map.copyOf(frozen);
    }

    /**
     * Every category (config- and code-claimed) that claims the given data type. Never empty —
     * an unclaimed data type resolves to {@code {UNCATEGORIZED}}.
     */
    @NotNull
    public Set<String> resolve(@NotNull String dataType) {
        Set<String> claimed = this.dataTypeToCategories.get(dataType);
        return claimed != null && !claimed.isEmpty() ? claimed : Set.of(UNCATEGORIZED);
    }

    /**
     * Every selectable category key, including {@link #UNCATEGORIZED}.
     */
    @NotNull
    public Set<String> categoryKeys() {
        Set<String> keys = new LinkedHashSet<>(this.categoryKeys);
        keys.add(UNCATEGORIZED);
        return Set.copyOf(keys);
    }

    /**
     * The player-facing label for a category key, falling back to the key itself if the category has
     * vanished from both config and the code registry since a player last saw it.
     */
    @NotNull
    public String label(@NotNull String categoryKey) {
        if (UNCATEGORIZED.equals(categoryKey)) {
            return this.uncategorizedLabel;
        }
        return this.labels.getOrDefault(categoryKey, categoryKey);
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
        return this.descriptions.getOrDefault(categoryKey, "");
    }

    /**
     * Every data type claimed by some category (config or code) that the given registry has no payload
     * mapping for. Intended to be checked once at startup, after feature modules have registered their
     * payload mappings and their category claims, so an operator sees a standing misconfiguration rather
     * than a silent no-op.
     */
    @NotNull
    public Set<String> typesWithNoPayloadMapping(@NotNull NotificationDataTypeRegistry registry) {
        Set<String> unmapped = new HashSet<>();
        for (String dataType : this.dataTypeToCategories.keySet()) {
            if (registry.resolvePayloadClass(dataType).isEmpty()) {
                unmapped.add(dataType);
            }
        }
        return Set.copyOf(unmapped);
    }

    /**
     * Every member of {@code allKnownDataTypes} that {@link #resolve} claims for {@code categoryKey}.
     * For {@link #UNCATEGORIZED}, this is every data type no other category claims — the complement, not
     * a stored set — which is why the full universe of known data types must be supplied by the caller
     * (there is no other source of it here). Used by the preference dialogs' "by notification type"
     * bulk-edit fan-out.
     */
    @NotNull
    public Set<String> dataTypesForCategory(@NotNull String categoryKey, @NotNull Set<String> allKnownDataTypes) {
        Set<String> result = new HashSet<>();
        for (String dataType : allKnownDataTypes) {
            if (resolve(dataType).contains(categoryKey)) {
                result.add(dataType);
            }
        }
        return Set.copyOf(result);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.core.category.NotificationCategoriesTest"`
Expected: PASS (this test class needs no Docker — it's pure unit logic).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/github/md5sha256/playernotifications/core/category/NotificationCategories.java core/src/test/java/io/github/md5sha256/playernotifications/core/category/NotificationCategoriesTest.java
git commit -m "refactor: NotificationCategories merges code registry and config, resolve() returns a Set"
```

---

### Task 8: Wire `PlayerNotificationsPlugin` — category registry threading, delivery simplification, reload ordering

**Files:**
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`

**Interfaces:**
- Consumes: `NotificationService#categoryRegistry()` (Task 2), `NotificationDelivery`'s simplified 5-arg constructor (Task 6), `NotificationCategories`'s new 3-arg constructor (Task 7).
- Produces: nothing new consumed elsewhere in this plan, but `PreferenceDialogRouter`'s constructor call here changes in Task 10 to add a `NotificationDataTypeRegistry` argument — leave a placeholder comment at that call site in this task so Task 10 knows exactly where to add it (or, if Task 10 is done first in execution, this task's diff already reflects the updated call — read the current file state before editing).

**Ordering note:** `NotificationCategoryRegistry` claims made by feature modules only exist after `startModules()` runs, but the merged `NotificationCategories` is needed earlier, to construct `PreferenceDialogRouter` (in `registerCommands()`, which currently runs before `startModules()`). Resolve this the same way `warnAboutUnmappedCategoryTypes()` already does: build an initial `NotificationCategories` from config only (registry has no module claims yet) to unblock `registerCommands()`, then rebuild it after `startModules()` and swap it in via the existing `preferenceDialogRouter.reloadCategories(...)` mechanism — reusing the exact machinery `reload()` already exercises, not new code paths.

- [ ] **Step 1: Update `onEnable()`**

Replace the body of `onEnable()`:

```java
    @Override
    public void onEnable() {
        DatabaseSettings databaseSettings;
        PluginSettings pluginSettings;
        try {
            databaseSettings = loadDatabaseSettings();
            pluginSettings = loadPluginSettings();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load configuration; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MariaDatabase mariaDatabase = new MariaDatabase(databaseSettings, getLogger());
        this.database = mariaDatabase;
        try {
            mariaDatabase.initializeSchema(MIGRATIONS_DIR);
        } catch (IOException | SQLException ex) {
            getLogger().log(Level.SEVERE,
                    "Database schema migration failed; disabling plugin.",
                    ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.notificationService = new DefaultNotificationService(mariaDatabase);
        getServer().getServicesManager().register(
                NotificationService.class,
                this.notificationService,
                this,
                ServicePriority.Normal
        );

        try {
            this.categories = loadCategories();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load categories.yml; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.sinkRegistry = new NotificationSinkRegistry();
        this.sinkRegistry.registerSink(new ChatSink(this));
        this.sinkRegistry.registerSink(new DialogSink(this));
        // Backs an explicit mute; not offered as a choice in the preferences dialog.
        this.sinkRegistry.registerSink(new NullSink());
        this.preferences =
                new DatabaseNotificationPreferences(mariaDatabase, pluginSettings.defaultMedia());
        this.notificationDelivery = new NotificationDelivery(
                mariaDatabase,
                this.notificationService.dataTypeRegistry(),
                this.sinkRegistry,
                this.preferences,
                getLogger()
        );

        registerCommands();
        schedulePruneTask(pluginSettings.pruneIntervalSeconds());

        // Start modules last so they can look up the registered NotificationService and register their
        // own category claims against it.
        startModules();

        // Rebuild the merged categories now that modules have had a chance to register, and swap the
        // rebuilt view into the dialog router — the same mechanism /notifications reload uses.
        try {
            this.categories = loadCategories();
            this.preferenceDialogRouter.reloadCategories(this.categories);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to rebuild categories after module startup.", ex);
        }
        warnAboutUnmappedCategoryTypes();
        getLogger().info("PlayerNotifications enabled");
    }
```

- [ ] **Step 2: Update `loadCategories()`**

```java
    private NotificationCategories loadCategories() throws IOException {
        ConfigurationNode root = copyDefaultsYaml("categories");
        NotificationCategoriesConfig config = root.get(NotificationCategoriesConfig.class);
        if (config == null) {
            throw new IOException("categories.yml could not be deserialized into NotificationCategoriesConfig");
        }
        return new NotificationCategories(config, this.notificationService.categoryRegistry(), getLogger());
    }
```

- [ ] **Step 3: Update `registerCommands()`**

Change the `PreferenceDialogRouter` construction to pass the data type registry too:

```java
    @SuppressWarnings("UnstableApiUsage")
    private void registerCommands() {
        this.preferenceDialogRouter = new PreferenceDialogRouter(
                this, this.sinkRegistry, this.categories, this.notificationService.dataTypeRegistry(),
                this.preferences);
        getServer().getPluginManager().registerEvents(
                new PreferenceQuitListener(this.preferenceDialogRouter.sessions()), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        NotificationsCommand.create(this.preferenceDialogRouter, this::reload),
                        NotificationsCommand.DESCRIPTION,
                        List.of("notifs")
                ));
    }
```

(This call site's exact argument order/count must match `PreferenceDialogRouter`'s constructor as defined in Task 10 — if Task 10 hasn't landed yet when this step is executed, come back and fix this call site once it has; both tasks touch this one line and must agree.)

- [ ] **Step 4: Update `reload()`**

Drop the `categories` argument from the `NotificationDelivery` reconstruction (it no longer takes one):

```java
    public void reload(@NotNull CommandSender sender) {
        NotificationCategories newCategories;
        PluginSettings newSettings;
        try {
            newCategories = loadCategories();
            newSettings = loadPluginSettings();
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to reload configuration.", ex);
            sender.sendMessage(Component.text(
                    "Failed to reload configuration: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }

        this.categories = newCategories;
        this.preferenceDialogRouter.reloadCategories(newCategories);
        this.notificationDelivery = new NotificationDelivery(
                this.database,
                this.notificationService.dataTypeRegistry(),
                this.sinkRegistry,
                this.preferences,
                getLogger()
        );
        this.preferences.reloadDefaultMedia(newSettings.defaultMedia());
        reschedulePruneTask(newSettings.pruneIntervalSeconds());
        warnAboutUnmappedCategoryTypes();

        getLogger().info("Configuration reloaded by " + sender.getName());
        sender.sendMessage(Component.text(
                "PlayerNotifications configuration reloaded.", NamedTextColor.GREEN));
    }
```

- [ ] **Step 5: Update the `categories()` accessor's javadoc**

```java
    /**
     * Resolves a registered {@code dataType} to every player-facing category (config- and
     * code-claimed) that claims it.
     */
    @NotNull
    public NotificationCategories categories() {
        return this.categories;
    }
```

- [ ] **Step 6: Compile the platform module**

Run: `./gradlew :platform:paper-plugin:compileJava`
Expected: FAIL until Task 10 also updates `PreferenceDialogRouter`'s constructor to match — that's expected at this point in isolated execution; if executing tasks strictly in order, treat this as a checkpoint to revisit after Task 10, not a hard blocker for committing this task's other changes. If running tasks in dependency order (this plan's numbering), do Task 10 immediately after this one before considering either "done".

- [ ] **Step 7: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java
git commit -m "refactor: wire NotificationCategoryRegistry into plugin startup and reload"
```

---

### Task 9: Re-key `PreferenceEditSession` to `dataType`

**Files:**
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSession.java`
- Modify: `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSessionTest.java`
- Modify: `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceSessionManagerTest.java` (only if it references the renamed methods directly — it constructs `PreferenceEditSession` via its constructor, whose shape is unchanged, so likely no changes needed; verify by reading it first)

**Interfaces:**
- Produces: `PreferenceEditSession#mediaFor(String dataType)`, `#setDataTypeMedia(String, Set<String>, Instant)`, `#resetDataType(String, Instant)`, `#toggleDataTypeMedium(String, String, boolean, Instant)`, `#dirtyDataTypes()`, `#dataTypesToReset()`, `#explicitChanges(): Map<String dataType, Set<String>>`, `#isUsingServerDefault(String dataType)`. Tasks 10-13 (dialogs/router) are the consumers.

- [ ] **Step 1: Rewrite the test file**

Read `PreferenceEditSessionTest.java` in full first to capture its exact existing test method names and fixture setup, then rename every method call and test name from category-flavored to dataType-flavored, 1:1, preserving all existing assertions and scenarios exactly (test data string literals like `"economy"` can stay as arbitrary dataType strings — only method/field names and prose change). Concretely:

- `setCategoryMediaMarksItDirty` → `setDataTypeMediaMarksItDirty`, replacing `session.setCategoryMedia(...)` → `session.setDataTypeMedia(...)`
- `toggleCategoryMediumAddsAndRemoves` → `toggleDataTypeMediumAddsAndRemoves`, replacing `session.toggleCategoryMedium(...)` → `session.toggleDataTypeMedium(...)`
- `emptyingACategoryStagesAMute` → `emptyingADataTypeStagesAMute`
- `resetCategoryStagesAResetUsingFallbackMedia` → `resetDataTypeStagesAResetUsingFallbackMedia`, replacing `session.resetCategory(...)` → `session.resetDataType(...)`
- `reSettingMediaAfterAResetCancelsTheReset` — rename internal calls only, method name can stay if it doesn't reference "category"
- `isUsingServerDefaultReflectsLoadStateAndStagedResets` — replace calls to `session.isUsingServerDefault(...)` (signature unchanged, still fine)
- `startsClean`, `expiresAfterTheIdleTimeout`, `touchingResetsTheIdleClock` — likely need no renames (check for `mediaFor`/`dirtyCategories` usage and rename those specific calls if present)
- Any direct field/method reference to `dirtyCategories()` → `dirtyDataTypes()`, `categoriesToReset()` → `dataTypesToReset()`, `mediaFor(...)` calls keep the same name (just conceptually a dataType arg now).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSessionTest"`
Expected: FAIL to compile.

- [ ] **Step 3: Rewrite `PreferenceEditSession`**

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
 * A player's in-progress edits to their dataType x medium preference matrix. Both preference dialogs
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
    private final Set<String> dirtyDataTypes = new HashSet<>();
    private final Set<String> resetDataTypes = new HashSet<>();
    private Instant lastTouched;

    /**
     * @param initialEffectiveMedia the matrix as it would currently apply, per data type (exact rows,
     *                              else the {@code *} fallback, else the configured default)
     * @param explicitAtLoad        the data types that had exact stored rows when this session was
     *                              loaded, used by {@link #isUsingServerDefault(String)}
     * @param fallbackMedia         the plain {@code *}/configured-default media, used to populate a
     *                              data type when it is reset
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
    public Set<String> mediaFor(@NotNull String dataType) {
        return Set.copyOf(this.media.getOrDefault(dataType, Set.of()));
    }

    /**
     * Overwrites one data type's staged media, marking it dirty. An empty set stages a mute, not a
     * fall-through to the server default — only {@link #resetDataType(String, Instant)} does that.
     */
    public void setDataTypeMedia(@NotNull String dataType, @NotNull Set<String> newMedia, @NotNull Instant now) {
        this.media.put(dataType, new TreeSet<>(newMedia));
        this.dirtyDataTypes.add(dataType);
        this.resetDataTypes.remove(dataType);
        this.lastTouched = now;
    }

    /**
     * Stages "use the server default" for one data type: its staged media becomes the fallback media
     * captured at load time, and it is written by clearing its rows on {@code Apply} rather than by
     * writing the fallback media explicitly.
     */
    public void resetDataType(@NotNull String dataType, @NotNull Instant now) {
        this.media.put(dataType, new TreeSet<>(this.fallbackMedia));
        this.dirtyDataTypes.add(dataType);
        this.resetDataTypes.add(dataType);
        this.lastTouched = now;
    }

    /**
     * Toggles a single medium within a single data type — the operation the "by delivery method" editor
     * performs on Save.
     */
    public void toggleDataTypeMedium(@NotNull String dataType, @NotNull String medium, boolean enabled,
                                     @NotNull Instant now) {
        Set<String> current = new TreeSet<>(this.media.getOrDefault(dataType, Set.of()));
        if (enabled) {
            current.add(medium);
        } else {
            current.remove(medium);
        }
        setDataTypeMedia(dataType, current, now);
    }

    public boolean isDirty() {
        return !this.dirtyDataTypes.isEmpty();
    }

    public int dirtyCount() {
        return this.dirtyDataTypes.size();
    }

    @NotNull
    public Set<String> dirtyDataTypes() {
        return Set.copyOf(this.dirtyDataTypes);
    }

    @NotNull
    public Set<String> dataTypesToReset() {
        return Set.copyOf(this.resetDataTypes);
    }

    /**
     * Dirty data types that are explicit selections rather than resets, keyed to the media that should
     * be written wholesale. An empty selection is encoded as {@link NullSink#MEDIUM_KEY}.
     */
    @NotNull
    public Map<String, Set<String>> explicitChanges() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String dataType : this.dirtyDataTypes) {
            if (!this.resetDataTypes.contains(dataType)) {
                Set<String> selected = this.media.getOrDefault(dataType, Set.of());
                result.put(dataType, selected.isEmpty() ? Set.of(NullSink.MEDIUM_KEY) : Set.copyOf(selected));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Whether the given data type is currently showing the server default rather than an explicit
     * choice — true if it was never explicitly configured and has not been touched, or if it has been
     * staged for reset.
     */
    public boolean isUsingServerDefault(@NotNull String dataType) {
        if (this.dirtyDataTypes.contains(dataType)) {
            return this.resetDataTypes.contains(dataType);
        }
        return !this.explicitAtLoad.contains(dataType);
    }

    public boolean isExpired(@NotNull Instant now, @NotNull Duration idleTimeout) {
        return Duration.between(this.lastTouched, now).compareTo(idleTimeout) > 0;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :platform:paper-plugin:test --tests "io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSessionTest" --tests "io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManagerTest"`
Expected: PASS. `PreferenceSessionManagerTest` should compile unchanged since the constructor shape didn't change; if it references any renamed method, fix those call sites the same way.

- [ ] **Step 5: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSession.java platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSessionTest.java
git commit -m "refactor: re-key PreferenceEditSession from category to dataType"
```

---

### Task 10: `PreferenceDialogs` and `PreferenceDialogRouter` — thread the data type registry through session loading, apply, and bulk mute

**Files:**
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogs.java`
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogRouter.java`
- Modify: `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogsTest.java` (only if it calls `withSession` or the renamed static helpers directly — read it first; it likely only tests `selectableMedia`/`sortedCategoryKeys`/`inputKey`, which are unaffected)

**Interfaces:**
- Consumes: `PreferenceEditSession`'s renamed methods (Task 9), `DatabaseNotificationPreferences`'s renamed methods (Task 5), `NotificationDataTypeRegistry#dataTypes()` (Task 1).
- Produces: `PreferenceDialogRouter(Plugin, NotificationSinkRegistry, NotificationCategories, NotificationDataTypeRegistry, DatabaseNotificationPreferences)` — new constructor shape; `PreferenceDialogRouter#dataTypeRegistry(): NotificationDataTypeRegistry` (package-private accessor, mirroring `categories()`); `PreferenceDialogs.sortedDataTypes(NotificationCategories, NotificationDataTypeRegistry)`, `PreferenceDialogs.primaryCategoryFor(NotificationCategories, String)`, `PreferenceDialogs.dataTypeLabel(NotificationCategories, String)`. Tasks 11-13 (the dialog screens) consume all of these.

- [ ] **Step 1: Update `PreferenceDialogs.withSession` and add the new sorting/labeling helpers**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
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
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Every known data type, sorted by its primary category's label (see {@link #primaryCategoryFor})
     * then by the data type itself, so the "by delivery method" editor's flat checkbox list reads as
     * grouped by category even though the dialog API has no true section headers.
     */
    @NotNull
    static List<String> sortedDataTypes(@NotNull NotificationCategories categories,
                                        @NotNull NotificationDataTypeRegistry dataTypeRegistry) {
        List<String> dataTypes = new ArrayList<>(dataTypeRegistry.dataTypes());
        dataTypes.sort(Comparator
                .comparing((String dataType) -> categories.label(primaryCategoryFor(categories, dataType)))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(dataTypes);
    }

    /**
     * The category a data type is grouped under for display purposes when it's claimed by more than
     * one — the alphabetically-first category key it resolves to. Deterministic, not meaningful beyond
     * sorting/labeling.
     */
    @NotNull
    static String primaryCategoryFor(@NotNull NotificationCategories categories, @NotNull String dataType) {
        return new TreeSet<>(categories.resolve(dataType)).first();
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
     * A data type's row label in the "by delivery method" editor: its primary category's label,
     * prefixed for readability, followed by the raw data type key.
     */
    @NotNull
    static Component dataTypeLabel(@NotNull NotificationCategories categories, @NotNull String dataType) {
        String category = primaryCategoryFor(categories, dataType);
        return Component.text(categories.label(category) + ": " + dataType);
    }

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
                            @NotNull NotificationDataTypeRegistry dataTypeRegistry,
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
            Set<String> dataTypes = dataTypeRegistry.dataTypes();
            Map<String, Set<String>> effective = preferences.effectiveMediaByDataType(uuid, dataTypes);
            Set<String> explicitAtLoad = preferences.explicitlyConfiguredDataTypes(uuid, dataTypes);
            Set<String> fallback = preferences.preferredMedia(uuid, DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY);
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

- [ ] **Step 2: Update `PreferenceDialogRouter`**

Add a `dataTypeRegistry` field, thread it through the constructor and every `withSession` call, use it in `muteImmediately`, and rename `apply()`'s use of `session.categoriesToReset()`:

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
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

public final class PreferenceDialogRouter {

    private final Plugin plugin;
    private final NotificationSinkRegistry sinkRegistry;
    private volatile NotificationCategories categories;
    private final NotificationDataTypeRegistry dataTypeRegistry;
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
                                  @NotNull NotificationDataTypeRegistry dataTypeRegistry,
                                  @NotNull DatabaseNotificationPreferences preferences) {
        this.plugin = plugin;
        this.sinkRegistry = sinkRegistry;
        this.categories = categories;
        this.dataTypeRegistry = dataTypeRegistry;
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
    NotificationDataTypeRegistry dataTypeRegistry() {
        return this.dataTypeRegistry;
    }

    public void reloadCategories(@NotNull NotificationCategories categories) {
        this.categories = categories;
    }

    @NotNull
    public PreferenceSessionManager sessions() {
        return this.sessions;
    }

    public void openRoot(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
                player, session -> this.rootDialog.show(player, new PreferenceEditSessionHandle(session)));
    }

    public void openMediaPicker(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
                player, session -> this.mediumPickerDialog.show(player, session));
    }

    public void openCategoryPicker(@NotNull Player player) {
        PreferenceDialogs.withSession(this.plugin, this.sessions, this.dataTypeRegistry, this.preferences,
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
        Set<String> resets = session.dataTypesToReset();
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
     * Immediately mutes every known data type for the player and discards any staged, unapplied session
     * — the one deliberate asymmetry with the root screen's staged "Mute everything" button.
     */
    public void muteImmediately(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        boolean hadSession = this.sessions.get(uuid).isPresent();
        Set<String> dataTypes = this.dataTypeRegistry.dataTypes();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                this.preferences.muteAll(uuid, dataTypes);
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

- [ ] **Step 3: Fix `PlayerNotificationsPlugin`'s `registerCommands()` call site**

Confirm (from Task 8, Step 3) that `new PreferenceDialogRouter(this, this.sinkRegistry, this.categories, this.notificationService.dataTypeRegistry(), this.preferences)` matches this constructor's parameter order exactly (`plugin, sinkRegistry, categories, dataTypeRegistry, preferences`). Fix the call site now if it doesn't.

- [ ] **Step 4: Check `PreferenceDialogsTest` for direct `withSession`/renamed-helper calls**

Read `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogsTest.java`. If it only exercises `selectableMedia`, `sortedCategoryKeys`, and `inputKey` (per the earlier survey), it needs no changes. If it calls `withSession` directly, update the call to pass a `NotificationDataTypeRegistry` instead of `NotificationCategories`.

- [ ] **Step 5: Compile the platform module**

Run: `./gradlew :platform:paper-plugin:compileJava :platform:paper-plugin:compileTestJava`
Expected: FAIL — `MediumEditorDialog`, `CategoryPickerDialog`, `CategoryEditorDialog`, `PreferenceRootDialog` still reference the old category-keyed session methods and `PreferenceDialogs.withSession`'s old signature. This is expected; Tasks 11-13 fix them next.

- [ ] **Step 6: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogs.java platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogRouter.java platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java
git commit -m "refactor: thread NotificationDataTypeRegistry through PreferenceDialogRouter and session loading"
```

---

### Task 11: `MediumEditorDialog` — one checkbox per data type

**Files:**
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/MediumEditorDialog.java`

**Interfaces:**
- Consumes: `PreferenceDialogs.sortedDataTypes`/`dataTypeLabel` (Task 10), `PreferenceEditSession#mediaFor`/`#toggleDataTypeMedium` (Task 9), `PreferenceDialogRouter#dataTypeRegistry()` (Task 10).

- [ ] **Step 1: Rewrite the class**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
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
 * Editor for one medium: a checkbox per notification data type (grouped for readability by its primary
 * category), indicating whether it currently reaches the player through this medium. Save writes into
 * the session only; nothing is persisted until the root screen's Apply.
 */
final class MediumEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SAVE_LABEL = Component.text("Save");

    private final PreferenceDialogRouter router;

    MediumEditorDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String mediumKey) {
        List<String> dataTypes = PreferenceDialogs.sortedDataTypes(this.router.categories(), this.router.dataTypeRegistry());
        Map<String, String> inputKeyToDataType = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(dataTypes.size());
        for (int i = 0; i < dataTypes.size(); i++) {
            String dataType = dataTypes.get(i);
            String inputKey = PreferenceDialogs.inputKey("dataType", i);
            inputKeyToDataType.put(inputKey, dataType);
            boolean initial = session.mediaFor(dataType).contains(mediumKey);
            inputs.add(DialogInput.bool(inputKey, PreferenceDialogs.dataTypeLabel(this.router.categories(), dataType))
                    .initial(initial).build());
        }

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (Map.Entry<String, String> entry : inputKeyToDataType.entrySet()) {
                        boolean checked = Boolean.TRUE.equals(response.getBoolean(entry.getKey()));
                        session.toggleDataTypeMedium(entry.getValue(), mediumKey, checked, now);
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

- [ ] **Step 2: Compile**

Run: `./gradlew :platform:paper-plugin:compileJava`
Expected: still fails on `CategoryPickerDialog`/`CategoryEditorDialog`/`PreferenceRootDialog` (Tasks 12-13 not done yet) — confirm this file itself has no errors by checking the compiler output mentions only the other three.

- [ ] **Step 3: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/MediumEditorDialog.java
git commit -m "refactor: MediumEditorDialog lists one checkbox per dataType"
```

---

### Task 12: `CategoryPickerDialog` and `CategoryEditorDialog` — fan out to member data types, show mixed state

**Files:**
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryPickerDialog.java`
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryEditorDialog.java`

**Interfaces:**
- Consumes: `NotificationCategories#dataTypesForCategory` (Task 7), `PreferenceEditSession#mediaFor`/`#toggleDataTypeMedium`/`#resetDataType`/`#isUsingServerDefault` (Task 9), `PreferenceDialogRouter#dataTypeRegistry()` (Task 10).

- [ ] **Step 1: Rewrite `CategoryPickerDialog`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
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
import java.util.Set;

/**
 * "By notification type" picker: choose one category to configure which media it reaches the player
 * through. A category label is suffixed "(server default)" when every data type it claims is currently
 * showing the server default.
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
        Set<String> allDataTypes = this.router.dataTypeRegistry().dataTypes();
        List<ActionButton> buttons = new ArrayList<>();
        for (String category : categoryKeys) {
            Set<String> members = this.router.categories().dataTypesForCategory(category, allDataTypes);
            boolean usingDefault = members.isEmpty() || members.stream().allMatch(session::isUsingServerDefault);
            Component label = PreferenceDialogs.categoryLabel(this.router.categories(), category);
            if (usingDefault) {
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

- [ ] **Step 2: Rewrite `CategoryEditorDialog`**

```java
package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Editor for one notification category: a checkbox per registered medium, plus "use server default" to
 * stage clearing every data type this category claims. Checking or unchecking a medium fans out to a
 * per-data-type write on Save; if the category's members currently disagree on a medium, that
 * checkbox's label shows "(mixed)" until this editor overwrites them uniformly. Save writes into the
 * session only; nothing is persisted until the root screen's Apply.
 */
final class CategoryEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SAVE_LABEL = Component.text("Save");
    private static final Component USE_DEFAULT_LABEL = Component.text("Use server default");
    private static final Component MIXED_SUFFIX = Component.text(" (mixed)", NamedTextColor.GRAY);

    private final PreferenceDialogRouter router;

    CategoryEditorDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String categoryKey) {
        Set<String> memberDataTypes = this.router.categories()
                .dataTypesForCategory(categoryKey, this.router.dataTypeRegistry().dataTypes());
        List<String> media = PreferenceDialogs.selectableMedia(this.router.sinkRegistry());
        Map<String, String> inputKeyToMedium = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(media.size());
        for (int i = 0; i < media.size(); i++) {
            String medium = media.get(i);
            String inputKey = PreferenceDialogs.inputKey("medium", i);
            inputKeyToMedium.put(inputKey, medium);
            MixedState state = mixedStateFor(session, memberDataTypes, medium);
            Component label = PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), medium);
            if (state == MixedState.MIXED) {
                label = label.append(MIXED_SUFFIX);
            }
            inputs.add(DialogInput.bool(inputKey, label).initial(state == MixedState.ALL_CHECKED).build());
        }

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (Map.Entry<String, String> entry : inputKeyToMedium.entrySet()) {
                        boolean checked = Boolean.TRUE.equals(response.getBoolean(entry.getKey()));
                        for (String dataType : memberDataTypes) {
                            session.toggleDataTypeMedium(dataType, entry.getValue(), checked, now);
                        }
                    }
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton useDefault = ActionButton.builder(USE_DEFAULT_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String dataType : memberDataTypes) {
                        session.resetDataType(dataType, now);
                    }
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

    private enum MixedState { ALL_CHECKED, ALL_UNCHECKED, MIXED }

    @NotNull
    private static MixedState mixedStateFor(@NotNull PreferenceEditSession session,
                                            @NotNull Set<String> memberDataTypes, @NotNull String medium) {
        boolean anyChecked = false;
        boolean anyUnchecked = false;
        for (String dataType : memberDataTypes) {
            if (session.mediaFor(dataType).contains(medium)) {
                anyChecked = true;
            } else {
                anyUnchecked = true;
            }
        }
        if (anyChecked && anyUnchecked) {
            return MixedState.MIXED;
        }
        return anyChecked ? MixedState.ALL_CHECKED : MixedState.ALL_UNCHECKED;
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :platform:paper-plugin:compileJava`
Expected: still fails only on `PreferenceRootDialog` (Task 13 not done yet).

- [ ] **Step 4: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryPickerDialog.java platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/CategoryEditorDialog.java
git commit -m "refactor: category picker/editor fan out to member dataTypes with mixed-state display"
```

---

### Task 13: `PreferenceRootDialog` — bulk mute/reset iterate every known data type

**Files:**
- Modify: `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceRootDialog.java`

**Interfaces:**
- Consumes: `PreferenceDialogRouter#dataTypeRegistry()` (Task 10), `PreferenceEditSession#setDataTypeMedia`/`#resetDataType` (Task 9).

- [ ] **Step 1: Rewrite the "Mute everything" and "Reset all" button bodies**

In `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceRootDialog.java`, replace:

```java
        buttons.add(ActionButton.builder(MUTE_ALL_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String dataType : this.router.dataTypeRegistry().dataTypes()) {
                        session.setDataTypeMedia(dataType, Set.of(), now);
                    }
                    show(player, handle);
                }, PreferenceDialogs.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(RESET_ALL_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String dataType : this.router.dataTypeRegistry().dataTypes()) {
                        session.resetDataType(dataType, now);
                    }
                    show(player, handle);
                }, PreferenceDialogs.callbackOptions()))
                .build());
```

(No other part of this file changes — the pivot buttons, Apply/Discard staging, and imports stay the same.)

- [ ] **Step 2: Compile the whole platform module**

Run: `./gradlew :platform:paper-plugin:compileJava :platform:paper-plugin:compileTestJava`
Expected: PASS — this is the last dialog file with category-keyed session calls.

- [ ] **Step 3: Commit**

```bash
git add platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceRootDialog.java
git commit -m "refactor: root dialog's bulk mute/reset iterate every known dataType"
```

---

### Task 14: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Build everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. If Docker isn't running, `:core:test` will fail outright (not skip) per this project's known testing gotcha — start Docker and re-run if so.

- [ ] **Step 2: Run each module's test suite explicitly and check result counts, not just exit status**

Run: `./gradlew :api:test :core:test :platform:paper-plugin:test`

Then count actual result files per this project's documented gotcha (glob `*.xml`, not `TEST-*.xml`, since `@Nested` classes produce shortened `__TEST-<hash>` names on Windows):

```bash
find api/build/test-results/test core/build/test-results/test platform/paper-plugin/build/test-results/test -name "*.xml" | wc -l
```

Compare against a sensible expectation: the project's documented baseline before this change was 63 (`:core:test`) + 13 (`:api:test`) + 17 (`:platform:paper-plugin:test`) = 93 test result files; this plan added roughly 2 (`NotificationDataTypeRegistryTest` additions) + 8 (`DefaultNotificationCategoryRegistryTest`) + 1 (`DefaultNotificationServiceTest` addition) + a handful of renamed-not-added tests elsewhere + a few net-new `NotificationCategoriesTest`/`RenderingProcessorTest` cases — expect noticeably more than 93, not fewer. If the count looks suspiciously low, re-check for a `--tests` filter left over from an earlier step matching nothing (a documented footgun: `BUILD SUCCESSFUL` while matching zero tests).

- [ ] **Step 3: Manually verify the preference dialogs**

Per this project's standing convention, the five dialog classes have no automated coverage. Run `./gradlew :platform:paper-plugin:runServer`, connect, and walk through:
- `/notifications` → "By delivery method" → pick a medium → confirm one checkbox per data type appears, grouped/labeled by category, and Save persists correctly after Apply.
- `/notifications` → "By notification type" → pick a category with more than one member data type → toggle a medium → confirm it fans out to all members (verify via the "By delivery method" view afterward) → try "Use server default" → confirm the picker shows "(server default)" again.
- Stage a mixed state by setting two data types in the same category to different media via the medium editor, then open that category's editor and confirm a "(mixed)" label appears for the divergent medium.
- `/notifications mute` and `/notifications reset` — confirm they still work immediately without needing Apply.
- `/notifications reload` — confirm it doesn't throw and preferences dialogs still open correctly afterward.

- [ ] **Step 4: Final commit if verification uncovered fixes**

If Step 3 surfaces a bug, fix it, re-run the relevant automated tests, and commit with a message describing the specific fix (not a generic "fix bugs").
