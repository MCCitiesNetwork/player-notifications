# Configurable notification type names Implementation Plan

**Goal:** A module can supply a default display name for the `dataType`s it registers; the operator overrides any of them in `type-names.yml` and sees what modules supplied in a generated `defaults/type-names.yml`.
**Spec:** `docs/superpowers/specs/2026-08-23-configurable-type-names-design.md`

Tasks 2, 3 and 4 are independent of each other and may run in parallel once Task 1 is in.

## Task 1: display names on `NotificationDataTypeRegistry`

**Files:** modify `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationDataTypeRegistry.java`; modify or create its test in `api/src/test/java/.../api/`

**Interfaces produced:**

```java
public void registerDisplayName(@NotNull String dataType, @NotNull String miniMessage);
public void unregisterDisplayName(@NotNull String dataType);
public @NotNull Optional<String> displayName(@NotNull String dataType);
```

- [ ] Write the failing tests: a registered display name comes back; an unregistered `dataType` is `Optional.empty()`; `unregisterDisplayName` removes it; re-registering the same key overwrites.
- [ ] Run `./gradlew :api:test --tests "*NotificationDataTypeRegistry*"` — expect FAIL: methods do not exist.
- [ ] Implement: one `Collections.synchronizedMap(new HashMap<>())` beside the existing `payloadMapping`, matching that field's concurrency approach exactly. Javadoc records that the value is raw MiniMessage, that the host may override it from `type-names.yml`, and that registering one is optional — a type with none is title-cased.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: `GeneratedYaml`, extracted from `CategoryDefaultsWriter`

**Files:** create `core/src/main/java/io/github/md5sha256/playernotifications/core/config/GeneratedYaml.java`; modify `core/.../core/category/CategoryDefaultsWriter.java`

**Interfaces produced:**

```java
public static <T> void write(@NotNull Path file, @NotNull String header, @NotNull String rootKey,
                             @NotNull TypeToken<T> type, @NotNull T content, @NotNull Logger logger);
```

**No new test.** This is a behaviour-preserving extraction; `CategoryDefaultsWriterTest`'s 7 tests are
the safety net and must pass **unchanged** — do not edit that file.

- [ ] Run `./gradlew :core:test --tests "*CategoryDefaultsWriterTest"` first and record 7 passing, so the
      before-state is measured rather than assumed.
- [ ] Move the render-to-string, header-prepend, `Files.writeString` and `catch (IOException)` body out
      of `CategoryDefaultsWriter.write` into `GeneratedYaml.write`, carrying the javadoc that justifies
      each: never throws, header is plain text not a Configurate comment, the write is not atomic.
- [ ] `CategoryDefaultsWriter.write` becomes a call to it with its existing `HEADER`, the root key
      `"categories"` and `new TypeToken<Map<String, NotificationCategoryDefinition>>() {}`. Its public
      shape, `FILE_NAME` and `snapshot` are unchanged.
- [ ] Run the same command — expect the same 7 passing, unchanged.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: `TypeNames`

**Files:** create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/localisation/TypeNames.java` and its test; modify `platform/paper-plugin/src/main/java/.../paper/preferences/PreferenceDialogs.java` (remove its private `titleCase`, call `TypeNames.titleCase`)

**Interfaces produced:**

```java
public TypeNames(@NotNull NotificationDataTypeRegistry registry, @NotNull Logger logger);
public void load(@NotNull ConfigurationNode root);
public @NotNull Component name(@NotNull String dataType);
public @NotNull String plainName(@NotNull String dataType);
public @NotNull Optional<String> override(@NotNull String dataType);
public static @NotNull String titleCase(@NotNull String key);
```

- [ ] Write the failing tests, one per spec bullet:
  - `unconfiguredTypeIsTitleCased` — `essentials_mail` → `Essentials Mail`
  - `moduleDefaultIsUsedWhenNoOverride`
  - `operatorOverrideBeatsModuleDefault`
  - `miniMessageFormattingSurvivesInName` and `plainNameFlattensIt`
  - `malformedOverrideFallsThroughToModuleDefault` — value `"§cBroken"`
  - `malformedOverrideLogsOneWarningAndIsDropped` — attach a capturing `java.util.logging.Handler`,
    assert exactly one record naming the key, then call `name` twice more and assert no further records
  - `malformedModuleDefaultWarnsOncePerDataType` — three `name` calls, one record
  - `loadClearsTheWarnedOnceSet` — warn, `load` again, warn again: two records
  - `blankValueFallsThroughSilently` — at both layers, zero records
  - `loadReplacesThePreviousMap` — a key absent from the new node stops overriding
  - `nonScalarNodeValueIsSkipped` — a map-valued key does not throw
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*TypeNamesTest"` — expect FAIL: class does not exist.
- [ ] Implement per the spec. `ConcurrentHashMap` for the overrides and
      `ConcurrentHashMap.newKeySet()` for the warned-once module keys, cleared by `load`. `load` reads
      `root.childrenMap()`, skips non-scalar and blank values, parse-checks each with
      `MiniMessage.miniMessage().deserialize(...)` inside a `try`, and on `ParsingException` logs
      `WARNING` naming the key and the message and does not store it.
- [ ] Run the same command — expect PASS. Then `./gradlew :platform:paper-plugin:test` — expect the
      module suite green at 165 + the number of tests added.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 4: the Discord type label seam

