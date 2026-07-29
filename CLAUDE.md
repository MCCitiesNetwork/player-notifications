# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Workflow skills

Four project-local skills in `.claude/skills/` replace the `superpowers` plugin, which is disabled
for this project in `.claude/settings.local.json`. Invoke the matching one **before** acting; they
run inline and do not dispatch subagents.

| Situation | Skill |
|---|---|
| New feature, API change, behaviour change — before any code | `design` |
| Writing feature/bugfix code, or executing a plan | `implement` |
| Any bug, test failure, build failure, unexpected behaviour | `debug` |
| Before claiming done/fixed/passing, and before committing | `ship` |

`design` → `implement` → `ship` is the normal path; `debug` feeds into `implement`. Skip `design`
only for changes whose shape is not in question (a typo, a rename, a one-line config default).
Subagents are opt-in: dispatch one only when the user asks.

The skills state process rules (which hold regardless) and repo facts (which drift). Where a skill
describes this codebase and the code disagrees, **the code wins** — fix the skill in the same commit,
the same as for a stale line in this file. Test counts, module lists, and the open gaps in "Current
state" below are the parts most likely to be out of date.

## Overview

PlayerNotifications is a PaperMC (Spigot) plugin for Minecraft **1.21.8**, targeting **Java 21**. It stores per-player notifications in a MariaDB database and delivers them to players via pluggable, payload-typed processors — or, more commonly, via the **renderer/sink** path, which fans a notification out to whichever media a player prefers (chat, dialog, Essentials mail, Discord DM). Persistence is implemented with MyBatis in the `core` module; the Paper bootstrap and platform integrations live under `platform/`. Cross-cutting infrastructure (a runtime module system, schema migrator, Configurate helpers) comes from the external `plugin-infrastructure` library.

## Build & run

Uses the Gradle wrapper (Gradle 9.3.0). On Windows, use `./gradlew` from the Bash tool or `gradlew.bat` from PowerShell.

