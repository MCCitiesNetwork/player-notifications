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

PlayerNotifications is a PaperMC (Spigot) plugin for Minecraft **1.21.8**, targeting **Java 21**. It stores per-player notifications in a MariaDB database and delivers them to players via pluggable, payload-typed processors — or, more commonly, via the **renderer/sink** path, which fans a notification out to whichever media a player prefers (chat, dialog, Discord DM). Persistence is implemented with MyBatis in the `core` module; the Paper bootstrap and platform integrations live under `platform/`. Cross-cutting infrastructure (a runtime module system, schema migrator, Configurate helpers) comes from the external `plugin-infrastructure` library. A first-party `mail` notification type (see "Mail") is stored and read via `/mail`, but — deliberately — never delivered through any of those media.

## Build & run

Uses the Gradle wrapper (Gradle 9.3.0). On Windows, use `./gradlew` from the Bash tool or `gradlew.bat` from PowerShell.

- `./gradlew build` — build all modules. The `platform:paper-plugin` module's `build` depends on `shadowJar`, producing the shaded plugin jar under `platform/paper-plugin/build/libs/`.
- `./gradlew :platform:paper-plugin:shadowJar` — build only the distributable plugin jar.
- `./gradlew :platform:discord-adapter:shadowJar` — build the Discord adapter module jar. It must be the **shaded** (`-all`) jar: the module bundles its own relocated JDA, so the plain `jar` output has no Discord library in it at all.
- `./gradlew test` — run all tests (JUnit 5 / Jupiter).
- `./gradlew :core:test` — run the `core` persistence tests. **These require a running Docker daemon** — they spin up a real `mariadb:11.7` container via Testcontainers.
- `./gradlew :platform:discord-adapter:test` — **also requires a running Docker daemon**, for the same reason: the module owns its own schema, so its migrator, mapper and link store are tested against a real `mariadb:11.7`. The rest of that module's tests are hermetic, but the suite as a whole is not.
- `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.*SomeTest"` — run a single test class/method.
- `./gradlew :platform:paper-plugin:runServer` — launch a real Paper 1.21.8 test server with the plugin loaded (via the `xyz.jpenilla.run-paper` plugin). Server files go under `platform/paper-plugin/run/`. Needs a reachable MariaDB (see `database.yml`). It `dependsOn` `installFeatureModules`, so every feature module is built and installed first, and its `downloadPlugins` block fetches DiscordSRV `v1.30.5` from GitHub (the discord adapter's link source — without it `DiscordSrvAccountProvider` reports itself unavailable and the adapter cannot be exercised end to end).
- `./gradlew :platform:paper-plugin:installFeatureModules` — `Sync` the feature-module jars into the runServer data folder's `run/plugins/PlayerNotifications/modules/`. Modules are **not** classpath entries (the host loads them through their own `URLClassLoader`), so they are installed as files, not added to the server classpath. Adding a new adapter means adding one `featureModules(project(path = ":platform:<name>", configuration = "moduleJar"))` line to `platform/paper-plugin/build.gradle.kts`. The `Sync` owns only the top-level `*.jar` files — everything else in that directory is preserved at any depth (`preserve { include("**"); exclude("*.jar") }`), so module configs written at runtime survive, whether they are loose files (`discord.yml`, holding the bot token) or a module's own config subdirectory.

## Module architecture

Gradle build with `api`, `core`, and two platform modules (`settings.gradle.kts` includes `api`, `core`, `platform:paper-plugin`, `platform:discord-adapter`). Type-safe project accessors are enabled (`enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`), so build scripts reference `projects.api`, `projects.core`, etc. Dependency direction flows **platform → core → api**; `api` depends on nothing but Paper.

- **`api`** (`io.github.md5sha256.playernotifications.api`) — Public, dependency-light API. Uses `compileOnlyApi` for `paper-api`. Key types:
  - `NotificationService` — entry point: `enqueueNotification`, `resolveNotifications(UUID)` (a pure read — does **not** delete), `clearNotification`/`clearNotifications`, `deleteNotificationTarget(key, UUID)` / `deleteNotificationTargets(key, Collection<UUID>)`, `clearExpiredNotifications`, and `dataTypeRegistry()`.
  - `NotificationDataTypeRegistry` — maps a string `dataType` → payload `Class<?>`, and payload class → `NotificationProcessor`, `PayloadSerializer`, and `NotificationRenderer`. The extension point: callers register their own payload types, processors/renderers, and serializers.
  - `NotificationSinkRegistry` — separate registry keyed by **medium** (`"chat"`, `"dialog"`, `"discord-dm"`), not by data type. `registerSink` keys off `NotificationSink#mediumKey()`. `"discord-channel-ping"` is **reserved but unimplemented** (see "Discord adapter").
  - `Notification` / `ResolvedNotification` — records for the persisted vs. target-resolved forms. `ResolvedNotification` holds a `NotificationTarget` (list of player UUIDs) and carries `notifPayloadType` (the registry data-type string) plus the `String` payload.
  - **`api.processor`** package — `NotificationProcessor<T>` is a pure `@FunctionalInterface`: `NotificationDisposition receiveNotification(T payload, UUID target)` — it processes **one target (audience member) per call** and returns whether the notification should be `RETAIN`ed or marked `MARK_SEEN` for that target. Composition lives in `NotificationProcessorBuilder` (fluent "chop-down" chaining via `andThen`/`andThenIf`/`onComplete`, folding dispositions with MARK_SEEN-wins). `FixedDelayProcessor` wraps a processor with a scheduled delay.
  - **`api.render`** package — the renderer/sink architecture (see "Rendering & delivery media" below): `RenderableNotification` (medium-neutral `Component` title + body), `NotificationRenderer<T>` (payload → `RenderableNotification`, one per payload type), `NotificationSink` (`RenderableNotification` → a medium, one per medium, returning a `DeliveryResult`), `DeliveryResult` (`DELIVERED` / `UNREACHABLE` / `UNSUPPORTED`), `NotificationPreferences` (`UUID` → `Set<String>` of preferred media, plus a `default` two-argument `preferredMedia(UUID, String dataType)` overload — resolved directly against the payload's `dataType`, with no category involved in dispatch; see "Notification categories" for the separate, display-only category concept), and `RenderingProcessor<T>` — the single framework-supplied processor that binds them, constructed with the `dataType` it dispatches for (`@NotNull`, required). `NotificationSink` also carries `default` `displayName()` / `description()` `Component`s used to label media in player-facing UI (`displayName()` title-cases `mediumKey()`, so `discord-dm` → "Discord Dm"). `api.render.sink` holds `ChatSink` and `DialogSink`. The `"none"` medium backing an explicit **per-`dataType`** mute is **not** a sink: it is the constant `NotificationPreferences.MUTED_MEDIUM`, which `RenderingProcessor` filters out of the resolved media set. Separately, `NotificationPreferences` carries a `default boolean isMuted(UUID)` — the player-level do-not-disturb flag, orthogonal to the media matrix and enforced in `NotificationDelivery.deliver` rather than here. See "Global mute" and "Player commands".
  - **`api.category`** package — `NotificationCategoryRegistry` (`DefaultNotificationCategoryRegistry` the in-memory impl) lets module authors declare categories and claim `dataType`s under them in code, exactly like payload types/processors/renderers/sinks are registered. Exposed via `NotificationService#categoryRegistry()`. Merged at read time with `categories.yml` by `core.category.NotificationCategories` — see "Notification categories".
  - `InboxEntry` / `InboxPage` — one notification as it appears in a player's inbox (stored payload plus that viewer's `seenTime`, with `unread()`), and one page of them (`entries`, `page`, `pageSize`, `totalEntries`, `unreadCount`, `totalPages()` at least 1). See "Notification inbox".
  - **`api.link`** package — `AccountLinkProvider` (`providerKey`, a `default` title-casing `displayName()`
    plus the static `defaultDisplayName(key)` the host uses to name an *unregistered* key, and
    `begin`/`status`/`unlink` returning the player-facing `Component` rather than sending it) and
    `AccountLinkRegistry` (synchronized map, keys normalised to lower case on both registration and
    lookup). Exposed via `PlayerNotificationsPlugin.accountLinkRegistry()` — deliberately *not* on
    `NotificationService`, which has impls in `core` and in tests and no stake in account linking. See
    "Player commands".
  - **`api.serialize`** package — `PayloadSerializer<T>` (JSON string ↔ `T`) and `PayloadSerializationException`. The only serialization type crossing the API boundary; the JSON library stays an implementation detail of whoever supplies the serializer.
- **`core`** (`io.github.md5sha256.playernotifications.core`) — MyBatis persistence, `DefaultNotificationService`, `NotificationDelivery` (the delivery loop, dispatches directly on `dataType`, with no category resolution), `DatabaseNotificationPreferences` (the persisted, `dataType`-keyed `NotificationPreferences` impl), `category.NotificationCategories` (a read-only display/grouping merge — see "Notification categories"), and `serialize.JacksonPayloadSerializer`. `api("org.mybatis:mybatis")`, `api("org.spongepowered:configurate-yaml")`, `implementation("org.mariadb.jdbc:mariadb-java-client")`, `paper-api` compileOnly **plus `testRuntimeOnly`** (see "Testing gotchas"). See "Persistence layer" below.
- **`platform:paper-plugin`** (`io.github.md5sha256.playernotifications.paper`) — Paper bootstrap. `PlayerNotificationsPlugin.onEnable` loads config (including `categories.yml`), builds a `MariaDatabase`, runs schema migration, constructs `DefaultNotificationService`, registers it under `NotificationService.class` in the Bukkit `ServicesManager`, builds the `NotificationSinkRegistry` (registering `ChatSink` and `DialogSink`), `DatabaseNotificationPreferences`, and `NotificationCategories`, constructs `NotificationDelivery` with all three, registers the Brigadier commands, an `InboxRouter` with its `InboxQuitListener`, a `PreferenceQuitListener` and a `JoinDeliveryListener`, schedules the async prune task, starts the module system, and finally warns about any `categories.yml` data type with no registered payload mapping. Exposes `database()` / `notificationService()` / `sinkRegistry()` / `preferences()` / `categories()` / `notificationDelivery()` accessors for modules. Applies `shadow` (relocating `org.mariadb`, `org.mybatis`, `org.apache.ibatis`, `org.spongepowered`, `io.leangen.geantyref`, `com.fasterxml.jackson`) and `run-paper`. Also declares `testRuntimeOnly("io.papermc.paper:paper-api")` (see "Testing gotchas") — needed once its own tests started touching Adventure/Bukkit types.
- **`platform:discord-adapter`** (`io.github.md5sha256.playernotifications.discord`) — a **feature module** that delivers notifications as Discord DMs. `DiscordModule` (the manifest entry class) registers a `DiscordDmSink` under medium key `discord-dm` — a **sink**, not a processor, so (unlike the mail RETAIN processor described under "Mail") it participates in preferences and fan-out like any other notification type. It also **owns its own schema**: the `discord.schema` subpackage holds its migrator, migration script, entity and mappers, and `core` knows nothing of Discord. Applies `paper-adapter` plus `com.gradleup.shadow`, bundling its own relocated JDA. See "Discord adapter" below.

### Build conventions

`buildSrc/src/main/kotlin/` holds precompiled convention plugins:
`buildSrc` also holds `HostShading.kt` — the host's relocation prefix and package list, shared so that
the host's `shadowJar` and every shading adapter cannot drift apart. See "Discord adapter" for why an
adapter must relocate packages it does not bundle.

- `player-notifications-conventions` — Java 21 toolchain, UTF-8, JUnit 5, and the Paper / mavenLocal / mavenCentral repos. Applied by every module.
- `paper-adapter` — for feature-module projects. Applies the base conventions, adds `compileOnly(project(":platform:paper-plugin"))` (so adapters compile against the host but never bundle it — the module class loader resolves host classes at runtime) **plus `testImplementation` on the same project** (`compileOnly` reaches neither `compileTestJava` nor the test runtime, so an adapter's own tests would not see `api`/`core` or Adventure types at all), and adds the `maven.democracycraft.net/snapshots` repo. It also declares a **consumable `moduleJar` configuration** whose artifact is the module's deliverable jar — `jar` by default, swapped to `shadowJar` under `plugins.withId("com.gradleup.shadow")` (lazily, since an adapter applies shadow *after* the convention), so a shading adapter such as `discord-adapter` publishes its `-all` jar. `platform:paper-plugin`'s `installFeatureModules` resolves that configuration; the host therefore never needs to know which adapters shade. Because `paper-plugin` exposes `api`/`compileOnlyApi` dependencies, this single dependency transitively provides `api`, `core`, `plugin-infrastructure`, and `paper-api` to adapters.

Note: precompiled conventions apply sibling conventions with `id("player-notifications-conventions")`, not the backtick accessor. Project version comes from the root `gradle.properties`.

### plugin-infrastructure dependency

`com.minecraftcitiesnetwork:plugin-infrastructure` (from `https://maven.democracycraft.net/snapshots`, see its README) provides `com.minecraftcitiesnetwork.pluginInfrastructure.modules` (the module system), `.sql` (schema migration primitives), `.configurate`, and `.util`. Consumed as a normal repo dependency — **not** an `includeBuild`. (The coordinate's group id and the repo host's name disagree — `minecraftcitiesnetwork` vs. `democracycraft` — that is the upstream project's naming, not a typo here; verify against an actual import, e.g. `PlayerNotificationsPlugin.java`'s `com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLoader`, before trusting either half of it again.)

## Module system

Feature modules are jars dropped into `<dataFolder>/modules/`, each containing a `module-manifest.yml` and an entry class implementing `PluginModule<T extends Plugin>` (or extending `SimplePluginModule`). `PlayerNotificationsPlugin` drives them with a `ModuleLifecycleManager` (`start()` on enable after the service is registered, `stop()` first on disable). A module's `initialize` receives the host plugin and typically resolves `NotificationService` from the `ServicesManager` to register processors.

The manifest keys are the `ModuleManifest` record's component names in **kebab-case**. Configurate's
`ObjectMapper` uses `NamingSchemes.LOWER_CASE_DASHED` by default, so `moduleName` reads the key
`module-name`, **not** `moduleName`:

```yaml
module-name: discord-adapter
entry-class: io.github.md5sha256.playernotifications.discord.DiscordModule
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

**A module can own its own schema.** It cannot add a step to `MariaSchemaMigrator.DEFAULT_MIGRATIONS` or a
mapper to `MariaDatabase`'s hardcoded list — both are fixed — but that does not stop it owning persistence,
because `SqlSessionWrapper#session()` exposes everything needed:

| Need | API |
|---|---|
| A connection for its own DDL | `session().getConnection()` |
| Register its own mapper | `session().getConfiguration().addMapper(...)`, guarded by `hasMapper` |
| Use it | `session().getMapper(...)` |
| `UUID` ↔ `BINARY(16)` | already registered on the shared `Configuration` |

`platform:discord-adapter` does exactly this: its own `V*.sql`, its own migrator, its own
`discord_schema_version` chain, all over the **host's** pool and session factory, so there is no second
pool and no second copy of the credentials. Three rules if you follow it: load scripts through **the module's
own** class loader (core's cannot see inside a module jar); remember that registering a mapper mutates
the host's shared `Configuration`, which is the narrowest scope MyBatis offers; and **relocate MyBatis to
the host's shaded prefix in the module's own `shadowJar`** (`HostShading.ADAPTER_PACKAGES`), or every one
of those calls and every mapper annotation names a class that does not exist at runtime — see
"Discord adapter".

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
always wins (so a bespoke processor keeps working unchanged — including bypassing preferences
entirely). **One in-tree type deliberately uses this**: `mail` registers a `RETAIN`-always processor
so it is never pushed through any sink — see "Mail". Otherwise a registered `NotificationRenderer` dispatches
through `RenderingProcessor`, built with the notification's `notifPayloadType` as its `dataType`;
otherwise the notification is logged and retained. `NotificationDelivery` has a 3-arg constructor (no
rendering path) and a 5-arg one (rendering) — categories play no role in delivery.

**Fan-out is MARK_SEEN-wins:** if any sink returns `DELIVERED`, the notification is marked seen for that
target — **not** deleted. It stays readable in the player's inbox until dismissed or expired; see
"Notification inbox". `selectDueByPlayer` carries `AND t.seenTime IS NULL`, so a seen notification is
never pushed again. Consequences:
- **Partial delivery is still silent, but no longer lossy.** If a player prefers `chat + discord-dm`,
  chat succeeds and Discord transiently fails, the notification is marked seen and Discord never receives
  it — but it remains readable in the inbox. Per-medium delivery tracking is still deferred; retrying is
  *not* a workaround, since chat is not idempotent.
- When **nothing** was delivered and at least one medium returned `UNSUPPORTED`, a `warning` is logged
  (a player whose only preferred medium is permanently unreachable would otherwise sit on unread
  notifications until expiry with no operator-visible signal).
- **`RenderingProcessor` drops `MUTED_MEDIUM` from the resolved set** and returns `RETAIN` if nothing
  deliverable remains, leaving the notification **unread** in the inbox. This is the **per-`dataType`**
  mute only; the player-level mute never reaches here, because `NotificationDelivery.deliver` returns
  before dispatching (see "Global mute"). Either way a mute means "do not interrupt me", not "do not
  tell me".
- A sink throwing a `RuntimeException` is caught, logged, and treated as `UNREACHABLE`, so one broken
  sink cannot abort delivery to the others.

`RenderableNotification` holds **no `Player` and no `Audience`** — Discord DM delivers to
absent players. `Component` is the lingua franca; non-Minecraft sinks serialize it down, so bodies must
not rely on in-game-only affordances such as click events. Actions/buttons are intentionally **out of
scope** (a dialog button and a Discord button share no execution model), so dialogs render as
read-and-dismiss.

### Join delivery

Design doc: `docs/superpowers/specs/2026-07-30-join-delivery-trigger-design.md`.

`paper.JoinDeliveryListener` delivers a joining player's due, **unseen** notifications, gated by
`deliver-on-join` and delayed by `join-delivery-delay-seconds` (see "Configuration"). It also sends one
line naming the player's unread count and pointing at `/notifications`, **outside** the
`deliver-on-join` gate and its delay — a player who turned `deliver-on-join` off still needs to know
something arrived, which is the whole point of separating push from the inbox. A zero count sends
nothing, and so does a **player-level mute**: the announcement lines live behind a package-private
`announcements(UUID) : List<Component>` seam that returns an empty list when
`NotificationPreferences#isMuted` is true, which is also what makes the gate unit-testable without a
live `Player`. The listener takes a `NotificationPreferences` for exactly that check. It is a
**trigger** for the existing delivery loop, the same kind of thing as the async prune task — not a
registry extension, which is why it lives in the Paper bootstrap and not behind
`NotificationSinkRegistry`. A toggle inside `NotificationDelivery` was rejected: `core` has no Bukkit
event surface, and a flag there would also gate `/notifications test`, which must keep working
regardless.

- **Always registered, gated internally.** `onJoin` returns early when disabled rather than the
  listener being registered conditionally, so `/notifications reload` can flip the toggle through
  `reloadSettings(boolean, long)` without `HandlerList` surgery. `enabled`/`delaySeconds` are `volatile`
  fields — the same reload idiom as `DatabaseNotificationPreferences.reloadDefaultMedia` and
  `PreferenceDialogRouter.reloadCategories`.
- **Delivery is scheduled async** (`runTaskAsynchronously` when the delay is `0`, else
  `runTaskLaterAsynchronously` with `delaySeconds * 20` ticks): the mappers and preference lookups do
  blocking JDBC, and `DiscordDmSink` refuses the main thread outright.
- **The scheduled body re-checks `isOnline()` and the toggle.** An offline `Audience` still makes
  `ChatSink` report `DELIVERED`, so under the MARK_SEEN-wins fan-out delivering to a player who quit
  mid-delay would mark the notification seen without anyone reading it. Re-reading the toggle means a reload that turns
  the trigger off cancels a delivery already waiting out its delay.
- A `RuntimeException` from `deliver` is logged at `WARNING` and swallowed — an uncaught throw in a
  scheduled task is reported by Bukkit with no useful attribution, and nothing is sent to the player,
  who did not ask for a diagnostic.
- **The delay is not cancellable.** Join → quit → rejoin inside the window schedules two tasks; the
  second finds nothing due and no-ops. Two queries rather than one, judged not worth a per-player
  pending-task map.
- **`onJoin` itself is unverified by automated tests** — `PlayerJoinEvent` and the scheduler need a live
  server. `JoinDeliveryListenerTest` covers the gate and the reload swap, which is why the gate lives in
  a package-private `deliver(UUID)` rather than inline in the handler. Manual checklist: Task 3 of
  `docs/superpowers/plans/2026-07-30-join-delivery-trigger.md` — **not yet run**.

### Discord adapter

Design doc: `docs/superpowers/specs/2026-07-29-discord-adapter-design.md`.

`platform:discord-adapter` is a feature module registering `DiscordDmSink` under medium key
**`discord-dm`**. Once the jar is in `<dataFolder>/modules/` and configured, "Discord DM" appears
automatically in the `/notifications preferences` dialogs — they enumerate `sinkRegistry().registeredMedia()`, so
no host change was needed. `DiscordMedia.CHANNEL_PING` (`"discord-channel-ping"`) is **reserved but
unimplemented**: nothing registers it, so it can never be selected; the key exists so the DM sink is
not squatting on a generic `"discord"` name and a channel sink can be added later without migrating a
single preference row.

- **The module owns its own schema.** `sql/discord/V1__discord_account_link.sql` (in the *module* jar),
  `schema.DiscordSchemaMigrator`, `schema.DiscordMigrationStep`, its own `discord_schema_version` version
  table, and its own entity/mappers in the `schema` subpackage. `core` contains no Discord-shaped type,
  table or migration — `grep -ri discord core/src/main` returns nothing, and a server without this module
  has no `DiscordAccountLink` table. Only the **connection** is borrowed, via `plugin.database()`; the
  host's pool, session factory and `UUID` type handler are all reused. `DiscordModule.initialize` runs the
  migration **before** constructing the store, and a failure fails module startup — an adapter whose table
  is missing is worse than no adapter. Two traps this hit: scripts load through *this module's* class
  loader, and `--` line comments are stripped before splitting on `;`, because a `;` inside a comment
  otherwise splits the script mid-sentence. See "Module system" for the general pattern.
- **DiscordSRV is genuinely optional.** `link-providers` defaults to `[embedded, discordsrv]`;
  `embedded` resolves the plugin's own `DiscordAccountLink` table and needs no other plugin, so a server
  with DiscordSRV absent still links and delivers. Listing both is the migration setting: new links land
  in the embedded table and win, old DiscordSRV-only links keep resolving.
- **The link flow.** `/notifications link discord` (plus `… discord status` and the sibling
  `/notifications unlink discord`) issues a
  6-character single-use code; the player sends `/link <code>` to **this module's bot** in Discord, and
  `LinkSlashCommandListener` writes the row. `DiscordLinkFlow` holds every decision and message — the
  `DiscordAccountLinkProvider` and the JDA listener are deliberately logic-free adapters over it, because
  neither can be unit tested. `DiscordLinkFlow.LINK_COMMAND` / `UNLINK_COMMAND` are the single source for
  the command names every reply quotes, chat and Discord alike. Codes live in `LinkCodeService`, in memory only: a code's lifetime is minutes, so a
  restart invalidating one costs a re-run, whereas persisting it would mean a migration for state designed
  to expire. `redeem` folds expiry into `UNKNOWN_CODE` — it removes the expired entry, so nothing remains
  to tell the two apart.
- **Redemption is a slash command, not a DM read.** Interactions require no gateway intent, privileged or
  otherwise, so `DiscordBot` keeps `createLight` with an empty intent set; reading DM message *content*
  would have needed the privileged `MESSAGE_CONTENT` intent. `DiscordBot.start` therefore takes event
  listeners, attached at build time so a listener cannot miss the ready event — the command is registered
  from `onReady`, since `awaitReady()` is never called. The command is global with
  `setContexts(BOT_DM, GUILD)`.
- **Linking is a registration, not a command.** `DiscordAccountLinkProvider` (a thin delegate over
  `DiscordLinkFlow`) is registered in the host's `AccountLinkRegistry` under
  `DiscordMedia.LINK_PROVIDER_KEY` (`"discord"`) — only when `link-providers` lists `embedded`, since a
  DiscordSRV-only server has nothing for a code to redeem into. The module owns **no** command and **no**
  permission: the host's `/notifications link|unlink` subtree already exists and is gated by
  `playernotifications.command.link` (on top of the root's `playernotifications.command.preferences`).
  `shutdown` just unregisters the provider (flag-guarded, so
  it is idempotent); the command node stays and the host replies "Discord linking is not available on
  this server", which is also what a server without this module shows. This replaced a module-owned
  `/discordlink` whose teardown had to go through `Bukkit.getCommandMap().getKnownCommands()` — that
  surgery, and the programmatic `playernotifications.discord.link` permission, are both gone.
- **A chain with nothing available now warns at startup.** `ChainedDiscordAccountProvider.reportAvailability`
  logs one line per configured provider and a `WARNING` naming `discord-dm` when none can answer. That case
  previously produced no log at all until a notification was dropped.
- **It runs its own JDA bot with its own token.** Legacy DiscordSRV relocates its bundled JDA to
  `github.scarsz.discordsrv.dependencies.jda.*`, so its instance is *not* type-compatible with upstream
  `net.dv8tion`. DiscordSRV is therefore a **link source only** — nothing is ever sent through it.
- **The shading is load-bearing, in both directions.** `shadowJar` relocates every *bundled* package
  under `io.github.md5sha256.playernotifications.discord.libraries`. Modules load through
  `new URLClassLoader(jarUrl, hostClassLoader)` — parent-first — and the host already shades Jackson,
  so an unrelocated copy would collide. **Verify the relocation set against the built jar** after any
  dependency bump (`unzip -l ...-all.jar` and look for classes outside `io/github/md5sha256/`); JDA 6's
  transitive set is not JDA 5's.
  It also relocates packages it does **not** bundle — MyBatis and Configurate — to the *host's* prefix,
  because it compiles against the unrelocated coordinates (transitively, compile-only, through the host
  project) but resolves them at runtime from the host jar, where only the relocated names exist. Without
  that rewrite the module fails on startup: a descriptor mentioning a relocated type is a different
  method (`NoSuchMethodError: SqlSessionWrapper.session()`), and `MariaDiscordAccountLinkMapper`'s
  `@Select` would not be the annotation the host's MyBatis looks for. The relocation set is therefore
  **not** duplicated in the two build scripts — `HostShading` in `buildSrc` owns it, the host applies
  `PACKAGES`, an adapter applies `ADAPTER_PACKAGES` (the same list minus Jackson, which the module
  bundles its own copy of). Adding a shaded library to the host means adding one entry there.
- **`paper-plugin.yml` carries a soft `dependencies: server: DiscordSRV` with `join-classpath: true`.**
  Paper plugins are classloader-isolated by default, and the module's loader is parent-first onto the
  *host's*, so without that entry `DiscordSrvAccountProvider` silently reports itself unavailable.
- **The provider swap point.** `DiscordAccountProvider` (`providerKey`, `discordIdFor`, `isAvailable`)
  resolves UUID → Discord id. `discord.yml`'s `link-providers` is an ordered key list resolved against
  `DiscordAccountProviderRegistry` and wrapped in `ChainedDiscordAccountProvider` (first link wins;
  unknown key warned and skipped; unavailable skipped un-queried; a throwing provider logged and
  skipped so it cannot mask a working one). Two providers ship: `EmbeddedDiscordAccountProvider`
  (`embedded`, over the plugin's own table via `DiscordAccountLinkStore`, always available) and
  `DiscordSrvAccountProvider` (`discordsrv`, available only when DiscordSRV is). Adding a provider is one
  class, one registration and one config line — `DiscordDmSink` never changes, and did not change when
  `embedded` was added.
- **Rendering.** `DiscordMarkdownSerializer` drives Adventure's `ComponentFlattener` to Discord
  markdown (bold `**`, italic `*`, underlined `__`, strikethrough `~~`, obfuscated → spoiler `||`),
  escaping `` \ * _ ~ | ` > `` and dropping colours — Discord message text cannot be coloured.
  `DiscordMessageFactory` builds the `MessageCreateData` in one of three `message-format`s
  (`embed` | `markdown` | `plain`) and truncates to Discord's limits (title 256, description 4096,
  content 2000) *before* JDA's builders, which throw on overlong input rather than trimming.
- **Shutdown blocks on purpose.** `DiscordBot.shutdown()` calls `jda.shutdown()` and then
  `awaitShutdown` (5s, escalating to `shutdownNow`). `shutdown()` alone only *requests* teardown; JDA's
  websocket reading thread then lazily loads more classes (`WebSocketClient.onShutdown`), and if the
  module's `URLClassLoader`/host jar is already closed that fails with `IllegalStateException: zip file
  closed` on disable. Do not make this fire-and-forget again.
- **An unloaded DiscordSRV poisons this module's class loading — known, unfixed, operational.** The
  *other* `IllegalStateException: zip file closed`, and the one you will actually be shown. Symptom: a
  stack trace on a healthy, running server, blaming our relocated JDA
  (`…discord.libraries.net.dv8tion.jda…WebSocketClient.handleDisconnect`) with
  `PluginClassLoader.findClass` for **DiscordSRV** underneath it. Read it as delegation, not use — the
  failing class is ours, and DiscordSRV's own JDA relocates to `github.scarsz.…`, so the two can never
  collide. The chain: `join-classpath: true` puts DiscordSRV's `PluginClassLoader` in our classloader
  group → the module's loader is parent-first, so *every* class it loads is offered to that group first
  → if DiscordSRV was **unloaded** (not merely disabled — a plain disable leaves the loader open; a
  plugin manager or `/reload` closes it) its loader answers a routine "not found" with a thrown
  `IllegalStateException`, which aborts the load instead of falling through to the module's own jar.
  Notes, each of which cost a session to establish:
  - **Independent of `discord.yml`.** The group is built from the declared dependency and the jar's
    presence, before any config is read. It fires with `link-providers: [embedded]` and
    `DiscordSrvAccountProvider` never constructed.
  - **The class being loaded is irrelevant** — JDA only appears because a gateway reconnect (which
    Discord initiates on its own, hence "I wasn't doing anything") was the next thing needing a class
    not yet loaded. It breaks host-class loads the same way, and keeps firing on every reconnect.
  - **Fix: remove the DiscordSRV jar from `plugins/`.** DiscordSRV is now the only `dependencies:
    server:` entry in `paper-plugin.yml`, and so the only source of this exposure — the Essentials
    entry that used to carry the identical risk was removed with the Essentials adapter (see "Mail").
  - Two code fixes were designed and **deliberately not taken**: dropping `join-classpath` and driving
    `DiscordSrvAccountProvider` reflectively through DiscordSRV's own plugin loader (the only fix that
    covers host-class loads too — the surface is three calls returning `String`/`UUID`, so it is cheap),
    and a fallback in `plugin-infrastructure`'s `ModuleLoader.loadModule` where the module's
    `URLClassLoader` retries its own jar when the parent chain *throws*. Reopen either only with the
    user; do not treat this entry as a TODO.
- **Result mapping.** No linked account → `UNSUPPORTED` (the exact case that result's javadoc names);
  `CANNOT_SEND_TO_USER`/`UNKNOWN_USER` → `UNSUPPORTED`; not connected, rate-limited, timed out, or any
  other exception → `UNREACHABLE`. `DiscordDmSink` also refuses to run on the main thread (warn +
  `UNREACHABLE`), since it blocks on a Discord round trip; the delivery loop is already async.
- **`discord.yml`** (bundled in the *module* jar, written to `<dataFolder>/modules/discord.yml`):
  `bot-token` (blank refuses module startup), `message-format`, `embed-color` (`#RRGGBB`),
  `delivery-timeout-seconds` (`long`, not a `Duration`), `link-providers`, `link-code-expiry-seconds`
  (`long`, default 600, clamped like the timeout). Loaded by `ModuleConfigs`, which reproduces the host's
  copy-defaults-then-merge idiom because `PlayerNotificationsPlugin.copyDefaultsYaml` is private and reads
  the *host* jar's resources. Because merge only *adds* absent keys, an existing install keeps its old
  `link-providers` — an upgrade does not silently switch a server onto `embedded`.
- **Untested by automated tests:** `DiscordBot`, `JdaDiscordMessenger`, `DiscordSrvAccountProvider`,
  `DiscordModule` and `LinkSlashCommandListener` — they need a live server, a real bot token and a Discord account.
  Two manual checklists exist and **neither has been run**: Task 8 of
  `docs/superpowers/plans/2026-07-29-discord-adapter.md` and Task 7 of
  `docs/superpowers/plans/2026-07-30-embedded-discord-linking.md`. Everything else in the module is unit
  tested — including `DiscordLinkFlow`, which is where the link logic deliberately lives for that reason.

## Mail

Design doc: `docs/superpowers/specs/2026-08-10-first-party-mail-design.md`.
Plan: `docs/superpowers/plans/2026-08-10-first-party-mail.md`.

First-party, player-to-player mail (`/mail send <player> <message>`, `/mail` to read) replaced the
`platform:essentials-adapter` module outright (deleted, see git history around this commit). Mail is
a `dataType` (`"mail"`, `api.mail.MailPayload.DATA_TYPE`) — a payload, not a medium — and it reuses
the existing notification machinery (storage, targeting, paging, seen/unread tracking, pruning-minus-expiry)
rather than a bespoke table.

**Mail is stored, but never delivered — on purpose.** `PlayerNotificationsPlugin.onEnable` registers a
processor for `MailPayload` that always returns `RETAIN`:

```java
service.dataTypeRegistry().registerProcessor(MailPayload.class,
        (payload, target) -> NotificationDisposition.RETAIN);
```

This exploits the dispatch-precedence rule in "Rendering & delivery media": an explicitly registered
`NotificationProcessor` wins over the renderer/sink path and bypasses preferences entirely. For every
other in-tree type that precedence is a *quirk* (see the "known quirk" note under "Player commands");
for mail it is the required behaviour, because nothing about a mail's *content* should ever be pushed
through chat or Discord — the whole point of the feature is that mail waits until the player asks for
it with `/mail`. **Do not "simplify" this processor away or fold it into the renderer path** — doing
so would push mail content through sinks the moment it is sent.

`RETAIN` rather than `MARK_SEEN` matters too: the delivery loop never stamps `seenTime` for a mail, so
a mail becomes seen only when the player actually opens it in `/mail`. If it were marked seen on
delivery, the unread count and the bold-unread row in `/mail` would silently lie.

**A `MailRenderer` is still registered** (`registerJsonRenderable(MailPayload.DATA_TYPE,
MailPayload.class, new MailRenderer())`, alongside the RETAIN processor) — not for delivery, which the
processor above short-circuits before a renderer is ever reached, but because `InboxEntryRenderer`
(see "Notification inbox") resolves payload → renderer on **read**, independent of the delivery path.
Without a renderer, `/mail` would show the "unrenderable payload" placeholder instead of the mail.
`MailRenderer` titles the notification `"Mail from <senderName>"` with `Component.text(...)` — a player
name is not a formatting document, and parsing it would smuggle tags past the message's permission gate
— and renders the message as the body **with `MiniMessage.miniMessage().deserialize(...)`**. That
inverts the original rule (the body used to be `Component.text` precisely because it is
player-supplied): the stored string is not raw player input any more, it is what
`MailFormatting.sanitize` produced at send time from only the tags the sender held a permission for,
with everything else escaped — see "Mail formatting". The parse is guarded all the same: MiniMessage
*throws* on a legacy section-sign code, and a mail stored before this change can contain one, so a
`RuntimeException` falls back to the message as literal text, exactly how such a mail read before. It
ignores the `target` argument; a mail reads the same to everyone (in practice, its one recipient).

**The mail preference rows are real, but they route the arrival *notice*, not the mail.** A processor
bypasses `NotificationPreferences` entirely, so a `mail`-keyed preference row cannot affect the mail
itself. `categories.yml` ships a `mail` category all the same (label "Mail", claiming the `mail`
`dataType`), and its Chat / Discord DM checkboxes in `/notifications preferences types` answer a real,
different question: "where do I want to be told that mail arrived". Muting `mail` means "don't tell me
when mail arrives" — the mail still lands in the inbox, unread, waiting; it does not mean "don't
deliver my mail", since mail is never delivered to begin with.

**The arrival notice** is exactly one line, verbatim, never templated with a sender, count or preview:

> **You have new mail!**

It is **not** a notification — never enqueued, never stored (a stored notice would sit in the inbox as
a second row announcing the first) — but it *is* routed through the ordinary sink machinery, so it
reaches whatever media the recipient prefers for `mail`. `paper.mail.MailNotifier` is the whole of
this: it returns immediately when the recipient is **globally muted**
(`NotificationPreferences#isMuted`), and otherwise resolves
`preferences.preferredMedia(recipient, MailPayload.DATA_TYPE)`, drops
`NotificationPreferences.MUTED_MEDIUM`, and delivers a fixed `RenderableNotification` to each medium's
sink, catching and logging a throwing sink so one broken sink cannot suppress the others. It
deliberately does **not** reuse `RenderingProcessor` — that class exists to render a *stored*
notification's payload and report a `NotificationDisposition` back to the delivery loop, and the
notice has neither a payload nor a disposition; sharing it would mean inventing a fake notification to
carry a constant string. The notice fires from two places: `MailCommand` after a successful `send`
(unconditionally, not gated on the recipient being online — a Discord DM reaches them either way), and
`JoinDeliveryListener`, which adds a mail-specific line driven by the filtered
`unreadCount(playerId, MailPayload.DATA_TYPE)`, sending nothing when it is zero, sitting alongside the
listener's existing unread-count line and **outside** the `deliver-on-join` gate and its delay for the
same reason that line already is. A player can therefore hear about the same unread mail twice (a
Discord DM at send time, a reminder on next join) — accepted, since both are true statements about
mail that is genuinely still unread.

**Sending.** `paper.mail.MailSender.send(sender, senderName, recipient, message)` builds and enqueues
a `MailPayload` notification: key `"mail-" + UUID.randomUUID()` (a per-send random key, since two
concurrent sends must not collide), `notifExpiryTime` **`null`** — every other in-tree notification is
transient, but mail is correspondence, and correspondence evaporating on a prune tick is a bug report
waiting to happen (the cost is an unbounded inbox for mail, same category as the general inbox's
unbounded-size limitation — see "Notification inbox"). `senderName` is captured at send time and
stored in the payload rather than looked up at render time, because `MailRenderer` runs on read,
potentially long after the send, and `Bukkit.getOfflinePlayer(uuid).getName()` is a blocking lookup
that returns `null` for a player the server has never seen; the `sender` UUID is kept alongside for a
possible future reply command. `MailSender` does no validation itself — that is `MailCommand`'s job, so
a module calling `MailSender` programmatically gets the mail sent rather than an exception thrown
across a module boundary.

**`paper.mail.MailRecipients.resolve(name, message, resolver[, formatter])`** is the testable half of `/mail send`'s
argument handling, taking a `Function<String, UUID>` resolver seam (the same device
`TestNotificationRenderer.usingServerNames()` uses) so its rules are unit-testable without a server: an
unknown name is rejected; a blank or over-`MailPayload.MAX_MESSAGE_LENGTH` (256-character) message is
rejected **without truncation** (silently dropping the end of someone's sentence would be worse than
asking them to shorten it). `MailCommand` resolves the recipient itself on the async dispatch thread —
an online player by name first, else `Bukkit.getOfflinePlayer(name)` accepted only when
`hasPlayedBefore()` (rejecting a never-joined name matters because `getOfflinePlayer(String)`
fabricates a UUID for any string at all) — then calls `MailRecipients.resolve` for the message rule. The
four-argument overload additionally runs a `UnaryOperator<String>` over the trimmed message — in
production `MailFormatting.sanitize` — **after** the length check, so `MAX_MESSAGE_LENGTH` bounds what
the sender typed rather than what it serialises to, and rejects a formatter result that is blank
(`<red>` alone carries no readable text, and `MailPayload` refuses a blank message). The three-argument
form delegates with `UnaryOperator.identity()`, so a programmatic caller is unchanged.

### Mail formatting

`/mail send`'s message is **MiniMessage**, with **one permission per tag group** under
`playernotifications.command.mail.format.` (`paper.mail.MailFormatting.PERMISSION_PREFIX`).
**Every one of the fifteen is `default: op`** — a formatted mail is something an operator opts a rank
into, cosmetic tags included, so an ordinary player's message stays literal text until a server says
otherwise. The split is fine-grained anyway, so a server can grant exactly the cosmetic groups
(`color`, `decoration`, `gradient`, `rainbow`, `reset`, `newline`) without also handing out `click` and
`hover`, which attach a runnable command or a payload to text landing in someone else's inbox, or
`score`/`nbt`, which pull server-side state into it; `font` sits with those because a client-side font
can render text misleadingly. `MailFormatting.Group` deliberately carries no "granted by default"
component — it would be the same value fifteen times — and `MailFormattingTest` asserts the defaults
against `paper-plugin.yml` itself instead.

**Parsing happens at send time, not at render time**, because the permission check needs the *sender*
and a renderer runs on read, long after, with the sender possibly gone. `MailCommand` builds the
resolver with `MailFormatting.resolverFor(sender::hasPermission)` **on the command thread** (Bukkit
permission state belongs to the main thread) and closes over it in the async task;
`MailFormatting.sanitize` then deserializes with only those tags and re-serializes with the standard
`MiniMessage`, and *that* string is stored. A tag the sender may not use was never registered, so it
survives as text and is escaped on the way out — the recipient sees `<click:run_command:/op me>`
verbatim rather than the sentence being silently shortened. `sanitize` returns `""` when nothing
readable survives, judged on the *rendered* text: `<red>` alone still serialises back to `<red>`, so a
blankness check on the stored string would let it through and then trip `MailPayload`'s constructor.

Consequences worth knowing: **permissions are evaluated once, at send time** — revoking
`…format.click` later does not neutralise mail already sent, deliberately, since the alternative makes
an old mail's appearance depend on the sender's *current* rank. And `MAX_MESSAGE_LENGTH` bounds the
typed message, not the stored one. Design doc:
`docs/superpowers/specs/2026-08-20-console-mail-and-minimessage-design.md`.

**`/mail` is a second `InboxRouter` instance**, not the shared one `/notifications` uses.
`InboxRouter` gained a `@Nullable String dataTypeFilter` and a `Component title` constructor argument,
threaded into every `inbox`/`unreadCount`/`markAllSeen`/`dismissSeen` call it makes (see "Notification
inbox" for the filtered overloads themselves); `PlayerNotificationsPlugin` constructs one with `null` /
`"Notifications"` (unchanged) and a second with `MailPayload.DATA_TYPE` / `"Mail"`. Two instances
rather than one shared router with a per-call filter, because the page cursor and last-listed-index map
are per-screen state: `/mail list 2` must not make a stale `/notifications read 1` resolve against the
mail page. `InboxQuitListener` now holds a `List<InboxRouter>` and drops the quitting player from each.

**The `/mail` command table**, mirroring `NotificationsCommand`'s shape (`paper.command.MailCommand`,
every branch dispatched off the main thread; player-only **except `send`**, which acts on someone
else's inbox and so runs for any `CommandSender` — a console send is attributed to
`MailSender.SERVER_SENDER` (the nil UUID) / `SERVER_NAME` (`"Server"`) and reads as "Mail from Server".
The other branches act on *your* inbox, which the console does not have):

| Command | Behaviour |
|---|---|
| `/mail` | opens the mail inbox dialog (filtered `InboxDialog`, titled "Mail") |
| `/mail send <player> <message>` | sends mail (`<message>` is a greedy string, parsed as MiniMessage) and fires the notice |
| `/mail list [page]` | chat fallback list |
| `/mail read <entry>` | reads entry `<entry>` of the last-listed page; marks it seen |
| `/mail dismiss <entry>` | dismisses entry `<entry>` |
| `/mail clear` | `markAllSeen` + `dismissSeen`, both filtered to `mail` |

Two command permissions, both `default: true`: `playernotifications.command.mail` gates the root
(read/list/dismiss/clear), and `playernotifications.command.mail.send` is an **additional** requirement
on `send` only, nested under the root the way `playernotifications.command.link` nests under
`playernotifications.command.preferences` — so a server can make mail read-only for a rank by revoking
only the `.send` permission, while revoking the root hides `/mail` entirely. The fifteen
`playernotifications.command.mail.format.*` nodes are separate from both, all `default: op`, and gate
tags rather than commands — see "Mail formatting".

**Nothing in this feature has been exercised on a live server.** `MailCommand`'s Brigadier wiring, the
`/mail` and `/notifications` dialogs, tab completion, the permission split, console `/mail send`, the
per-tag formatting gate, and both notice paths (send-time and join-time) are unverified — see the
manual checklists recorded under "Current state".
Everything unit-testable is tested: `MailSenderTest`, `MailRecipientsTest`, `MailNotifierTest`,
`MailRendererTest`, `MailFormattingTest`, and — against a real MariaDB — `FilteredInboxTest` and
`core.MailNotDeliveredTest` (the regression test for the central claim above: a mail and an ordinary
renderable notification enqueued together, delivery runs, and only the non-mail one reaches a
recording sink while the mail's `seenTime` stays null and it is still present in `inbox(player, 1, 10,
"mail")`).

## Notification inbox

Design doc: `docs/superpowers/specs/2026-08-07-notification-inbox-design.md`.
Plan: `docs/superpowers/plans/2026-08-07-notification-inbox.md`.

Every notification targeting a player stays readable until they dismiss it or it passes
`notifExpiryTime`. **Delivery marks seen; it does not consume.**

Three states, all carried by one nullable column, `NotificationTarget.seenTime`:

| State | Storage | In the inbox |
|---|---|---|
| unread | row exists, `seenTime IS NULL` | listed, marked, counted on join, pushed through preferred media |
| seen | row exists, `seenTime` set | listed, not counted, never pushed again |
| dismissed | target row deleted | absent |

Dismissal **deletes the target row** rather than setting a third timestamp: an absent row makes
"never reappear" true without any query knowing the rule, and it reuses the existing
`trg_delete_targetless_notification` trigger to dispose of the notification once the last member goes.

- **Read API** (on `NotificationService`): `inbox(playerId, page, pageSize)` → `InboxPage`,
  `unreadCount(playerId)`, `markSeen(key, playerId)`, `markAllSeen(playerId)`, `dismissSeen(playerId)`,
  `pruneOrphanedTargets()`. Dismissing **one** notification needs no new method —
  `deleteNotificationTarget(key, playerId)` already does exactly that. `page` is 1-based and clamped
  into `1..totalPages`, `pageSize` into `1..20`, so a stale dialog button cannot produce an error
  screen. The page query's `ORDER BY n.notifScheduledTime DESC, n.notifPriority DESC, n.notifKey DESC`
  is a **total** order; without the key tiebreak two notifications sharing a timestamp could swap
  between page reads and appear twice or not at all.
- **Data-type-filtered overloads**, added for `/mail` (see "Mail"): `inbox(playerId, page, pageSize,
  dataType)`, `unreadCount(playerId, dataType)`, `markAllSeen(playerId, dataType)`, `dismissSeen(playerId,
  dataType)`, each taking a `@Nullable String dataType` — `null` means "no filter" rather than a parallel
  method set or an `Optional`. The existing unfiltered forms are now `default` methods delegating with
  `null`, so no pre-existing caller changed. On the mapper side the filter is one extra `<if test="dataType
  != null">AND n.notifPayloadType = #{dataType}</if>` predicate (`NotificationMapper`'s
  `selectInboxPage`/`countInbox`/`countUnread`); `NotificationTarget` has no `notifPayloadType` column, so
  `markAllSeen`'s filter is an `EXISTS` against `Notification` rather than a join. Filtered `dismissSeen`
  cannot be one `DELETE`: its subquery would read `Notification` while
  `trg_delete_targetless_notification` writes it, the same constraint `pruneOrphanedTargets` already works
  around, so it is select-then-delete — `NotificationTargetMapper.selectSeenKeys(playerId, dataType)`
  followed by one `deleteNotificationTarget(key, playerId)` per key. The clamping contract (`page` into
  `1..totalPages`, `pageSize` into `1..20`) is unchanged, applied against the **filtered** total.
- **`resolveNotifications` is deliberately unchanged** and still unfiltered: it means "every
  notification currently targeting the player", and the inbox has its own query rather than redefining
  an existing method.
- **Rendering happens on read, not on write.** `paper.inbox.InboxEntryRenderer` resolves payload class →
  `PayloadSerializer` → `NotificationRenderer`, the same three lookups `NotificationDelivery.dispatch`
  does, extracted so the two cannot drift and so this one is unit-testable without a server. Any lookup
  missing, or a decode or render throwing, yields a **placeholder** naming the data type — hiding the
  entry would leave it counted in `totalEntries` and read as a bug. Mail needs a renderer registered
  alongside its RETAIN processor for exactly this reason — see "Mail".
- **Paper UI:** `paper.inbox.InboxRouter` owns both screens, the per-player page cursor (dropped by
  `InboxQuitListener` on quit) and the async marshalling — the same shape as `PreferenceDialogRouter`,
  for the same reason. `InboxDialog` is the paged list (unread rows bold, *Mark all read*, *Dismiss all
  read*, Previous/Next); **no inbox screen carries a *Preferences* button** — the inbox is for
  reading, and preferences are reached by their own command; jumping into the preference screens from
  a list left the player with no way back to what they were reading. `InboxDetailDialog` shows one
  entry with *Dismiss* and *Back*,
  Back-doesn't-commit as in the preference editors. Opening a row marks it seen.
- **An empty inbox replies in chat and opens no dialog at all.** `InboxRouter.openInbox` returns early
  with `EMPTY_MESSAGE` ("Your inbox is empty."), the same constant the chat fallback's `listInChat`
  uses. This is not cosmetic: with no entries there are no row buttons, no Previous/Next (one page),
  no *Mark all read* (`unreadCount > 0` is false) and no *Dismiss all read* (`0 > 0` is false), so
  `DialogType.multiAction` was handed an **empty** list — and vanilla's `MultiActionDialog` codec wraps
  `actions` in `ExtraCodecs.nonEmptyList`, so the dialog failed to encode and the player saw nothing.
  `exitAction` is a separate optional field and does not satisfy that constraint. **Any future
  `multiAction` screen that can reach zero buttons has the same bug**; today none of the others can
  (`categoryKeys()` always contains `UNCATEGORIZED`, `ChatSink`/`DialogSink` are always registered, and
  the editors' Apply/Discard/Back are unconditional).
- **`inbox-page-size` is clamped twice**, by `PluginSettings`/`paper.ui.PageBounds` and again by
  `DefaultNotificationService.inbox`. Deliberate: one is a UI helper, the other a public-API trust
  boundary, and neither should assume the other ran.
- **Marking seen is not transactional with delivery.** The processor runs outside the transaction, so a
  crash between a sink delivering and the `seenTime` write leaves the notification unread and it is
  pushed again. Chat is not idempotent, so that can duplicate a message. Accepted: the window is
  milliseconds and the alternative holds a transaction across a Discord round trip.

## Global mute

Design doc: `docs/superpowers/specs/2026-08-20-global-mute-design.md`.
Plan: `docs/superpowers/plans/2026-08-20-global-mute.md`.

A player-level do-not-disturb switch, **orthogonal to the per-`dataType` media matrix**. It suppresses
every unsolicited push; it does not stop anything reaching the inbox. A muted player still accrues
notifications, still has them stored unread, and still reads them through `/notifications` and `/mail`.

- **API:** `NotificationPreferences#isMuted(UUID)`, a `default` method returning `false` — feature
  modules are compiled separately against `api`, so a new abstract method would break them. Mutation
  stays off the read interface: `DatabaseNotificationPreferences#mute(UUID)`/`unmute(UUID)`, the same
  placement `resetAll` already had.
- **Storage:** `PlayerNotificationMute(playerUuid BINARY(16) PK, mutedTime DATETIME NOT NULL)`,
  migration `V3__player_mute.sql`. Presence of the row is the mute; `mutedTime` is read by nothing and
  exists so an operator can see when a mute was set, and so a future *timed* mute has somewhere to land
  without another migration. `mute` is idempotent (`ON DUPLICATE KEY UPDATE`); `unmute` on an unmuted
  player is a no-op, not an error. A separate table rather than a `dataType = '*'`, `medium = 'none'`
  preference row: that row already means "blanket fallback", so overloading it would make unmuting
  indistinguishable from clearing a blanket preference — and would put the mute back inside the very set
  this design pulled it out of.
- **Enforcement — one gate, three push paths.** The delivery gate sits at the **top of
  `NotificationDelivery.deliver(UUID, Instant)`**, before the due query: a muted target's notifications
  are not decoded, not dispatched, and not marked seen. That placement is the whole point — `deliver` is
  the single funnel every stored notification passes, so the mute applies uniformly to the renderer path
  **and** to bespoke processors, which the old `MUTED_MEDIUM` filter inside `RenderingProcessor` could
  never do. The other two are `MailNotifier#notifyArrival` and `JoinDeliveryListener`'s announcement
  lines. `RenderingProcessor` was **not** changed; its `MUTED_MEDIUM` filter still serves the per-type
  mute.
- **Unmuting restores exactly what the player had**, because the flag never touches a preference row.
  This replaced `muteAll`, which rewrote every known `dataType` to `{none}` and therefore had no
  meaningful inverse.
- **Commands and UI:** `/notifications mute` / `/notifications unmute`, both mirrored under
  `preferences`; `PreferenceEditSession` stages the flag (`muted()`, `setMuted`, `stagedMuteChange()`,
  counted by `dirtyCount()`), and `DatabaseNotificationPreferences.applyChanges` has a four-argument
  overload taking that `@Nullable Boolean` so a staged mute and staged matrix edits commit in one
  transaction. The three-argument form delegates with `null`.
- **`/notifications test`** enqueues as usual but `deliver` no-ops, so `TestNotificationSender.report`
  checks `isMuted` first and says so, naming `/notifications unmute`. The notification still lands in
  the inbox — which is the correct outcome to observe.
- **Deliberate consequences**, all recorded in the design doc's "Known limitations": a muted player is
  told *nothing at all* (no arrival notice, no join-time unread count), so the inbox is their only
  discovery path; the mute has no timer; unmuting does **not** back-deliver what accumulated while
  muted, since a burst on unmute is precisely the interruption being avoided; and there is no admin
  surface, so a stuck mute needs a manual `DELETE` against `PlayerNotificationMute`.

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

- `/notifications` (alias `/notifs`) — **opens the player's inbox** (`paper.inbox.InboxDialog`). The name
  was reserved for exactly this; the preference subcommands sit under `preferences` so the top level
  stays clear for the inbox's own verbs, which would otherwise collide with a name like `mute`.
- `/notifications list [page]` / `/notifications read <entry>` / `/notifications dismiss <entry>` — the **chat
  fallback** for clients where the dialog does not render. `<entry>` indexes the page most recently listed
  for that player, held in `InboxRouter`. Each listed row carries a hover ("Click to run /… read `<entry>`")
  and a `runCommand` click event for its own `read` — so the fallback is clickable too, and the command it
  runs is worded from the router's `commandLabel`, making the same rows work under `/mail list`. All three are player-only, under the same
  `playernotifications.command.preferences` permission, and dispatch off the main thread.
- `/notifications clear` — empties the inbox outright, **unread entries included**, as a shorthand for
  the list screen's "Mark all read" then "Dismiss all read". Composed from `markAllSeen` +
  `dismissSeen` rather than a new service method, and drops the player's page cursor so a stale
  `read <entry>`/`dismiss <entry>` cannot resolve. Same permission, player-only, async.
- `/notifications preferences` — opens the root preferences dialog.
- `/notifications preferences media` — jumps straight to the "Delivery methods" picker.
- `/notifications preferences types` — jumps straight to the "Notification types" picker.
- `/notifications preferences mute` / `/notifications preferences unmute` — sets or clears the
  player-level mute flag **immediately** (no staging).
- `/notifications mute` / `/notifications unmute` — the two preference subcommands kept **also** at the
  top level, as proxies onto the same `PreferenceDialogRouter.muteImmediately`/`unmuteImmediately`
  actions the nested forms call (not second implementations), since muting and unmuting everything are
  the operations most often wanted in a hurry.

There is **no player-facing way back to the server default.** `/notifications preferences reset`, the
root screen's "Reset all to server default", the category editor's "Use server default" and the
picker's "(server default)" suffix were all removed together, along with
`PreferenceEditSession.resetDataType`/`dataTypesToReset`/`isUsingServerDefault` and
`PreferenceDialogRouter.resetImmediately`: players read "defaults" as a fourth preference state they
had to reason about. Every edit a player makes is now an explicit choice, and the only way out of one
is to pick different media or mute. `default-media` still exists and still applies to a `dataType` the
player has never configured — it is just no longer reachable once they have. `core` kept
`DatabaseNotificationPreferences.resetAll`/`explicitlyConfiguredDataTypes` and `applyChanges`'s
`dataTypesToReset` parameter, which are now **uncalled** — persistence-level operations left in place
(and still tested) for a future admin command, not UI.
- `/notifications link [provider] [status]` / `/notifications unlink [provider]` — account linking, backed
  by the `AccountLinkRegistry`. Gated by its own `playernotifications.command.link`
  (`NotificationsCommand.LINK_PERMISSION`, `default: true`) — see below.
- `/notifications test [message]` — enqueues a `test` notification targeting the sender and delivers it
  immediately, off the main thread. Admin-only (`playernotifications.command.test`, `default: op`) and
  player-only. The **only** caller of `NotificationDelivery.deliver(UUID)` in the tree. Its reply names
  the media *attempted*, not delivered — `RenderingProcessor` reports no per-sink outcome — labelled via
  `NotificationSinkRegistry#displayName(String)` so they match the preference dialogs, and separates the
  three preference states: a `{none}` mute and an empty selection each get their own "nothing was sent"
  reply, and a preferred medium with **no registered sink** is called out, since `RenderingProcessor`
  skips it silently and the reply would otherwise overstate what happened. Backed by
  `paper.diagnostic.TestNotificationSender`, which takes a `Supplier<NotificationDelivery>` rather than
  the instance because `reload()` replaces that object.
  `TestNotificationRenderer` titles the notification `[Test] Test Notification` and opens the body by
  saying what a test notification is and that `/notifications test` sent it — it lands in the same inbox
  as real ones (e.g. a Discord DM) with no other context, and previously read as a stray
  debug message. It names the target rather than printing its raw UUID, taking a
  `Function<UUID, String>` name lookup so it stays unit-testable; `TestNotificationRenderer.usingServerNames()`
  is the production wiring over `Bukkit.getOfflinePlayer`.
- `/notifications reload` — reloads `categories.yml` and `settings.yml` without a restart. Admin-only
  (`playernotifications.command.reload`, `default: op`), and usable from console, unlike every other
  subcommand — it operates on plugin configuration, not a specific player, so `NotificationsCommand`
  dispatches it outside the player-only `run()` helper the rest of the tree uses. Deliberately does
  **not** reload `database.yml`, since that would mean rebuilding the MariaDB connection pool mid-request.
  Also refreshes the `JoinDeliveryListener`'s `deliver-on-join` toggle and delay — see "Join delivery".

**`/mail` is a sibling command tree, not a subcommand of `/notifications`** — see "Mail" for its full
table, `MailNotifier`, and why mail is never pushed through `RenderingProcessor`.

The player-facing subcommands are player-only, under permission `playernotifications.command.preferences`,
declared in `paper-plugin.yml` with `default: true`. `link`/`unlink` carry an **additional**
`playernotifications.command.link` (also `default: true`), so a server can restrict linking without
closing preferences; because Brigadier `requires` nest, revoking the root permission still hides
linking. Both defaulting to `true` means the split is a no-op until an operator negates one.

**Account linking lives under this tree**, via `api.link.AccountLinkRegistry` — a host-owned registry a
feature module registers an `AccountLinkProvider` against. This **reverses** an earlier decision (linking
used to be the Discord module's own `/discordlink`, kept out of the host "to avoid a registry it has no
other use for"); it still has exactly one consumer, but it deleted more host-adjacent machinery than it
added, and linking is now discoverable from the command a player already knows.

- `/notifications link` — lists every registered provider and how to use it.
- `/notifications link <provider>` — starts a link; `… <provider> status` describes the current one.
- `/notifications unlink <provider>` — a **sibling** literal of `link`, not a child of it.

`<provider>` is a Brigadier **argument**, not one literal per provider: the tree is built once when Paper
fires `LifecycleEvents.COMMANDS`, and modules register during `startModules()`, so static literals would
freeze the provider set. Suggestions and resolution both read the registry live. The node exists even
with nothing registered; an absent provider (never installed, or its module stopped) gets an explanatory
reply — removing the node would need mutation of an already-registered Brigadier node, which Paper does
not expose. `paper.command.AccountLinkDispatcher` holds all of this (resolution, routing, containing a
throwing provider) precisely so it is unit-testable; `NotificationsCommand`'s `link` subtree is wiring
only. Both branches are player-only and dispatched off the main thread, since providers block on JDBC.

`paper.command.NotificationsCommand` builds the Brigadier node and delegates every subcommand to a
`paper.preferences.PreferenceDialogRouter`, the single object owning the six dialog screens and the
shared `PreferenceSessionManager` (`paper.preferences.session`):

- `PreferenceRootDialog` — pick a pivot ("Delivery methods" / "Notification types"), or go to
  "Mute everything". It is **purely navigational**: it carries no Apply and no Discard at all, since
  every edit is made and committed on a screen of its own and a commit button here would belong to no
  particular edit. It still shows the `stagedSummary` line.
- `MuteConfirmDialog` — the "Mute everything" / "Unmute everything" confirmation, reached from the root
  button. One screen either way: title, intro and direction read `PreferenceEditSession#muted()`, and
  the root button's own label flips with it. Structurally an editor with no inputs: Apply/Discard via
  `addEditorCommitButtons`, plus a Back that returns to the root. The flip is staged by **Apply's commit
  callback**, not on the way in, so Back genuinely changes nothing.
- `MediumPickerDialog` → `MediumEditorDialog` — pick a medium, then one checkbox per **`dataType`**
  (grouped/labeled by its primary category for readability, and title-cased rather than shown as the
  raw registry key — see `PreferenceDialogs.sortedDataTypes`/`dataTypeLabel`), e.g. "which
  notifications reach me on Discord".
- `CategoryPickerDialog` → `CategoryEditorDialog` — pick a category, then one checkbox per **medium**;
  each medium's checkbox fans out to every `dataType` the category claims
  (`NotificationCategories#dataTypesForCategory`), and shows "(partly on)" when the category's member
  `dataType`s currently disagree on that medium. **Structurally the mirror of the medium pair**: same
  button set (Apply / Discard / Back, no Save and no per-row state annotation on the picker), differing
  only in which axis is the row and which is the checkbox.

**Apply and Discard are on every screen that can edit something** — the two editors, the mute
confirmation, and the two pickers once the session is dirty — along with a
`PreferenceDialogs.stagedSummary` line naming the pending count, which the root screen shows too. This
replaced an Apply/Discard pair that existed **only** on the root screen, which players reported as the
main confusion: Save navigated away and nothing on the screen they landed on said anything was unsaved.

- **The editors have no Save.** `addEditorCommitButtons` shows Apply and Discard *unconditionally*;
  `addStagedButtons` (the two pickers, which have no inputs) shows them only while the session is
  dirty. The distinction matters: an editor's checkbox state lives in the dialog response until a
  button is pressed, so a first edit on a clean session has nothing staged yet and a dirty-gated Apply
  would be missing exactly when it is needed. A Save that staged without persisting, sitting next to
  an Apply that did both, was a third option whose difference from Apply nobody could state.
- **Apply folds in the on-screen response first**, via the `Consumer<DialogResponseView>` the editors
  pass — without it Apply would write only what was already staged and silently drop the ticks in
  front of the player.
- **`Back` on an editor abandons that screen's checkboxes.** It deliberately does not commit, so
  leaving an editor without pressing Apply resets what was ticked there — Back is the way out without
  saving. It is not a session-wide undo: edits applied or staged from another screen are untouched,
  and Discard remains the button that throws the whole session away.
- **Both actions take *reloading* router entry points** (`openRoot`/`openMediaPicker`/
  `openCategoryPicker`, plus `openMediaEditor`/`openCategoryEditor` added for this) because applying
  and discarding both drop the session the caller holds. Apply returns to the picker; an editor's
  Discard reopens *that editor*, so the reverted checkboxes are visible rather than the player being
  dropped elsewhere to infer what happened.

Both editors mutate the same `paper.preferences.session.PreferenceEditSession`, keyed by `dataType` (not
category), so the two pivots can never disagree. An editor's checkboxes reach the session only when a
button commits them, which only Apply does; nothing is persisted until **Apply**, which writes every dirty
`dataType` in one transaction
(`DatabaseNotificationPreferences.applyChanges`, called with an empty `dataTypesToReset` — the session
has no reset concept left, so every staged edit is an explicit write). A `dataType` emptied to nothing —
from either editor — stages a mute (`{"none"}`); there is no staged form of "fall back to the server
default" at all. In `CategoryEditorDialog`, committing always writes every member `dataType`'s state for
every medium shown, even ones the player didn't touch — opening a category editor and pressing Apply
with no changes still marks every member `dataType` dirty and converts them from unconfigured to an
explicit row matching whatever was already displayed.

**Three preference states per `dataType`**, expressible per-`dataType` via the medium editor or (fanned
out) via the category editor:

| State | Storage | `preferredMedia(player, dataType)` returns |
|---|---|---|
| Unconfigured | no exact rows for that `dataType` | `*` rows, else `default-media` from `settings.yml` |
| Explicit selection | one row per medium for that `dataType` | that set |
| Explicit mute | a single `medium = 'none'` row for that `dataType` | `{none}` |

A per-type-muted notification is **retained unread in the inbox**, not consumed: `RenderingProcessor`
drops `NotificationPreferences.MUTED_MEDIUM` and returns `RETAIN`. **This table is the per-`dataType`
axis only** — the player-level mute is a separate switch on top of it, and does not appear in these
rows; see "Global mute".

**Unconfigured is now a one-way state**: it is where a `dataType` starts, and nothing in the UI returns
a `dataType` to it since the "server default" affordances were removed. The row remains because
`preferredMedia` still resolves it — for a `dataType` the player has never touched, and for one
registered by a module installed after they last edited their preferences.

`"none"` is **not a registered sink** — `NullSink` was deleted with the inbox work, because reporting
`DELIVERED` would have marked a muted notification seen and hidden it from the unread list, precisely
backwards for a player who muted a type in order to read it later. It survives as the constant
`NotificationPreferences.MUTED_MEDIUM`, stored as a row rather than as zero rows because zero rows
already means "has expressed no preference". It is excluded from every checkbox list, since checking
nothing already says the same thing. It is reached only through the medium/category editors, by
emptying a `dataType`'s selection: **`/notifications mute` and `MuteConfirmDialog` no longer write
`{none}` rows at all**, and `DatabaseNotificationPreferences.muteAll` (which used to write one per
`dataType` plus a blanket `"*"` row) was deleted with the global mute. That approach destroyed the
player's real choices, which is why there was no unmute to pair with it.

Implementation notes:
- `PreferenceSessionManager` expires a session after 15 minutes idle (`IDLE_TIMEOUT`) and
  `PreferenceQuitListener` drops it on `PlayerQuitEvent`; reopening after either starts fresh from the
  database.
- `/notifications preferences mute`/`unmute` write **immediately** and discard any open staged session
  with a chat notice — the one deliberate asymmetry with `MuteConfirmDialog`'s staged flip, which only
  takes effect on Apply.
- `DatabaseNotificationPreferences` does blocking JDBC while `Player#showDialog` must run on the main
  thread, so dialog loads/writes marshal onto the async scheduler and back (`PreferenceDialogs.withSession`).
- **`paper.ui`** (`PageBounds`, `PagedDialogs`, `DialogSupport`) holds the paging arithmetic, the
  Previous/Next buttons and page indicator, and the callback options / main-thread marshalling /
  `message` helpers that used to live in the package-private `PreferenceDialogs`, which now delegates.
  **The package imports nothing from `io.github.md5sha256.playernotifications`** — only Paper, Bukkit and
  Adventure — so lifting it into `plugin-infrastructure` would be a package rename. It stays in-tree
  until a second consumer exists; a change that would only ever make sense for the inbox belongs in
  `paper.inbox` instead. Verify the rule with
  `grep -rn "playernotifications" platform/paper-plugin/src/main/java/.../paper/ui/` — only `package`
  lines should match.
- Dialog input keys are **positional** (`medium_0`, `category_0`, …) with a key→value map, because
  medium/category keys are arbitrary strings and any sanitizing transform risks two colliding onto one
  input.
- Button callbacks use `ClickCallback.Options` with `uses(1)` and a one-hour lifetime; a dialog left
  open past that has inert buttons and must be reopened.
- **Known quirk, now also a deliberate feature:** an explicitly registered `NotificationProcessor` wins
  the dispatch-precedence rule and bypasses **per-`dataType`** preferences entirely (categories were
  never part of the dispatch path, even before this quirk existed), so a player who muted one type
  would still receive its notifications. It does **not** escape the player-level mute, which is checked
  in `NotificationDelivery.deliver` before dispatch — see "Global mute". There is again an in-tree instance of this — `mail`'s RETAIN processor (see "Mail") —
  but there it is intentional: mail is never sent through any medium at all, so there is nothing for a
  mute to bypass, and the `mail`-keyed preference rows the processor skips are reused to route the
  arrival *notice* instead. The quirk remains reachable, and still a footgun, for any other
  third-party processor that isn't making that same trade deliberately.
- The six dialog classes and the router are **unverified by automated tests** — they need a live
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
- `settings.yml` → `PluginSettings` (in `paper-plugin`): `prune-interval-seconds` (default 3600) — how often the async task deletes expired notifications; `default-media` (`List<String>`, default `[chat]`) — the media a player is assumed to prefer when they have no stored preference rows; `deliver-on-join` (`boolean`, default `true`) — whether joining triggers delivery of that player's due notifications; `join-delivery-delay-seconds` (`long`, default 3) — how long after the join event delivery runs, `0` meaning immediately and a negative value clamped to `0` (not defaulted, unlike `prune-interval-seconds`); `inbox-page-size` (`int`, default 7) — how many inbox entries `/notifications` shows per page, clamped to `1..20` in the compact constructor, with `0` (the value an absent key deserializes to) falling back to the default. The three primitive keys and so deliberately **not** `@Required` — that rule guards against a missing key deserializing to `null`, which a primitive cannot do.
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
- `database.migration.MigrationStep` + `core/src/main/resources/sql/migrations/V*.sql` — the migrator tracks applied versions in a `schema_version` table and runs each script once. **Adding a migration means adding both the `V*.sql` file and a `MigrationStep` entry to `MariaSchemaMigrator.DEFAULT_MIGRATIONS`** — that list is hardcoded, not discovered from the classpath. Three steps today: `V1__maria_initial_schema.sql`, `V2__notification_inbox.sql` (`seenTime` plus its index) and `V3__player_mute.sql` (the `PlayerNotificationMute` table). Migrations that predate V1 were collapsed into it, back when the project had no data to preserve; **that is no longer the rule** — V2 was layered precisely because there is now deployed data, and further changes must be layered too. `SchemaUpgradeTest` covers the already-at-V1 path, which `AbstractDatabaseTest` cannot: it migrates an empty schema with the whole chain in one call, and asserts `seenTime` and `PlayerNotificationMute` exist and `MAX(version) = 3`. **A feature module can own its own migrations** without joining this list — see "Module system".

Schema (`V1__maria_initial_schema.sql`), three tables:
- `NotificationTarget(notifTargetId INT, playerUuid BINARY(16), seenTime DATETIME NULL, PRIMARY KEY(notifTargetId, playerUuid))` — a target group is the set of rows sharing a `notifTargetId`. `seenTime` (added by V2, indexed with `playerUuid`) is that member's read marker: `NULL` is unread, set is seen, and a deleted row is dismissed. See "Notification inbox". New group ids come from `MAX(id)+1` allocated inside the enqueue transaction.
- `Notification(notifKey PK, notifScheduledTime, notifExpiryTime NULL, notifTargetId, notifPayloadType, notifPayload JSON, notifPriority)` with indexes on `notifTargetId`, `notifPayloadType`, `notifScheduledTime`, `notifExpiryTime`.
- A trigger `trg_delete_targetless_notification` (`AFTER DELETE ON NotificationTarget`) deletes a notification once its target group has no remaining members. It is a **single-statement trigger body** (no `BEGIN…END`) because `MariaSchemaMigrator` splits scripts on `;`.
- `PlayerNotificationMute(playerUuid BINARY(16) PRIMARY KEY, mutedTime DATETIME NOT NULL)` — added by V3. One row per muted player; presence is the mute. See "Global mute".
- `PlayerNotificationPreference(playerUuid BINARY(16), dataType VARCHAR(64), medium VARCHAR(64), PRIMARY KEY(playerUuid, dataType, medium))` — one row per preferred medium **per `dataType`**, so a player's preference is set-valued within each `dataType` (`chat` + `discord-dm` for `mail` is two rows). See "Notification categories" for how `dataType` and the reserved key `*` (`ALL_DATA_TYPES_KEY`) resolve, and how the separate, display-only category concept relates.

`player-notifications.drawio` is the design source for the schema (note it uses conceptual names like `notif_key`; the DDL uses camelCase columns) — it predates the `dataType` column (originally `category`) and has not been updated.

## Testing gotchas

- `:core:test` **and `:platform:discord-adapter:test`** need a **running Docker daemon** (Testcontainers, `mariadb:11.7`). Without it the suite fails rather than skipping. The adapter's fixture (`discord.schema.DiscordSchemaTestSupport`) hands each test its **own fresh schema** rather than truncating a shared one, because its migrator tests assert on DDL and on version bookkeeping and so must start with no tables at all.
- `api`, `core`, **and `platform:paper-plugin`** declare **`testRuntimeOnly("io.papermc.paper:paper-api")`**. `compileOnlyApi` is not on the test runtime classpath, so without it tests touching Adventure/Bukkit types die at discovery with `NoClassDefFoundError: net/kyori/adventure/text/Component`.
- **Counting results: glob `*.xml`, not `TEST-*.xml`.** On Windows, Gradle shortens result filenames for `@Nested` classes to dodge the path-length limit, producing `__TEST-<hash>...` names. Several test classes here (`NotificationMapperTest`, `PlayerNotificationPreferenceTest`) put **all** their `@Test` methods inside `@Nested` inner classes, so a `TEST-*.xml` glob silently omits them and makes passing tests look like they never ran.
- `./gradlew :core:test --tests "<pattern>"` can report **BUILD SUCCESSFUL while matching nothing meaningful**. Check the result count, not the exit status.

Current baseline: **108 tests in `:core:test`, 30 in `:api:test`, 101 in `:platform:paper-plugin:test`, 122 in `:platform:discord-adapter:test`** — 361 in total, all passing.

## Current state

The project builds end-to-end; `:core:test`, `:api:test`, and `:platform:paper-plugin:test` pass. The
enqueue → deliver path is complete: `enqueueNotification` persists the notification's `notifPayloadType`;
`NotificationDelivery.deliver(UUID[, Instant])` resolves a player's due notifications, decodes each
payload through its registered `PayloadSerializer`, dispatches by the precedence rule above (directly on
`dataType`, no category resolution), and stamps `seenTime` on targets whose processor returns
`NotificationDisposition.MARK_SEEN` — delivery no longer destroys its own input, so the notification
stays readable in the player's inbox (see "Notification inbox").
Preferences are stored and resolved per `dataType` end-to-end: storage, dispatch, and the player-facing
dialogs (root, by-medium, by-category) all agree on the same `dataType` axis, with categories layered on
top purely as a player-facing display/grouping concept (a `NotificationCategoryRegistry` for code-driven
claims, merged with `categories.yml` by `NotificationCategories`, many-to-many). Known gaps / notes:
- **`NotificationCategoryRegistry` has no in-tree consumer yet.** No feature module calls
  `claimDataType`/`registerCategory` (`platform:discord-adapter` owns a medium, not a data type, and the
  `mail` category is declared in `categories.yml`, not through the code registry), so the code-registry
  half of the category system (and the two-pass rebuild-after-`startModules()` ordering in
  `PlayerNotificationsPlugin.onEnable()`) is exercised only by unit tests against a hand-built registry,
  never end-to-end by a real module through the real module class loader.
- **Delivery has two triggers: joining, and `/notifications test`.** `paper.JoinDeliveryListener` and
  `TestNotificationSender` are the only callers of `deliver(UUID)`. The remaining gap is that a
  notification enqueued for an **already-online** player still waits until their next join — there is no
  push path, because that would need the enqueue call to reach the Paper layer, which
  `NotificationService` in `core` deliberately does not do. See "Join delivery" above.
- **`ChatSink`, `DialogSink`, and the six preference dialog screens are unverified by automated tests** — they need a live server. Check them by hand with `:platform:paper-plugin:runServer`.
- **The Discord adapter's end-to-end path has never been run.** `DiscordBot`, `JdaDiscordMessenger`,
  `DiscordSrvAccountProvider` and `DiscordModule` compile and are wired, but JDA login, the DiscordSRV
  lookup, module class loading through the shaded jar, and an actual DM landing have not been verified —
  they need a real bot token and an account link. Checklist: Task 8 of
  `docs/superpowers/plans/2026-07-29-discord-adapter.md`. `/notifications test` now exists to drive
  exactly this check — it is the intended way to run that checklist. Since `embedded` linking landed, a
  DiscordSRV-linked account is no longer needed to run it: `/notifications link discord` can supply the link instead.
- **`/notifications test`'s own wiring is unverified by automated tests.** `TestNotificationSender`,
  the Brigadier `test` subcommand, the permission gate, and the `test` type appearing in the preference
  dialogs all need a live server. The path underneath them (`registerJsonRenderable` → enqueue →
  `deliver` → render → sink fan-out → prune) **is** covered, by `core`'s `RenderedDeliveryTest`.
- **Discord linking exists but its end-to-end path has never been run.** `embedded` + `/notifications link discord` +
  the Discord `/link` slash command are implemented and unit tested where testable, but JDA slash-command
  registration, the DM interaction, and a row actually landing need a live server, a bot token and a
  Discord account. Checklists: Task 7 of `docs/superpowers/plans/2026-07-30-embedded-discord-linking.md`
  and Task 6 of `docs/superpowers/plans/2026-07-30-account-link-subcommand.md` (the move to
  `/notifications link discord`; **not yet run** — the Brigadier `link`/`unlink` node wiring, tab
  completion, and the absent-provider reply all need a live server. `AccountLinkDispatcher`, the logic
  underneath, **is** unit tested).
  Remaining gaps in the feature itself:
  - **No admin link management** — no way to link, unlink or inspect another player's link, and no
    listing. A stuck link needs a manual `DELETE` against `DiscordAccountLink`.
  - **No rate limiting** on `/notifications link discord` or on `/link` attempts. A 6-character code over a 32-character
    alphabet is ~10^9 with a 10-minute window, which makes brute force impractical rather than
    impossible; add a per-Discord-user attempt limit if abuse is ever seen.
  - **Codes do not survive a restart** (in memory by design), and **one Discord account per player** is
    enforced in the schema.
- **`notifPayload` is a `JSON` column**, so payloads must be valid JSON. A payload mapped to `String.class` is therefore JSON-encoded on write and arrives at its processor still quoted — the reason every in-tree payload now owns a record instead. `DefaultNotificationService` still pre-registers a `String` serializer and nothing forbids `String.class`, so the trap is still reachable; no registration guard was added (considered and deferred — see the test-notification design doc).
- **Stored `essentials-mail` *preference* rows are inert.** They predate the Essentials adapter's removal (see "Mail") and named a medium nothing registers any more: the host renders an unregistered medium by its raw key, and `RenderingProcessor` simply skips a preferred medium with no sink. No admin command prunes them; acceptable only because the project has no deployed data to preserve.
- **Partial delivery is silent** under the MARK_SEEN-wins fan-out, though no longer lossy — see
  "Rendering & delivery media".
- Target-id allocation via `MAX(id)+1` is not concurrency-safe under parallel enqueues (fine for a plugin's low write volume).
- **Rows for a category removed from `categories.yml` are kept, not pruned** — they resurface if the category is re-added, and are invisible in the dialogs meanwhile. No admin command prunes them.
- **The inbox exists, but its player-facing surface is unverified.** `InboxRouter`, `InboxDialog`,
  `InboxDetailDialog`, the `list`/`read`/`dismiss` Brigadier subcommands, the bare `/notifications`
  opening the dialog, and the join unread line all need a live server. **Task 8's manual checklist in
  `docs/superpowers/plans/2026-08-07-notification-inbox.md` has not been run.** Everything underneath is
  covered: `InboxDeliveryTest`, `InboxReadTest`, `InboxEntryRendererTest` and `PageBoundsTest`.
- **Mail's player-facing surface is entirely unverified — nothing about it has run on a live server.**
  `MailCommand`'s Brigadier tree, both `InboxRouter` dialogs under the "Mail" title, the chat fallback,
  tab completion, the two-permission split, and both notice paths (send-time and join-time) all need
  `:platform:paper-plugin:runServer`. **Task 4's 14-item manual checklist in
  `docs/superpowers/plans/2026-08-10-first-party-mail.md` has not been run**, and neither has **Task 3's
  six-item checklist in `docs/superpowers/plans/2026-08-20-console-mail-and-minimessage.md`** (console
  `/mail send`, the "Mail from Server" title, a plain player getting literal text until a format node is
  granted, a non-op's `<click>` still arriving literal, an op's arriving live, and a tag-only message
  rejected as blank). Everything underneath is
  unit- or Testcontainers-tested — see "Mail" and "Mail formatting" for the list.
- **The global mute's player-facing surface is unverified — nothing about it has run on a live
  server.** `/notifications mute|unmute` (both the top-level and `preferences` forms), the root
  dialog's state-dependent button label, `MuteConfirmDialog`'s staged flip, the suppressed join and
  mail-arrival lines, and `/notifications test`'s muted reply all need
  `:platform:paper-plugin:runServer`. **The 12-item manual checklist in
  `docs/superpowers/plans/2026-08-20-global-mute.md` has not been run.** Everything underneath is
  covered: `PlayerMuteTest` and `MutedDeliveryTest` (against a real MariaDB — including a case proving
  a *bespoke processor* is gated too), plus `MailNotifierTest`, `JoinDeliveryListenerTest` and
  `PreferenceEditSessionTest`.
- **Inbox size is unbounded** and **seen is per player, not per medium** — both accepted; see the design
  doc's "Known limitations".
- **The orphaned-target leak is fixed.** `deleteExpired`/`deleteByKey`/`deleteByPayloadType`/
  `deleteByPlayer` still delete `Notification` rows without touching `NotificationTarget` (the trigger
  only fires the other way round), but `pruneOrphanedTargets()` now runs on the periodic prune task. It
  is a select plus per-group deletes, **not** one `DELETE … LEFT JOIN Notification`: MariaDB refuses a
  statement that reads `Notification` when the delete fires `trg_delete_targetless_notification`, which
  writes it.
- Deferred to their own designs: **admin Discord link management**, a **`discord-channel-ping` sink**, **per-medium delivery tracking**, **actions/buttons** in `RenderableNotification`, and **admin commands** (no admin view of another player's inbox or preferences, and no player-initiated bulk clear beyond "Dismiss all read").
