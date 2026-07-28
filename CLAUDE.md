# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

PlayerNotifications is a PaperMC (Spigot) plugin for Minecraft **1.21.8**, targeting **Java 21**. It stores per-player notifications in a MariaDB database and delivers them to players via pluggable, payload-typed processors — or, more commonly, via the **renderer/sink** path, which fans a notification out to whichever media a player prefers (chat, dialog, Essentials mail, planned Discord). Persistence is implemented with MyBatis in the `core` module; the Paper bootstrap and platform integrations live under `platform/`. Cross-cutting infrastructure (a runtime module system, schema migrator, Configurate helpers) comes from the external `plugin-infrastructure` library.

## Build & run

Uses the Gradle wrapper (Gradle 9.3.0). On Windows, use `./gradlew` from the Bash tool or `gradlew.bat` from PowerShell.

- `./gradlew build` — build all modules. The `platform:paper-plugin` module's `build` depends on `shadowJar`, producing the shaded plugin jar under `platform/paper-plugin/build/libs/`.
- `./gradlew :platform:paper-plugin:shadowJar` — build only the distributable plugin jar.
- `./gradlew :platform:essentials-adapter:jar` — build the Essentials mail adapter module jar.
- `./gradlew test` — run all tests (JUnit 5 / Jupiter).
- `./gradlew :core:test` — run the `core` persistence tests. **These require a running Docker daemon** — they spin up a real `mariadb:11.7` container via Testcontainers.
- `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.*SomeTest"` — run a single test class/method.
- `./gradlew :platform:paper-plugin:runServer` — launch a real Paper 1.21.8 test server with the plugin loaded (via the `xyz.jpenilla.run-paper` plugin). Server files go under `platform/paper-plugin/run/`. Needs a reachable MariaDB (see `database.yml`).

## Module architecture

Gradle build with `api`, `core`, and two platform modules (`settings.gradle.kts` includes `api`, `core`, `platform:paper-plugin`, `platform:essentials-adapter`). Type-safe project accessors are enabled (`enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`), so build scripts reference `projects.api`, `projects.core`, etc. Dependency direction flows **platform → core → api**; `api` depends on nothing but Paper.

- **`api`** (`io.github.md5sha256.playernotifications.api`) — Public, dependency-light API. Uses `compileOnlyApi` for `paper-api`. Key types:
  - `NotificationService` — entry point: `enqueueNotification`, `resolveNotifications(UUID)` (a pure read — does **not** delete), `clearNotification`/`clearNotifications`, `deleteNotificationTarget(key, UUID)` / `deleteNotificationTargets(key, Collection<UUID>)`, `clearExpiredNotifications`, and `dataTypeRegistry()`.
  - `NotificationDataTypeRegistry` — maps a string `dataType` → payload `Class<?>`, and payload class → `NotificationProcessor`, `PayloadSerializer`, and `NotificationRenderer`. The extension point: callers register their own payload types, processors/renderers, and serializers.
  - `NotificationSinkRegistry` — separate registry keyed by **medium** (`"chat"`, `"dialog"`, `"essentials-mail"`, `"discord"`), not by data type. `registerSink` keys off `NotificationSink#mediumKey()`.
  - `Notification` / `ResolvedNotification` — records for the persisted vs. target-resolved forms. `ResolvedNotification` holds a `NotificationTarget` (list of player UUIDs) and carries `notifPayloadType` (the registry data-type string) plus the `String` payload.
  - **`api.processor`** package — `NotificationProcessor<T>` is a pure `@FunctionalInterface`: `NotificationDisposition receiveNotification(T payload, UUID target)` — it processes **one target (audience member) per call** and returns whether the notification should be `RETAIN`ed or flagged for `DELETE`. Composition lives in `NotificationProcessorBuilder` (fluent "chop-down" chaining via `andThen`/`andThenIf`/`onComplete`, folding dispositions with DELETE-wins). `FixedDelayProcessor` wraps a processor with a scheduled delay.
  - **`api.render`** package — the renderer/sink architecture (see "Rendering & delivery media" below): `RenderableNotification` (medium-neutral `Component` title + body), `NotificationRenderer<T>` (payload → `RenderableNotification`, one per payload type), `NotificationSink` (`RenderableNotification` → a medium, one per medium, returning a `DeliveryResult`), `DeliveryResult` (`DELIVERED` / `UNREACHABLE` / `UNSUPPORTED`), `NotificationPreferences` (`UUID` → `Set<String>` of preferred media, plus a `default` two-argument `preferredMedia(UUID, String category)` overload — see "Notification categories"), and `RenderingProcessor<T>` — the single framework-supplied processor that binds them. `NotificationSink` also carries `default` `displayName()` / `description()` `Component`s used to label media in player-facing UI (`displayName()` title-cases `mediumKey()`, so `essentials-mail` → "Essentials Mail"). `api.render.sink` holds `ChatSink`, `DialogSink`, and `NullSink` (the `"none"` medium backing an explicit mute — see "Player commands").
  - **`api.serialize`** package — `PayloadSerializer<T>` (JSON string ↔ `T`) and `PayloadSerializationException`. The only serialization type crossing the API boundary; the JSON library stays an implementation detail of whoever supplies the serializer.