- `./gradlew build` — build all modules. The `platform:paper-plugin` module's `build` depends on `shadowJar`, producing the shaded plugin jar under `platform/paper-plugin/build/libs/`.
- `./gradlew :platform:paper-plugin:shadowJar` — build only the distributable plugin jar.
- `./gradlew :platform:essentials-adapter:jar` — build the Essentials mail adapter module jar.
- `./gradlew :platform:discord-adapter:shadowJar` — build the Discord adapter module jar. It must be the **shaded** (`-all`) jar: the module bundles its own relocated JDA, so the plain `jar` output has no Discord library in it at all.
- `./gradlew test` — run all tests (JUnit 5 / Jupiter).
- `./gradlew :core:test` — run the `core` persistence tests. **These require a running Docker daemon** — they spin up a real `mariadb:11.7` container via Testcontainers.
- `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.*SomeTest"` — run a single test class/method.
- `./gradlew :platform:paper-plugin:runServer` — launch a real Paper 1.21.8 test server with the plugin loaded (via the `xyz.jpenilla.run-paper` plugin). Server files go under `platform/paper-plugin/run/`. Needs a reachable MariaDB (see `database.yml`). It `dependsOn` `installFeatureModules`, so every feature module is built and installed first, and its `downloadPlugins` block fetches DiscordSRV `v1.30.5` from GitHub (the discord adapter's link source — without it `DiscordSrvAccountProvider` reports itself unavailable and the adapter cannot be exercised end to end).
- `./gradlew :platform:paper-plugin:installFeatureModules` — `Sync` the feature-module jars into the runServer data folder's `run/plugins/PlayerNotifications/modules/`. Modules are **not** classpath entries (the host loads them through their own `URLClassLoader`), so they are installed as files, not added to the server classpath. Adding a new adapter means adding one `featureModules(project(path = ":platform:<name>", configuration = "moduleJar"))` line to `platform/paper-plugin/build.gradle.kts`. The `Sync` owns only the top-level `*.jar` files — everything else in that directory is preserved at any depth (`preserve { include("**"); exclude("*.jar") }`), so module configs written at runtime survive, whether they are loose files (`discord.yml`, holding the bot token) or a module's own config subdirectory.

## Module architecture

Gradle build with `api`, `core`, and three platform modules (`settings.gradle.kts` includes `api`, `core`, `platform:paper-plugin`, `platform:essentials-adapter`, `platform:discord-adapter`). Type-safe project accessors are enabled (`enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`), so build scripts reference `projects.api`, `projects.core`, etc. Dependency direction flows **platform → core → api**; `api` depends on nothing but Paper.

- **`api`** (`io.github.md5sha256.playernotifications.api`) — Public, dependency-light API. Uses `compileOnlyApi` for `paper-api`. Key types:
  - `NotificationService` — entry point: `enqueueNotification`, `resolveNotifications(UUID)` (a pure read — does **not** delete), `clearNotification`/`clearNotifications`, `deleteNotificationTarget(key, UUID)` / `deleteNotificationTargets(key, Collection<UUID>)`, `clearExpiredNotifications`, and `dataTypeRegistry()`.
  - `NotificationDataTypeRegistry` — maps a string `dataType` → payload `Class<?>`, and payload class → `NotificationProcessor`, `PayloadSerializer`, and `NotificationRenderer`. The extension point: callers register their own payload types, processors/renderers, and serializers.
  - `NotificationSinkRegistry` — separate registry keyed by **medium** (`"chat"`, `"dialog"`, `"essentials-mail"`, `"discord-dm"`), not by data type. `registerSink` keys off `NotificationSink#mediumKey()`. `"discord-channel-ping"` is **reserved but unimplemented** (see "Discord adapter").
  - `Notification` / `ResolvedNotification` — records for the persisted vs. target-resolved forms. `ResolvedNotification` holds a `NotificationTarget` (list of player UUIDs) and carries `notifPayloadType` (the registry data-type string) plus the `String` payload.
  - **`api.processor`** package — `NotificationProcessor<T>` is a pure `@FunctionalInterface`: `NotificationDisposition receiveNotification(T payload, UUID target)` — it processes **one target (audience member) per call** and returns whether the notification should be `RETAIN`ed or flagged for `DELETE`. Composition lives in `NotificationProcessorBuilder` (fluent "chop-down" chaining via `andThen`/`andThenIf`/`onComplete`, folding dispositions with DELETE-wins). `FixedDelayProcessor` wraps a processor with a scheduled delay.
  - **`api.render`** package — the renderer/sink architecture (see "Rendering & delivery media" below): `RenderableNotification` (medium-neutral `Component` title + body), `NotificationRenderer<T>` (payload → `RenderableNotification`, one per payload type), `NotificationSink` (`RenderableNotification` → a medium, one per medium, returning a `DeliveryResult`), `DeliveryResult` (`DELIVERED` / `UNREACHABLE` / `UNSUPPORTED`), `NotificationPreferences` (`UUID` → `Set<String>` of preferred media, plus a `default` two-argument `preferredMedia(UUID, String dataType)` overload — resolved directly against the payload's `dataType`, with no category involved in dispatch; see "Notification categories" for the separate, display-only category concept), and `RenderingProcessor<T>` — the single framework-supplied processor that binds them, constructed with the `dataType` it dispatches for (`@NotNull`, required). `NotificationSink` also carries `default` `displayName()` / `description()` `Component`s used to label media in player-facing UI (`displayName()` title-cases `mediumKey()`, so `essentials-mail` → "Essentials Mail"). `api.render.sink` holds `ChatSink`, `DialogSink`, and `NullSink` (the `"none"` medium backing an explicit mute — see "Player commands").
  - **`api.category`** package — `NotificationCategoryRegistry` (`DefaultNotificationCategoryRegistry` the in-memory impl) lets module authors declare categories and claim `dataType`s under them in code, exactly like payload types/processors/renderers/sinks are registered. Exposed via `NotificationService#categoryRegistry()`. Merged at read time with `categories.yml` by `core.category.NotificationCategories` — see "Notification categories".
  - **`api.serialize`** package — `PayloadSerializer<T>` (JSON string ↔ `T`) and `PayloadSerializationException`. The only serialization type crossing the API boundary; the JSON library stays an implementation detail of whoever supplies the serializer.
- **`core`** (`io.github.md5sha256.playernotifications.core`) — MyBatis persistence, `DefaultNotificationService`, `NotificationDelivery` (the delivery loop, dispatches directly on `dataType`, with no category resolution), `DatabaseNotificationPreferences` (the persisted, `dataType`-keyed `NotificationPreferences` impl), `category.NotificationCategories` (a read-only display/grouping merge — see "Notification categories"), and `serialize.JacksonPayloadSerializer`. `api("org.mybatis:mybatis")`, `api("org.spongepowered:configurate-yaml")`, `implementation("org.mariadb.jdbc:mariadb-java-client")`, `paper-api` compileOnly **plus `testRuntimeOnly`** (see "Testing gotchas"). See "Persistence layer" below.
- **`platform:paper-plugin`** (`io.github.md5sha256.playernotifications.paper`) — Paper bootstrap. `PlayerNotificationsPlugin.onEnable` loads config (including `categories.yml`), builds a `MariaDatabase`, runs schema migration, constructs `DefaultNotificationService`, registers it under `NotificationService.class` in the Bukkit `ServicesManager`, builds the `NotificationSinkRegistry` (registering `ChatSink`, `DialogSink`, and `NullSink`), `DatabaseNotificationPreferences`, and `NotificationCategories`, constructs `NotificationDelivery` with all three, registers the Brigadier commands and a `PreferenceQuitListener`, schedules the async prune task, starts the module system, and finally warns about any `categories.yml` data type with no registered payload mapping. Exposes `database()` / `notificationService()` / `sinkRegistry()` / `preferences()` / `categories()` / `notificationDelivery()` accessors for modules. Applies `shadow` (relocating `org.mariadb`, `org.mybatis`, `org.apache.ibatis`, `org.spongepowered`, `io.leangen.geantyref`, `com.fasterxml.jackson`) and `run-paper`. Also declares `testRuntimeOnly("io.papermc.paper:paper-api")` (see "Testing gotchas") — needed once its own tests started touching Adventure/Bukkit types.
- **`platform:essentials-adapter`** (`io.github.md5sha256.playernotifications.essentials`) — a **feature module** (see "Module system") that renders notifications as Essentials mail. `EssentialsMailModule` (the manifest entry class) registers an `EssentialsMailProcessor` for the `essentials-mail` data type, via `registerJsonPayload` against its own `EssentialsMailPayload` record. It deliberately does **not** map to `String.class`: the registry keys handlers by payload class, so a shared class means a shared processor/serializer/renderer, and `unregisterPayloadMapping`'s cascade would tear out the host's shared `String` serializer on module unload. Applies the `paper-adapter` convention; declares only the EssentialsX API (compile-only).
- **`platform:discord-adapter`** (`io.github.md5sha256.playernotifications.discord`) — a **feature module** that delivers notifications as Discord DMs. `DiscordModule` (the manifest entry class) registers a `DiscordDmSink` under medium key `discord-dm` — a **sink**, not a processor, so unlike the Essentials adapter it participates in preferences and fan-out. Applies `paper-adapter` plus `com.gradleup.shadow`, bundling its own relocated JDA. See "Discord adapter" below.

### Build conventions

`buildSrc/src/main/kotlin/` holds precompiled convention plugins:
- `player-notifications-conventions` — Java 21 toolchain, UTF-8, JUnit 5, and the Paper / mavenLocal / mavenCentral repos. Applied by every module.
- `paper-adapter` — for feature-module projects. Applies the base conventions, adds `compileOnly(project(":platform:paper-plugin"))` (so adapters compile against the host but never bundle it — the module class loader resolves host classes at runtime) **plus `testImplementation` on the same project** (`compileOnly` reaches neither `compileTestJava` nor the test runtime, so an adapter's own tests would not see `api`/`core` or Adventure types at all), and adds the `maven.democracycraft.net/snapshots` repo. It also declares a **consumable `moduleJar` configuration** whose artifact is the module's deliverable jar — `jar` by default, swapped to `shadowJar` under `plugins.withId("com.gradleup.shadow")` (lazily, since an adapter applies shadow *after* the convention), so a shading adapter such as `discord-adapter` publishes its `-all` jar. `platform:paper-plugin`'s `installFeatureModules` resolves that configuration; the host therefore never needs to know which adapters shade. Because `paper-plugin` exposes `api`/`compileOnlyApi` dependencies, this single dependency transitively provides `api`, `core`, `plugin-infrastructure`, and `paper-api` to adapters.

Note: precompiled conventions apply sibling conventions with `id("player-notifications-conventions")`, not the backtick accessor. Project version comes from the root `gradle.properties`.

### plugin-infrastructure dependency

`net.democracrycraft:plugin-infrastructure` (from `https://maven.democracycraft.net/snapshots`, see its README) provides `net.democracrycraft.pluginInfrastructure.modules` (the module system), `.sql` (schema migration primitives), `.configurate`, and `.util`. Consumed as a normal repo dependency — **not** an `includeBuild`.

## Module system

Feature modules are jars dropped into `<dataFolder>/modules/`, each containing a `module-manifest.yml` and an entry class implementing `PluginModule<T extends Plugin>` (or extending `SimplePluginModule`). `PlayerNotificationsPlugin` drives them with a `ModuleLifecycleManager` (`start()` on enable after the service is registered, `stop()` first on disable). A module's `initialize` receives the host plugin and typically resolves `NotificationService` from the `ServicesManager` to register processors.

The manifest keys are the `ModuleManifest` record's component names in **kebab-case**. Configurate's
`ObjectMapper` uses `NamingSchemes.LOWER_CASE_DASHED` by default, so `moduleName` reads the key
`module-name`, **not** `moduleName`:

```yaml
module-name: essentials-mail-adapter
entry-class: io.github.md5sha256.playernotifications.essentials.EssentialsMailModule
author: md5sha256
expected-plugin-class: io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin
reloadable: false
```

A camelCase key does not fail — it simply does not match, and the record component deserializes to
`null`. That is silent for every key except a single-word one like `author`, which happens to be
identical under both schemes. `ModuleLoader.loadModulesFromDisk` then NPEs on
`manifest.expectedPluginClass().equals(...)`, aborting `onEnable`. The same rule applies to every
`@ConfigSerializable` record in this repo — hence `prune-interval-seconds`, `default-media`,
`bot-token`, `uncategorized-label`.

`expectedPluginClass` must equal the host plugin's runtime FQCN, or the module is skipped.

## Rendering & delivery media

Design doc: `docs/superpowers/specs/2026-07-27-notification-renderers-design.md`.

Payload *rendering* and medium *delivery* are separate axes, so a payload is converted **once** and can
fan out to any number of media. Registrations are **N + M**, not N × M: adding a medium requires no
change to any existing payload — the Discord DM sink landed without touching a single one.

- A payload author registers a `NotificationRenderer<T>` (payload → `RenderableNotification`) and never
  writes per-medium or preference-lookup logic. `NotificationService#registerJsonRenderable(dataType,
  type, renderer)` is the one-call form (mapping + reflective JSON serializer + renderer), mirroring
  `registerJsonPayload` for the processor path. It registers **no** processor by design — an explicit
  processor wins dispatch precedence and would bypass preferences and sinks entirely.
  `paper.diagnostic.TestNotificationRenderer` is the in-tree example.
- A medium owner registers one `NotificationSink` in the `NotificationSinkRegistry`.
- `RenderingProcessor<T>` — the **single** framework-supplied processor — resolves preferred media via
  `NotificationPreferences#preferredMedia(target, dataType)`, passing the notification's `dataType`
  directly (no category resolution in the dispatch path at all — see "Notification categories"), renders
  once, and delivers to each preferred medium's sink. A medium with no registered sink is logged at
  `fine` and skipped.

**Dispatch precedence in `NotificationDelivery`:** an explicitly registered `NotificationProcessor`
always wins (so `EssentialsMailProcessor` and other bespoke processors keep working unchanged —
including bypassing preferences entirely); otherwise a registered `NotificationRenderer` dispatches
through `RenderingProcessor`, built with the notification's `notifPayloadType` as its `dataType`;
otherwise the notification is logged and retained. `NotificationDelivery` has a 3-arg constructor (no
rendering path) and a 5-arg one (rendering) — categories play no role in delivery.

**Fan-out is DELETE-wins:** if any sink returns `DELIVERED`, the notification is consumed. Consequences,
deliberate and documented in the design doc's "Known limitations":
- **Partial delivery is silent and unrecoverable.** If a player prefers `chat + discord-dm`, chat succeeds
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

### Discord adapter

Design doc: `docs/superpowers/specs/2026-07-29-discord-adapter-design.md`.

`platform:discord-adapter` is a feature module registering `DiscordDmSink` under medium key
**`discord-dm`**. Once the jar is in `<dataFolder>/modules/` and configured, "Discord DM" appears
automatically in the `/notifications` dialogs — they enumerate `sinkRegistry().registeredMedia()`, so
no host change was needed. `DiscordMedia.CHANNEL_PING` (`"discord-channel-ping"`) is **reserved but
unimplemented**: nothing registers it, so it can never be selected; the key exists so the DM sink is
not squatting on a generic `"discord"` name and a channel sink can be added later without migrating a
single preference row.

- **It runs its own JDA bot with its own token.** Legacy DiscordSRV relocates its bundled JDA to
  `github.scarsz.discordsrv.dependencies.jda.*`, so its instance is *not* type-compatible with upstream
  `net.dv8tion`. DiscordSRV is therefore a **link source only** — nothing is ever sent through it.
- **The shading is load-bearing.** `shadowJar` relocates every bundled package under
  `io.github.md5sha256.playernotifications.discord.libraries`. Modules load through
  `new URLClassLoader(jarUrl, hostClassLoader)` — parent-first — and the host already shades Jackson,
  so an unrelocated copy would collide. **Verify the relocation set against the built jar** after any
  dependency bump (`unzip -l ...-all.jar` and look for classes outside `io/github/md5sha256/`); JDA 6's
  transitive set is not JDA 5's.
- **`paper-plugin.yml` carries a soft `dependencies: server: DiscordSRV` with `join-classpath: true`.**
  Paper plugins are classloader-isolated by default, and the module's loader is parent-first onto the
  *host's*, so without that entry `DiscordSrvAccountProvider` silently reports itself unavailable.
- **The provider swap point.** `DiscordAccountProvider` (`providerKey`, `discordIdFor`, `isAvailable`)
  resolves UUID → Discord id. `discord.yml`'s `link-providers` is an ordered key list resolved against
  `DiscordAccountProviderRegistry` and wrapped in `ChainedDiscordAccountProvider` (first link wins;
  unknown key warned and skipped; unavailable skipped un-queried; a throwing provider logged and
  skipped so it cannot mask a working one). Only `DiscordSrvAccountProvider` ships. Adding a provider
  is one class, one registration and one config line — `DiscordDmSink` never changes.
- **Rendering.** `DiscordMarkdownSerializer` drives Adventure's `ComponentFlattener` to Discord
  markdown (bold `**`, italic `*`, underlined `__`, strikethrough `~~`, obfuscated → spoiler `||`),
  escaping `` \ * _ ~ | ` > `` and dropping colours — Discord message text cannot be coloured.
  `DiscordMessageFactory` builds the `MessageCreateData` in one of three `message-format`s
  (`embed` | `markdown` | `plain`) and truncates to Discord's limits (title 256, description 4096,
  content 2000) *before* JDA's builders, which throw on overlong input rather than trimming.
- **Result mapping.** No linked account → `UNSUPPORTED` (the exact case that result's javadoc names);
  `CANNOT_SEND_TO_USER`/`UNKNOWN_USER` → `UNSUPPORTED`; not connected, rate-limited, timed out, or any
  other exception → `UNREACHABLE`. `DiscordDmSink` also refuses to run on the main thread (warn +
  `UNREACHABLE`), since it blocks on a Discord round trip; the delivery loop is already async.
- **`discord.yml`** (bundled in the *module* jar, written to `<dataFolder>/modules/discord.yml`):
  `bot-token` (blank refuses module startup), `message-format`, `embed-color` (`#RRGGBB`),
  `delivery-timeout-seconds` (`long`, not a `Duration`), `link-providers`. Loaded by `ModuleConfigs`,
  which reproduces the host's copy-defaults-then-merge idiom because
  `PlayerNotificationsPlugin.copyDefaultsYaml` is private and reads the *host* jar's resources.
- **Untested by automated tests:** `DiscordBot`, `JdaDiscordMessenger`, `DiscordSrvAccountProvider`
  and `DiscordModule` — they need a live server, a real bot token and a DiscordSRV-linked account. The
  manual checklist is Task 8 of `docs/superpowers/plans/2026-07-29-discord-adapter.md`, and it has
  **not been run**. Everything else in the module is unit tested.

## Notification categories

Design doc: `docs/superpowers/specs/2026-07-28-categorised-notification-preferences-design.md`.

**Categories are a display/grouping concept only — preferences are stored and resolved per `dataType`,
not per category.** A category is a coarser, player-facing grouping over one or more `dataType`s (e.g.
"Economy" might group `mail` and `receipt`), used purely by the preference dialogs' "by notification
type" pivot and its bulk fan-out; nothing in the delivery/dispatch path (`NotificationDelivery`,
`RenderingProcessor`, `DatabaseNotificationPreferences`) touches categories at all.

Categories are **many-to-many** and come from two sources merged at read time: `categories.yml`
(deserialized via Configurate into `core.category.NotificationCategoriesConfig` /
`NotificationCategoryDefinition`) and the code-driven `api.category.NotificationCategoryRegistry` (module
authors call `registerCategory`/`claimDataType` on the instance exposed via
`NotificationService#categoryRegistry()`). `core.category.NotificationCategories` builds this merge at
construction and exposes `resolve(dataType): Set<String>` (every category — config- and code-claimed —
that claims the type; a `dataType` claimed by two categories resolves to both, no collision to resolve),
`categoryKeys()`, `label(key)`, `description(key)`, `dataTypesForCategory(categoryKey, allKnownDataTypes)`
(the complement for `UNCATEGORIZED`), and `typesWithNoPayloadMapping(registry)` (checked once at startup,
after modules load, to warn about a category referencing a `dataType` nothing registered). A category-key
collision (code and config both defining the same key) logs at `fine`; config's label/description wins.

A `dataType` no category claims resolves to `Set.of(NotificationCategories.UNCATEGORIZED)`
(`"uncategorized"`), which is always a real, selectable category — a newly installed module's
notifications are configurable immediately, without an operator editing `categories.yml` first.

`DatabaseNotificationPreferences` stores rows as `(playerUuid, dataType, medium)` and resolves
`preferredMedia(player, dataType)` with this precedence: exact rows for `dataType`, else rows for the
reserved key `DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY` (`"*"`, a blanket "applies to any data
type" fallback — nothing in the dialogs writes it directly), else `default-media` from `settings.yml`.
The single-argument `preferredMedia(player)` is the same lookup against `"*"`.

## Player commands

Registered in `PlayerNotificationsPlugin.registerCommands()` through Paper's Brigadier API
(`LifecycleEvents.COMMANDS`) — **not** a `commands:` block, which `paper-plugin.yml` does not support.

- `/notifications` (alias `/notifs`, also `/notifications preferences`) — opens the root preferences
  dialog.
- `/notifications media` — jumps straight to the "by delivery method" picker.
- `/notifications types` — jumps straight to the "by notification type" picker.
- `/notifications mute` — mutes every known `dataType` **immediately** (no staging).
- `/notifications reset` — clears every stored preference **immediately** (no staging).
- `/notifications test [message]` — enqueues a `test` notification targeting the sender and delivers it
  immediately, off the main thread. Admin-only (`playernotifications.command.test`, `default: op`) and
  player-only. The **only** caller of `NotificationDelivery.deliver(UUID)` in the tree. Its reply names
  the media *attempted*, not delivered — `RenderingProcessor` reports no per-sink outcome. Backed by
  `paper.diagnostic.TestNotificationSender`, which takes a `Supplier<NotificationDelivery>` rather than
  the instance because `reload()` replaces that object.
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
- `MediumPickerDialog` → `MediumEditorDialog` — pick a medium, then one checkbox per **`dataType`**
  (grouped/labeled by its primary category for readability — see `PreferenceDialogs.sortedDataTypes`/
  `dataTypeLabel`), e.g. "which notifications reach me on Discord".
- `CategoryPickerDialog` → `CategoryEditorDialog` — pick a category, then one checkbox per **medium**
  plus "use server default"; each medium's checkbox fans out to every `dataType` the category claims
  (`NotificationCategories#dataTypesForCategory`), and shows "(mixed)" when the category's member
  `dataType`s currently disagree on that medium.

Both editors mutate the same `paper.preferences.session.PreferenceEditSession`, keyed by `dataType` (not
category), so the two pivots can never disagree. Editor "Save" writes only into the session; nothing is
persisted until the root screen's **Apply**, which writes every dirty `dataType` in one transaction
(`DatabaseNotificationPreferences.applyChanges`). A `dataType` emptied to nothing — from either editor —
stages a mute (`{"none"}`), never a silent fall-through to the server default; only the explicit "use
server default" action stages a reset (`DatabaseNotificationPreferences.resetDataType` equivalent,
clearing that `dataType`'s rows on Apply). In `CategoryEditorDialog`, pressing Save always writes every
member `dataType`'s state for every medium shown, even ones the player didn't touch — opening a category
editor and pressing Save with no changes still marks every member `dataType` dirty and, on Apply,
converts them from "server default" to an explicit row matching whatever was already displayed.

**Three preference states per `dataType`**, expressible per-`dataType` via the medium editor or (fanned
out) via the category editor:

| State | Storage | `preferredMedia(player, dataType)` returns |
|---|---|---|
| Unconfigured | no exact rows for that `dataType` | `*` rows, else `default-media` from `settings.yml` |
| Explicit selection | one row per medium for that `dataType` | that set |
| Explicit mute | a single `medium = 'none'` row for that `dataType` | `{none}` |

`NullSink` is registered for `"none"` and returns `DELIVERED`, so a muted `dataType`'s notifications are
**consumed** rather than accumulating until expiry — a mute means "do not tell me", not "queue this for
later". `NullSink` is excluded from every checkbox list, since checking nothing already says the same
thing. `/notifications mute` and the root dialog's "Mute everything" both write one `{none}` row per
currently-known `dataType` **and** a blanket `ALL_DATA_TYPES_KEY` (`"*"`) `{none}` row, so a mute also
covers any `dataType` registered by a module installed later, and never silently no-ops on a server with
zero registered payload mappings.

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
  bypasses preferences entirely (categories were never part of the dispatch path, even before this quirk
  existed), so a muted player still receives `EssentialsMailProcessor` mail. Pre-existing; fixing it
  means converting those processors into sinks.
- The five dialog classes and the router are **unverified by automated tests** — they need a live
  server. Check them by hand with `:platform:paper-plugin:runServer`.
- `PlayerNotificationsPlugin.onEnable()` builds `NotificationCategories` twice: once from config only
  (to unblock `registerCommands()`, which constructs `PreferenceDialogRouter` before feature modules have
  registered any code-side category claims), then rebuilds it after `startModules()` and swaps it into
  `PreferenceDialogRouter` via `reloadCategories` — the same mechanism `/notifications reload` uses.
  `PlayerNotificationsPlugin.reload()` swaps the reloaded `NotificationCategories` into a freshly
  constructed `NotificationDelivery` (`NotificationDelivery` no longer takes a `NotificationCategories`
  argument at all — it dispatches directly on `dataType`) and into `PreferenceDialogRouter` (via
  `PreferenceDialogRouter.reloadCategories`, a mutable field rather than a final one), and swaps the
  reloaded `default-media` into `DatabaseNotificationPreferences` (via `reloadDefaultMedia`, a `volatile`
  field) — both without reconstructing objects other code already holds references to. A player with an
  already-open, staged `PreferenceEditSession` keeps editing against whatever `dataType` set was known
  when the session was loaded; its `dataType` keys are still valid strings to write on Apply even if the
  reload renamed or removed a category, matching how a category removed from config is already handled
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
- `PlayerNotificationPreference(playerUuid BINARY(16), dataType VARCHAR(64), medium VARCHAR(64), PRIMARY KEY(playerUuid, dataType, medium))` — one row per preferred medium **per `dataType`**, so a player's preference is set-valued within each `dataType` (`chat` + `discord-dm` for `mail` is two rows). See "Notification categories" for how `dataType` and the reserved key `*` (`ALL_DATA_TYPES_KEY`) resolve, and how the separate, display-only category concept relates.

`player-notifications.drawio` is the design source for the schema (note it uses conceptual names like `notif_key`; the DDL uses camelCase columns) — it predates the `dataType` column (originally `category`) and has not been updated.

## Testing gotchas

- `:core:test` needs a **running Docker daemon** (Testcontainers, `mariadb:11.7`). Without it the suite fails rather than skipping.
- `api`, `core`, **and `platform:paper-plugin`** declare **`testRuntimeOnly("io.papermc.paper:paper-api")`**. `compileOnlyApi` is not on the test runtime classpath, so without it tests touching Adventure/Bukkit types die at discovery with `NoClassDefFoundError: net/kyori/adventure/text/Component`.
- **Counting results: glob `*.xml`, not `TEST-*.xml`.** On Windows, Gradle shortens result filenames for `@Nested` classes to dodge the path-length limit, producing `__TEST-<hash>...` names. Several test classes here (`NotificationMapperTest`, `PlayerNotificationPreferenceTest`) put **all** their `@Test` methods inside `@Nested` inner classes, so a `TEST-*.xml` glob silently omits them and makes passing tests look like they never ran.
- `./gradlew :core:test --tests "<pattern>"` can report **BUILD SUCCESSFUL while matching nothing meaningful**. Check the result count, not the exit status.

Current baseline: **74 tests in `:core:test`, 23 in `:api:test`, 21 in `:platform:paper-plugin:test`, 52 in `:platform:discord-adapter:test`** — 170 in total, all passing. (`:platform:essentials-adapter` has no tests.)

## Current state

The project builds end-to-end; `:core:test`, `:api:test`, and `:platform:paper-plugin:test` pass. The
enqueue → deliver path is complete: `enqueueNotification` persists the notification's `notifPayloadType`;
`NotificationDelivery.deliver(UUID[, Instant])` resolves a player's due notifications, decodes each
payload through its registered `PayloadSerializer`, dispatches by the precedence rule above (directly on
`dataType`, no category resolution), and prunes targets whose processor returns
`NotificationDisposition.DELETE` (the trigger then removes notifications with no remaining targets).
Preferences are stored and resolved per `dataType` end-to-end: storage, dispatch, and the player-facing
dialogs (root, by-medium, by-category) all agree on the same `dataType` axis, with categories layered on
top purely as a player-facing display/grouping concept (a `NotificationCategoryRegistry` for code-driven
claims, merged with `categories.yml` by `NotificationCategories`, many-to-many). Known gaps / notes:
- **`NotificationCategoryRegistry` has no in-tree consumer yet.** `platform:essentials-adapter` does not
  call `claimDataType`/`registerCategory` for `essentials-mail`, so the code-registry half of the
  category system (and the two-pass rebuild-after-`startModules()` ordering in
  `PlayerNotificationsPlugin.onEnable()`) is exercised only by unit tests against a hand-built registry,
  never end-to-end by a real module through the real module class loader.
- **Only `/notifications test` calls `deliver(UUID)`.** `TestNotificationSender` is the sole caller; there is still **no join listener** — no `Listener` is registered for delivery anywhere in `platform/` (the one `Listener` that does exist, `PreferenceQuitListener`, only drops staged preference-edit sessions). So a player's notifications are delivered only when an admin manually triggers a test, never on join. Wiring delivery to a real trigger remains the open bootstrap step.
- **`ChatSink`, `DialogSink`, and the five preference dialog screens are unverified by automated tests** — they need a live server. Check them by hand with `:platform:paper-plugin:runServer`.
- **The Discord adapter's end-to-end path has never been run.** `DiscordBot`, `JdaDiscordMessenger`,
  `DiscordSrvAccountProvider` and `DiscordModule` compile and are wired, but JDA login, the DiscordSRV
  lookup, module class loading through the shaded jar, and an actual DM landing have not been verified —
  they need a real bot token and a DiscordSRV-linked account. Checklist: Task 8 of
  `docs/superpowers/plans/2026-07-29-discord-adapter.md`. `/notifications test` now exists to drive
  exactly this check — it is the intended way to run that checklist.
- **`/notifications test`'s own wiring is unverified by automated tests.** `TestNotificationSender`,
  the Brigadier `test` subcommand, the permission gate, and the `test` type appearing in the preference
  dialogs all need a live server. The path underneath them (`registerJsonRenderable` → enqueue →
  `deliver` → render → sink fan-out → prune) **is** covered, by `core`'s `RenderedDeliveryTest`.
- **Discord account linking is DiscordSRV-only, and there is no in-game link flow.** A player with no
  DiscordSRV link gets `UNSUPPORTED` from `discord-dm` forever. `DiscordAccountProvider` is the seam a
  future own-link-table or `/notifications link` flow would slot into; the table would have to live in
  `core` as a V2 migration, since `MariaSchemaMigrator` tracks one `schema_version` chain and
  `MariaDatabase` registers mappers from a hardcoded list, so a module cannot own a migration today.
- **`notifPayload` is a `JSON` column**, so payloads must be valid JSON. A payload mapped to `String.class` is therefore JSON-encoded on write and arrives at its processor still quoted — the reason every in-tree payload now owns a record instead. `DefaultNotificationService` still pre-registers a `String` serializer and nothing forbids `String.class`, so the trap is still reachable; no registration guard was added (considered and deferred — see the test-notification design doc).
- **Persisted `essentials-mail` rows predating the `EssentialsMailPayload` change will not deserialize** (they hold `"text"`, the type now expects `{"message":"text"}`). `decodePayload` logs a warning and retains them until expiry prunes them. Acceptable only because the project has no deployed data to preserve; a deployed server would have needed a payload-rewriting migration.
- **Partial delivery is silent** under the DELETE-wins fan-out — see "Rendering & delivery media".
- Target-id allocation via `MAX(id)+1` is not concurrency-safe under parallel enqueues (fine for a plugin's low write volume).
- **Rows for a category removed from `categories.yml` are kept, not pruned** — they resurface if the category is re-added, and are invisible in the dialogs meanwhile. No admin command prunes them.
- Deferred to their own designs: **Discord account linking** (the sink itself now exists), a **`discord-channel-ping` sink**, **per-medium delivery tracking**, **actions/buttons** in `RenderableNotification`, and a **player-facing inbox** (`/notifications` covers preferences only — there is no listing or player-initiated clear, and no admin commands or admin view of another player's preferences).
