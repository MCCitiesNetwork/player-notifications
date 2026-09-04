# Per-type Delivery Defaults Implementation Plan

**Goal:** `delivery-defaults.yml` overrides `default-media` per `dataType`, below every player row.
**Spec:** `docs/superpowers/specs/2026-09-04-per-type-delivery-defaults-design.md`

## Task 1: `DeliveryDefaults` — parsing the file

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/config/DeliveryDefaults.java`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/config/DeliveryDefaultsTest.java`

**Interfaces:**

```java
public final class DeliveryDefaults {
    public static Map<String, Set<String>> load(@NotNull ConfigurationNode root, @NotNull Logger logger);
}
```

- [ ] Write the failing test `DeliveryDefaultsTest`, building nodes with
      `YamlConfigurationLoader.builder().buildAndLoadString(...)` as `ConfigKeyGapsTest` does, and a
      `Logger` with a `Handler` collecting records so the warnings are asserted, not just the map:
  - `restart-warning:\n  - chat\n  - discord-dm\n` → `{restart-warning=[chat, discord-dm]}`
  - a duplicate entry (`- chat\n  - chat`) → a one-element set
  - `maintenance:\n  - none\n` → `{maintenance=[none]}` (accepted, not special-cased)
  - `'*':\n  - chat\n` → empty map, one warning naming `*`
  - `broadcast: chat` (a scalar, not a list) → empty map, one warning naming `broadcast`
  - `broadcast:\n  nested: chat` (a map) → empty map, one warning naming `broadcast`
  - `broadcast: []` → empty map, one warning naming `broadcast`
  - `broadcast:\n  - ''\n  - chat\n` → `{broadcast=[chat]}`, no warning
  - `broadcast:\n  - ''\n` → empty map, one warning naming `broadcast`
  - one bad entry alongside a good one → the good one survives
  - an empty document → an empty map, no warning
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*DeliveryDefaultsTest"` — expect FAIL: the
      class does not exist
- [ ] Implement `DeliveryDefaults.load`: walk `root.childrenMap()`, apply the table above, return
      `Map.copyOf` of what survived. Warnings go through `logger.log(Level.WARNING, ...)` naming the key
      and the reason, the wording shape `CustomNotificationTypes.reject` uses. The `*` rejection names
      `DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY` in its javadoc as the reason.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: The new resolution step in `core`

**Files:**
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/DatabaseNotificationPreferences.java`
- modify `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java`

**Interfaces:**

```java
public void reloadTypeDefaults(@NotNull Map<String, ? extends Collection<String>> typeDefaults);
```

The constructor is **unchanged** — the map starts empty and is pushed in by the caller, so every existing
construction site (the plugin and the `core` tests) keeps compiling and a caller that never sets it
behaves exactly as today.

Needs a running Docker daemon (`:core:test` uses Testcontainers).

- [ ] Write the failing tests in `PlayerNotificationPreferenceTest`, in a new `@Nested` class
      `TypeDefaults`, each constructing the preferences and calling
      `reloadTypeDefaults(Map.of("restart-warning", List.of("discord-dm")))`:
  - no player rows → `preferredMedia(PLAYER_A, "restart-warning")` is `{discord-dm}`
  - no player rows, a type the map does not name → the global default (`{chat}`)
  - an exact row for `restart-warning` → that row wins over the override
  - a `*` row and no exact row → the `*` row wins over the override
  - `reloadTypeDefaults(Map.of("maintenance", List.of("none")))` → `{none}`
  - `reloadTypeDefaults(Map.of())` after a non-empty one → back to the global default
  - `preferredMedia(PLAYER_A)` (single-argument) with `Map.of("*", List.of("discord-dm"))` staged →
    still the global default, never the override
  - `effectiveMediaByDataType(PLAYER_A, Set.of("restart-warning", "other"))` → `{discord-dm}` and the
    global default respectively, agreeing with `preferredMedia` for both
- [ ] Run `./gradlew :core:test --tests "*PlayerNotificationPreferenceTest"` — expect FAIL: no such
      method `reloadTypeDefaults`
- [ ] Implement: a `private volatile Map<String, Set<String>> typeDefaults = Map.of();`, the
      `reloadTypeDefaults` setter copying defensively (`Set.copyOf` per value), a private
      `@Nullable Set<String> typeDefault(String dataType)` returning null for
      `ALL_DATA_TYPES_KEY`, and the call inserted in both `preferredMedia(UUID, String)` — after the
      `ALL_DATA_TYPES_KEY` row lookup, before `return this.defaultMedia` — and
      `effectiveMediaByDataType`, where the per-type value is consulted before the `fallback` local.
      Update the class javadoc's chain description to the four steps.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: The file, its wiring, and reload

**Files:**
- create `platform/paper-plugin/src/main/resources/delivery-defaults.yml`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java` (enable path near `:264`, reload path near `:490`, and `warnAboutMissingConfigKeys` near `:804`)