- **`core`** (`io.github.md5sha256.playernotifications.core`) — MyBatis persistence, `DefaultNotificationService`, `NotificationDelivery` (the delivery loop), `DatabaseNotificationPreferences` (the persisted, category-aware `NotificationPreferences` impl), `category.NotificationCategories` (see "Notification categories"), and `serialize.JacksonPayloadSerializer`. `api("org.mybatis:mybatis")`, `api("org.spongepowered:configurate-yaml")`, `implementation("org.mariadb.jdbc:mariadb-java-client")`, `paper-api` compileOnly **plus `testRuntimeOnly`** (see "Testing gotchas"). See "Persistence layer" below.
- **`platform:paper-plugin`** (`io.github.md5sha256.playernotifications.paper`) — Paper bootstrap. `PlayerNotificationsPlugin.onEnable` loads config (including `categories.yml`), builds a `MariaDatabase`, runs schema migration, constructs `DefaultNotificationService`, registers it under `NotificationService.class` in the Bukkit `ServicesManager`, builds the `NotificationSinkRegistry` (registering `ChatSink`, `DialogSink`, and `NullSink`), `DatabaseNotificationPreferences`, and `NotificationCategories`, constructs `NotificationDelivery` with all three, registers the Brigadier commands and a `PreferenceQuitListener`, schedules the async prune task, starts the module system, and finally warns about any `categories.yml` data type with no registered payload mapping. Exposes `database()` / `notificationService()` / `sinkRegistry()` / `preferences()` / `categories()` / `notificationDelivery()` accessors for modules. Applies `shadow` (relocating `org.mariadb`, `org.mybatis`, `org.apache.ibatis`, `org.spongepowered`, `io.leangen.geantyref`, `com.fasterxml.jackson`) and `run-paper`. Also declares `testRuntimeOnly("io.papermc.paper:paper-api")` (see "Testing gotchas") — needed once its own tests started touching Adventure/Bukkit types.
- **`platform:essentials-adapter`** (`io.github.md5sha256.playernotifications.essentials`) — a **feature module** (see "Module system") that renders notifications as Essentials mail. `EssentialsMailModule` (the manifest entry class) registers an `EssentialsMailProcessor` for the `essentials-mail` data type. Applies the `paper-adapter` convention; declares only the EssentialsX API (compile-only).

### Build conventions