**Files:** modify `platform/discord-adapter/src/main/java/.../discord/command/PreferenceView.java` and its test

- [ ] Write the failing test in `PreferenceViewTest`: constructing the view with a label function
      `dt -> "Custom " + dt` makes `open(...).dataTypes()` carry `Custom mail` as that choice's label.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*PreferenceViewTest"` — expect FAIL: the
      constructor has no such parameter. **Needs Docker** (the module's suite is not hermetic as a
      whole, though this class's tests are); if the daemon is down, say so rather than skipping.
- [ ] Implement: add `Function<String, String> typeLabel` as the constructor's last parameter, delete
      the private static `label`, and call the function in `state(...)`. Update the other construction
      sites in the test file to pass `PreferenceView`-local title-casing so their expectations hold.
- [ ] Run the same command — expect PASS, then the module suite at 213 + added.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 5: `TypeNameDefaultsWriter` and the bundled `type-names.yml`

**Files:** create `platform/paper-plugin/src/main/java/.../paper/localisation/TypeNameDefaultsWriter.java` and its test; create `platform/paper-plugin/src/main/resources/type-names.yml`

- [ ] Write the failing tests: every registered `dataType` appears even with no module default; entries
      sorted; a module-supplied entry is distinguishable from a title-cased fallback in the emitted
      text; two writes byte-identical; an unwritable path logs and does not throw.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*TypeNameDefaultsWriterTest"` — expect FAIL.
- [ ] Implement over `GeneratedYaml.write` with root key `types`, a `TreeMap<String, String>` of
      effective default per registered `dataType`, and a header explaining the file is generated, never
      read, and that `type-names.yml` is where an override goes. Mark provenance with a `# from module`
      / `# title-cased fallback` trailing marker emitted in the header's legend plus a per-key comment
      line — if Configurate cannot emit per-entry comments, split the map into two commented sections
      instead and record that in the class javadoc.
- [ ] Create `type-names.yml` with a header comment and **every example commented out**, per the spec.
- [ ] Run the same command — expect PASS, then `./gradlew :platform:paper-plugin:test`.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 6: wire it into the plugin and both surfaces

**Files:** modify `PlayerNotificationsPlugin.java`, `preferences/PreferenceDialogRouter.java`,
`preferences/PreferenceDialogs.java`, `preferences/MediumEditorDialog.java`,
`platform/discord-adapter/src/main/java/.../discord/DiscordModule.java`

**No test.** Every line needs a live server. Manual checklist is Task 7.

- [ ] `PlayerNotificationsPlugin`: `private final TypeNames typeNames` constructed after the service
      exists (it needs the registry), `typeNames.load(copyDefaultsYaml("type-names"))` beside
      `reloadMessages()` in `onEnable` and again in `reload`, a `TypeNameDefaultsWriter` field written
      everywhere `categoryDefaultsWriter.write` is already called, and a public `typeNames()` accessor.
- [ ] Do **not** add `type-names` to `warnAboutMissingConfigKeys`'s list.
- [ ] `PreferenceDialogRouter`: hold `TypeNames`, expose `typeNames()`.
- [ ] `PreferenceDialogs.dataTypeLabel`: take `TypeNames`, return
      `Component.text(categories.label(category) + ": ").append(typeNames.name(dataType))`.
- [ ] `MediumEditorDialog`: pass `this.router.typeNames()`.
- [ ] `DiscordModule`: pass `plugin.typeNames()::plainName` as `PreferenceView`'s label function.
- [ ] Run `./gradlew build` and the full `./gradlew test`
- [ ] Commit

## Task 7: manual verification and `CLAUDE.md`

Live-server checklist — `./gradlew :platform:paper-plugin:runServer`, needs a reachable MariaDB:

- [ ] Start clean. Confirm `type-names.yml` is created with everything commented, and
      `defaults/type-names.yml` lists `mail`, `broadcast` and `test` with title-cased fallbacks.
- [ ] `/notifications preferences media` → pick Chat. Confirm rows read `Mail: Mail`,
      `Broadcasts: Broadcast`, `Diagnostics: Test`.
- [ ] Add `mail: "<gold>Personal Mail</gold>"` to `type-names.yml`, `/notifications reload`, reopen:
      the row reads `Mail: Personal Mail` in gold, and the category prefix is still there.
- [ ] Confirm `type-names.yml` was **not** rewritten by the reload — comments and order intact.
- [ ] Add `broadcast: "<red>Broken"` with a stray `§` (`"§cBroken"`), reload, and confirm **one**
      `WARNING` naming `broadcast`, that the row falls back to `Broadcasts: Broadcast`, and that
      reopening the screen several times prints no further warnings.
- [ ] With the Discord adapter loaded and an account linked, `/notifications prefs` in Discord: the
      type select shows `Personal Mail` as plain text, no colour, no category prefix.
- [ ] Confirm no gap warning mentions `type-names.yml`.

- [ ] Update `CLAUDE.md`: "Configuration" (the new file and the generated dump, and that `type-names`
      is deliberately absent from `warnAboutMissingConfigKeys`), "Player commands" (what the medium
      editor row is now composed of), "Discord slash commands" (the label seam), "Messages" (why type
      names are not keys in `messages.yml`), and the test-count baseline from a fresh `./gradlew test`.
- [ ] Run `./gradlew build`
- [ ] Commit