No unit test: this is `onEnable` wiring and Configurate file copying, which needs a live server — the
exception the `implement` skill names. Task 4 verifies it.

- [ ] Create `delivery-defaults.yml`, every example commented out so a stock install changes nothing:

```yaml
# Where each notification type goes for a player who has not chosen for themselves.
#
# This file OVERRIDES settings.yml's default-media, per notification type. It never overrides a
# player's own choice: a type they have configured in /notifications preferences keeps their setting.
# A type not named here falls back to default-media, so this file is always optional.
#
# The key is a notification type (dataType) - the same keys defaults/type-names.yml lists.
# The value is a list of media: chat, dialog, discord-dm (with the Discord adapter installed), or the
# single entry 'none' to make a type opt-in: stored and readable in /notifications, never pushed until
# a player chooses media for it.
#
#restart-warning:
#  - chat
#  - discord-dm
#mail:
#  - discord-dm
#maintenance:
#  - none
```

- [ ] Wire the enable path: alongside `this.typeNames.load(copyDefaultsYaml("type-names"));` add
      `this.preferences.reloadTypeDefaults(DeliveryDefaults.load(copyDefaultsYaml("delivery-defaults"), getLogger()));`
- [ ] Wire the reload path: the same line alongside the `type-names` load in `reload()`
- [ ] Leave `delivery-defaults` **out** of `warnAboutMissingConfigKeys`' file list, for the reason
      `type-names` and `notification-types` are out: a partial override map has no missing keys. Add it
      to that method's javadoc list of deliberate exclusions.
- [ ] Run `./gradlew build` — expect PASS
- [ ] Commit

## Task 4: Manual verification on a live server

**Files:** none — `./gradlew :platform:paper-plugin:runServer`

- [ ] First start writes `run/plugins/PlayerNotifications/delivery-defaults.yml` with every line
      commented, and the plugin enables clean
- [ ] With `broadcast:\n  - dialog` set and a player who has never configured `broadcast`:
      `/broadcast hello` arrives as a dialog, not chat
- [ ] That same player opens `/notifications preferences` → the `broadcast` row shows **Dialog** ticked,
      matching what was actually delivered
- [ ] The player ticks Chat for `broadcast` and applies; `/broadcast hello` now arrives in chat — the
      player's row beats the file
- [ ] Edit the file to `broadcast:\n  - chat`, `/notifications reload`, send again → still chat for that
      player (their row still wins), and a *different* untouched player now gets chat
- [ ] Delete the `broadcast` entry, `/notifications reload` → an untouched player falls back to
      `settings.yml`'s `default-media`
- [ ] Set `test:\n  - none`, then `/notifications test` as an untouched player → the reply reports
      nothing was sent, and the notification is in `/notifications`, unread
- [ ] Set a key `'*'` → one console warning naming `*` on load, and no behaviour change
- [ ] Set `broadcast: chat` (scalar) → one console warning naming `broadcast`, entry ignored
- [ ] Set `broadcast: []` → one console warning naming `broadcast`, entry ignored
- [ ] Set `broadcast:\n  - nosuchmedium` → no load warning, and delivery logs a `fine` skip, matching a
      mistyped `default-media`
- [ ] Delete the file entirely and restart → it is recreated, and nothing else changes
- [ ] Commit any fixes the checklist turns up

## Task 5: Update `CLAUDE.md`

**Files:** modify `CLAUDE.md`

- [ ] "Notification categories": the `DatabaseNotificationPreferences` precedence sentence gains the new
      step, and the three-state preference table's "Unconfigured" row changes to `*` rows, else the file,
      else `default-media`
- [ ] "Configuration": add `delivery-defaults.yml` to the file list, with its `childrenMap()` shape and
      its exclusion from `warnAboutMissingConfigKeys`, next to `type-names.yml`
- [ ] Add a short "Per-type delivery defaults" subsection under "Rendering & delivery media" recording
      why the global default was kept, why the override sits below the `*` rows, and that `[none]` makes
      a type opt-in
- [ ] Refresh the test-count baseline from a fresh `./gradlew build`, and add Task 4's checklist to
      "Current state" as not yet run
- [ ] Run `./gradlew build`
- [ ] Commit