`buildSrc/src/main/kotlin/` holds precompiled convention plugins:
- `player-notifications-conventions` — Java 21 toolchain, UTF-8, JUnit 5, and the Paper / mavenLocal / mavenCentral repos. Applied by every module.
- `paper-adapter` — for feature-module projects. Applies the base conventions, adds `compileOnly(project(":platform:paper-plugin"))` (so adapters compile against the host but never bundle it — the module class loader resolves host classes at runtime), and adds the `maven.democracycraft.net/snapshots` repo. Because `paper-plugin` exposes `api`/`compileOnlyApi` dependencies, this single dependency transitively provides `api`, `core`, `plugin-infrastructure`, and `paper-api` to adapters.

Note: precompiled conventions apply sibling conventions with `id("player-notifications-conventions")`, not the backtick accessor. Project version comes from the root `gradle.properties`.

### plugin-infrastructure dependency

`net.democracrycraft:plugin-infrastructure` (from `https://maven.democracycraft.net/snapshots`, see its README) provides `net.democracrycraft.pluginInfrastructure.modules` (the module system), `.sql` (schema migration primitives), `.configurate`, and `.util`. Consumed as a normal repo dependency — **not** an `includeBuild`.

## Module system

Feature modules are jars dropped into `<dataFolder>/modules/`, each containing a `module-manifest.yml` and an entry class implementing `PluginModule<T extends Plugin>` (or extending `SimplePluginModule`). `PlayerNotificationsPlugin` drives them with a `ModuleLifecycleManager` (`start()` on enable after the service is registered, `stop()` first on disable). A module's `initialize` receives the host plugin and typically resolves `NotificationService` from the `ServicesManager` to register processors.

The manifest keys are the `ModuleManifest` record's camelCase field names (Configurate uses field names verbatim):

```yaml
moduleName: essentials-mail-adapter
entryClass: io.github.md5sha256.playernotifications.essentials.EssentialsMailModule
author: md5sha256
expectedPluginClass: io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin
reloadable: false
```

`expectedPluginClass` must equal the host plugin's runtime FQCN, or the module is skipped.

## Rendering & delivery media

Design doc: `docs/superpowers/specs/2026-07-27-notification-renderers-design.md`.

Payload *rendering* and medium *delivery* are separate axes, so a payload is converted **once** and can
fan out to any number of media. Registrations are **N + M**, not N × M: adding a medium (e.g. the
planned Discord sink) requires no change to any existing payload.

- A payload author registers a `NotificationRenderer<T>` (payload → `RenderableNotification`) and never
  writes per-medium or preference-lookup logic.
- A medium owner registers one `NotificationSink` in the `NotificationSinkRegistry`.
- `RenderingProcessor<T>` — the **single** framework-supplied processor — resolves preferred media
  (via `NotificationPreferences#preferredMedia(target, category)` when a category is known, otherwise
  the category-agnostic `preferredMedia(target)`), renders once, and delivers to each preferred medium's
  sink. A medium with no registered sink is logged at `fine` and skipped.

**Dispatch precedence in `NotificationDelivery`:** an explicitly registered `NotificationProcessor`
always wins (so `EssentialsMailProcessor` and other bespoke processors keep working unchanged —
including bypassing categories); otherwise a registered `NotificationRenderer` dispatches through
`RenderingProcessor`, with the notification's `notifPayloadType` resolved to a category via
`NotificationCategories` when the delivery loop was built with one; otherwise the notification is logged
and retained. `NotificationDelivery` has a 3-arg constructor (no rendering path), a 5-arg one (rendering,
no category resolution), and a 6-arg one (rendering with category resolution) — all three delegate down
to the same fields, so existing callers of the 3-/5-arg forms are unaffected by categories.

**Fan-out is DELETE-wins:** if any sink returns `DELIVERED`, the notification is consumed. Consequences,
deliberate and documented in the design doc's "Known limitations":
- **Partial delivery is silent and unrecoverable.** If a player prefers `chat + discord`, chat succeeds
  and Discord transiently fails, the notification is consumed and Discord never receives it. Fixing this
  needs per-medium delivery tracking, which was deliberately deferred. Retaining instead is *not* a
  workaround — chat is not idempotent, so the player would be messaged twice.
- When **nothing** was delivered and at least one medium returned `UNSUPPORTED`, a `warning` is logged
  (a player whose only preferred medium is permanently unreachable would otherwise accumulate
  notifications silently until expiry).
- A sink throwing a `RuntimeException` is caught, logged, and treated as `UNREACHABLE`, so one broken
  sink cannot abort delivery to the others.

`RenderableNotification` holds **no `Player` and no `Audience`** — Essentials mail and Discord deliver to
absent players. `Component` is the lingua franca; non-Minecraft sinks serialize it down, so bodies must
not rely on in-game-only affordances such as click events. Actions/buttons are intentionally **out of
scope** (a dialog button and a Discord button share no execution model), so dialogs render as
read-and-dismiss.

## Notification categories

Design doc: `docs/superpowers/specs/2026-07-28-categorised-notification-preferences-design.md`.

Preferences are resolved per **category**, a coarser grouping than a payload `dataType`, so a player can
prefer `chat` for moderation notices but `discord` + `chat` for economy ones. Categories are
**config-driven**, not registered in code: `categories.yml` maps `dataType → category` and gives each
category a label/description, deserialized via Configurate into `core.category.NotificationCategoriesConfig`
/ `NotificationCategoryDefinition`. `core.category.NotificationCategories` builds the resolved
`dataType → category` map at construction (first category to claim a `dataType` wins; a collision is
logged as a warning) and exposes `resolve(dataType)`, `categoryKeys()`, `label(key)`, `description(key)`,
and `typesWithNoPayloadMapping(registry)` (checked once at startup, after modules load, to warn about a
category referencing a `dataType` nothing registered).

A `dataType` no category claims resolves to the reserved key `NotificationCategories.UNCATEGORIZED`
(`"uncategorized"`), which is always a real, selectable category — a newly installed module's
notifications are configurable immediately, without an operator editing `categories.yml` first.

`DatabaseNotificationPreferences` stores rows as `(playerUuid, category, medium)` and resolves
`preferredMedia(player, category)` with this precedence: exact rows for `category`, else rows for the
reserved key `DatabaseNotificationPreferences.ALL_CATEGORIES_KEY` (`"*"`, a blanket "applies to any
category" fallback — nothing in the dialogs writes it directly), else `default-media` from
`settings.yml`. The single-argument `preferredMedia(player)` is the same lookup against `"*"`.

## Player commands

Registered in `PlayerNotificationsPlugin.registerCommands()` through Paper's Brigadier API
(`LifecycleEvents.COMMANDS`) — **not** a `commands:` block, which `paper-plugin.yml` does not support.

- `/notifications` (alias `/notifs`, also `/notifications preferences`) — opens the root preferences
  dialog.
- `/notifications media` — jumps straight to the "by delivery method" picker.
- `/notifications types` — jumps straight to the "by notification type" picker.
- `/notifications mute` — mutes every category **immediately** (no staging).
- `/notifications reset` — clears every stored preference **immediately** (no staging).
- `/notifications reload` — reloads `categories.yml` and `settings.yml` without a restart. Admin-only
  (`playernotifications.command.reload`, `default: op`), and usable from console, unlike every other
  subcommand — it operates on plugin configuration, not a specific player, so `NotificationsCommand`
  dispatches it outside the player-only `run()` helper the rest of the tree uses. Deliberately does
  **not** reload `database.yml`, since that would mean rebuilding the MariaDB connection pool mid-request.

The player-facing subcommands are player-only, under permission `playernotifications.command.preferences`,
declared in `paper-plugin.yml` with `default: true`.

`paper.command.NotificationsCommand` builds the Brigadier node and delegates every subcommand to a
`paper.preferences.PreferenceDialogRouter`, the single object owning the five dialog screens and the
shared `PreferenceSessionManager` (`paper.preferences.session`):

- `PreferenceRootDialog` — pick a pivot ("By delivery method" / "By notification type"), or stage
  "Mute everything" / "Reset all to server default"; shows Apply / Discard only while the session is
  dirty.
- `MediumPickerDialog` → `MediumEditorDialog` — pick a medium, then one checkbox per **category**
  ("which notifications reach me on Discord").
- `CategoryPickerDialog` → `CategoryEditorDialog` — pick a category, then one checkbox per **medium**
  plus "use server default" ("where does Economy reach me").

Both editors mutate the same `paper.preferences.session.PreferenceEditSession`, so the two pivots can
never disagree. Editor "Save" writes only into the session; nothing is persisted until the root
screen's **Apply**, which writes every dirty category in one transaction
(`DatabaseNotificationPreferences.applyChanges`). A category emptied to nothing — from either editor —
stages a mute (`{"none"}`), never a silent fall-through to the server default; only the explicit "use
server default" action stages a reset (`DatabaseNotificationPreferences.resetCategory` equivalent,
clearing that category's rows on Apply).

**Three preference states per category**, expressible per-category via the category editor:

| State | Storage | `preferredMedia(player, category)` returns |
|---|---|---|
| Unconfigured | no exact rows for that category | `*` rows, else `default-media` from `settings.yml` |
| Explicit selection | one row per medium for that category | that set |
| Explicit mute | a single `medium = 'none'` row for that category | `{none}` |

`NullSink` is registered for `"none"` and returns `DELIVERED`, so a muted category's notifications are
**consumed** rather than accumulating until expiry — a mute means "do not tell me", not "queue this for
later". `NullSink` is excluded from every checkbox list, since checking nothing already says the same
thing.

Implementation notes:
- `PreferenceSessionManager` expires a session after 15 minutes idle (`IDLE_TIMEOUT`) and
  `PreferenceQuitListener` drops it on `PlayerQuitEvent`; reopening after either starts fresh from the
  database.
- `/notifications mute` and `/notifications reset` write **immediately** and discard any open staged
  session with a chat notice — the one deliberate asymmetry with the root screen's staged equivalents,
  which only take effect on Apply.
- `DatabaseNotificationPreferences` does blocking JDBC while `Player#showDialog` must run on the main
  thread, so dialog loads/writes marshal onto the async scheduler and back (`PreferenceDialogs.withSession`).
- Dialog input keys are **positional** (`medium_0`, `category_0`, …) with a key→value map, because
  medium/category keys are arbitrary strings and any sanitizing transform risks two colliding onto one
  input.
- Button callbacks use `ClickCallback.Options` with `uses(1)` and a one-hour lifetime; a dialog left
  open past that has inert buttons and must be reopened.
- **Known quirk:** an explicitly registered `NotificationProcessor` wins the dispatch-precedence rule and
  bypasses preferences (and therefore categories) entirely, so a muted player still receives
  `EssentialsMailProcessor` mail. Pre-existing; fixing it means converting those processors into sinks.
- The five dialog classes and the router are **unverified by automated tests** — they need a live
  server. Check them by hand with `:platform:paper-plugin:runServer`.
- `PlayerNotificationsPlugin.reload()` swaps the reloaded `NotificationCategories` into a freshly
  constructed `NotificationDelivery` and into `PreferenceDialogRouter` (via
  `PreferenceDialogRouter.reloadCategories`, a mutable field rather than a final one), and swaps the
  reloaded `default-media` into `DatabaseNotificationPreferences` (via `reloadDefaultMedia`, a `volatile`
  field) — both without reconstructing objects other code already holds references to. A player with an
  already-open, staged `PreferenceEditSession` keeps editing against whatever category set was in effect
  when the session was loaded; its category keys are still valid strings to write on Apply even if the
  reload renamed or removed one, matching how a category removed from config is already handled
  elsewhere (see "Current state"). The prune task is cancelled and rescheduled if
  `prune-interval-seconds` changed.

## Configuration

All config uses **Configurate** (`YamlConfigurationLoader`), not Bukkit's `getConfig()`. On enable the plugin copies bundled defaults into the data folder, merges in any new keys, and deserializes into `@ConfigSerializable` records:
- `database.yml` → `DatabaseSettings` (in `core`): `url` (JDBC url **without** the `jdbc:` prefix), `username`, `password`.
- `settings.yml` → `PluginSettings` (in `paper-plugin`): `prune-interval-seconds` (default 3600) — how often the async task deletes expired notifications; `default-media` (`List<String>`, default `[chat]`) — the media a player is assumed to prefer when they have no stored preference rows.
- `categories.yml` → `NotificationCategoriesConfig` (in `core`, package `category`): `uncategorized-label` — the label for the catch-all category; `categories` — a map of category key → `{label, description, types}`, each `types` entry a registered `dataType` string. See "Notification categories".

Conventions when editing config:
- Every non-null (`@NotNull`, reference-typed) `@Setting` field must also be annotated `@Required`, so a missing key fails loudly instead of deserializing to null.
- Configurate 4.2.0 has **no built-in `java.time.Duration` serializer** (`node.get(Duration.class)` returns null). Use a `long`-seconds field instead, or register a custom serializer.

## Persistence layer (`core`)

MyBatis over MariaDB, structured like a smaller version of the sibling `realty` project:
- `database.Database` / `database.SqlSessionWrapper` — vendor-neutral interfaces; `SqlSessionWrapper` exposes the typed mappers bound to one `SqlSession`/transaction.
- `database.entity` — record entities mirroring the DDL (`NotificationEntity`, `NotificationTargetEntity`, `PlayerNotificationPreferenceEntity`).
- `database.mapper` — vendor-neutral mapper interfaces (`NotificationMapper`, `NotificationTargetMapper`, `PlayerNotificationPreferenceMapper`).
- `database.maria` — `MariaDatabase` (builds the `SqlSessionFactory`, registers mappers + the `UUIDAsBin16Handler` UUID↔`BINARY(16)` type handler), `MariaSqlSession`, `MariaSchemaMigrator`.
- `database.maria.mapper` — MariaDB mappers with `@Select`/`@Insert`/`@Delete` (and `<script>`/`<foreach>` for batch ops), extending the neutral interfaces.
- `database.migration.MigrationStep` + `core/src/main/resources/sql/migrations/V*.sql` — the migrator tracks applied versions in a `schema_version` table and runs each script once. **Adding a migration means adding both the `V*.sql` file and a `MigrationStep` entry to `MariaSchemaMigrator.DEFAULT_MIGRATIONS`** — that list is hardcoded, not discovered from the classpath. Currently a single step, `V1__maria_initial_schema.sql` — the project is still in prototyping with no data to preserve across schema versions, so earlier migrations were collapsed into it rather than layered.

Schema (`V1__maria_initial_schema.sql`), three tables:
- `NotificationTarget(notifTargetId INT, playerUuid BINARY(16), PRIMARY KEY(notifTargetId, playerUuid))` — a target group is the set of rows sharing a `notifTargetId`. New group ids come from `MAX(id)+1` allocated inside the enqueue transaction.
- `Notification(notifKey PK, notifScheduledTime, notifExpiryTime NULL, notifTargetId, notifPayloadType, notifPayload JSON, notifPriority)` with indexes on `notifTargetId`, `notifPayloadType`, `notifScheduledTime`, `notifExpiryTime`.
- A trigger `trg_delete_targetless_notification` (`AFTER DELETE ON NotificationTarget`) deletes a notification once its target group has no remaining members. It is a **single-statement trigger body** (no `BEGIN…END`) because `MariaSchemaMigrator` splits scripts on `;`.
- `PlayerNotificationPreference(playerUuid BINARY(16), category VARCHAR(64), medium VARCHAR(64), PRIMARY KEY(playerUuid, category, medium))` — one row per preferred medium **per category**, so a player's preference is set-valued within each category (`chat` + `discord` for `economy` is two rows). See "Notification categories" for how `category` and the reserved keys `*`/`uncategorized` resolve.

`player-notifications.drawio` is the design source for the schema (note it uses conceptual names like `notif_key`; the DDL uses camelCase columns) — it predates the `category` column and has not been updated.

## Testing gotchas

- `:core:test` needs a **running Docker daemon** (Testcontainers, `mariadb:11.7`). Without it the suite fails rather than skipping.
- `api`, `core`, **and `platform:paper-plugin`** declare **`testRuntimeOnly("io.papermc.paper:paper-api")`**. `compileOnlyApi` is not on the test runtime classpath, so without it tests touching Adventure/Bukkit types die at discovery with `NoClassDefFoundError: net/kyori/adventure/text/Component`.
- **Counting results: glob `*.xml`, not `TEST-*.xml`.** On Windows, Gradle shortens result filenames for `@Nested` classes to dodge the path-length limit, producing `__TEST-<hash>...` names. Several test classes here (`NotificationMapperTest`, `PlayerNotificationPreferenceTest`) put **all** their `@Test` methods inside `@Nested` inner classes, so a `TEST-*.xml` glob silently omits them and makes passing tests look like they never ran.
- `./gradlew :core:test --tests "<pattern>"` can report **BUILD SUCCESSFUL while matching nothing meaningful**. Check the result count, not the exit status.

Current baseline: **63 tests in `:core:test`, 13 in `:api:test`, 17 in `:platform:paper-plugin:test`**, all passing.

## Current state

The project builds end-to-end; `:core:test`, `:api:test`, and `:platform:paper-plugin:test` pass. The
enqueue → deliver path is complete: `enqueueNotification` persists the notification's `notifPayloadType`;
`NotificationDelivery.deliver(UUID[, Instant])` resolves a player's due notifications, decodes each
payload through its registered `PayloadSerializer`, resolves a category via `NotificationCategories` when
one was supplied, dispatches by the precedence rule above, and prunes targets whose processor returns
`NotificationDisposition.DELETE` (the trigger then removes notifications with no remaining targets).
Preferences are now categorised end-to-end: storage, dispatch, and the player-facing dialogs (root,
by-medium, by-category) all agree on the same category axis. Known gaps / notes:
- **Nothing calls `deliver(UUID)`.** `PlayerNotificationsPlugin` now constructs `NotificationDelivery` and exposes it via `notificationDelivery()`, but there is **no join listener** — no `Listener` is registered for it anywhere in `platform/` (the one `Listener` that does exist, `PreferenceQuitListener`, only drops staged preference-edit sessions). Wiring delivery to an actual trigger (player join, a command, a scheduled task) is the remaining bootstrap step.
- **`ChatSink`, `DialogSink`, and the five preference dialog screens are unverified by automated tests** — they need a live server. Check them by hand with `:platform:paper-plugin:runServer`.
- **`notifPayload` is a `JSON` column**, so payloads must be valid JSON; a plain message string must be JSON-encoded. String-payload processors therefore receive the JSON-encoded form — consider a `TEXT` column or decoding on the way out.
- **Partial delivery is silent** under the DELETE-wins fan-out — see "Rendering & delivery media".
- Target-id allocation via `MAX(id)+1` is not concurrency-safe under parallel enqueues (fine for a plugin's low write volume).
- **Rows for a category removed from `categories.yml` are kept, not pruned** — they resurface if the category is re-added, and are invisible in the dialogs meanwhile. No admin command prunes them.
- Deferred to their own designs: the **Discord sink and account linking**, **per-medium delivery tracking**, **actions/buttons** in `RenderableNotification`, and a **player-facing inbox** (`/notifications` covers preferences only — there is no listing or player-initiated clear, and no admin commands or admin view of another player's preferences).
