# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## Workflow skills

Four project-local skills in `.claude/skills/` replace the `superpowers` plugin (disabled for this
project in `.claude/settings.local.json`). Invoke the matching one **before** acting; they run inline
and dispatch no subagents.

| Situation | Skill |
|---|---|
| New feature, API change, behaviour change — before any code | `design` |
| Writing feature/bugfix code, or executing a plan | `implement` |
| Any bug, test failure, build failure, unexpected behaviour | `debug` |
| Before claiming done/fixed/passing, and before committing | `ship` |

`design` → `implement` → `ship` is the normal path; `debug` feeds into `implement`. Skip `design` only
for changes whose shape is not in question. Subagents are opt-in: dispatch one only when asked.

Where a skill or this file describes the codebase and the code disagrees, **the code wins** — fix the
doc in the same commit. Test counts, module lists and the "Current state" gaps drift fastest.

## Overview

PlayerNotifications is a PaperMC (Spigot) plugin for Minecraft **26.1.2**, Java **25**. Per-player
notifications are stored in MariaDB and delivered through pluggable, payload-typed processors — or, more
commonly, through the **renderer/sink** path, which fans a notification out to whichever media a player
prefers (chat, dialog, Discord DM). MyBatis persistence lives in `core`; the Paper bootstrap and platform
integrations under `platform/`. Cross-cutting infrastructure (module system, schema migrator, Configurate
helpers) comes from the external `plugin-infrastructure` library. A first-party `mail` type is stored and
read via `/mail` but — deliberately — never delivered through any medium.

## Build & run

Gradle wrapper (9.3.0). On Windows use `./gradlew` from Bash or `gradlew.bat` from PowerShell.

- `./gradlew build` — all modules. `platform:paper-plugin`'s `build` depends on `shadowJar`.
- `./gradlew :platform:paper-plugin:shadowJar` — the distributable plugin jar.
- `./gradlew :platform:discord-adapter:shadowJar` — must be the **shaded** (`-all`) jar: the module
  bundles its own relocated JDA, so the plain `jar` contains no Discord library at all.
- `./gradlew test` — everything (JUnit 5).
- `./gradlew :core:test` and `./gradlew :platform:discord-adapter:test` — **need a running Docker
  daemon** (Testcontainers, `mariadb:11.7`); both modules own schema and are tested against a real DB.
- `./gradlew :platform:essentials-mail-converter:test` — hermetic, no Docker: the module owns no schema
  and every rule it holds was pushed into two plain classes for exactly that reason.
- `./gradlew :core:test --tests "…SomeTest"` — a single class/method.
- `./gradlew :platform:paper-plugin:runServer` — a real Paper 26.1.2 test server, files under
  `platform/paper-plugin/run/`. Needs a reachable MariaDB (`database.yml`). `dependsOn`
  `installFeatureModules`, and its `downloadPlugins` fetches DiscordSRV `v1.30.5`.
- `./gradlew :platform:paper-plugin:installFeatureModules` — `Sync`s feature-module jars into
  `run/plugins/PlayerNotifications/modules/`. Modules are **files, not classpath entries** (the host
  loads them through their own `URLClassLoader`). A new adapter is one
  `featureModules(project(path = ":platform:<name>", configuration = "moduleJar"))` line in
  `platform/paper-plugin/build.gradle.kts`. The `Sync` owns only top-level `*.jar`
  (`preserve { include("**"); exclude("*.jar") }`), so runtime-written module configs survive at any depth.

## Publishing

`api` and `core` publish to the network Maven repo; **1.0.0 is deployed** at
`https://maven.minecraftcitiesnetwork.com/releases`. Other modules are not published.

```kotlin
repositories { maven("https://maven.minecraftcitiesnetwork.com/releases") }
dependencies { compileOnly("io.github.md5sha256:player-notifications-api:1.0.0") }
```

- **Artifact ids are prefixed, project names are not**: `:api`/`:core` publish as
  `player-notifications-api`/`-core` via explicit `artifactId`, because the bare names would squat
  generic coordinates in a shared repo. Project dependencies pick the prefixed id up automatically.
- **`buildSrc/src/main/kotlin/player-notifications-publish.gradle.kts`** is the convention plugin:
  `maven-publish`, sources jar, the `deploy` repository (`deployUrl`/`deployUsername`/`deployPassword`
  Gradle properties) and shared POM metadata.
- **`.github/workflows/deploy-api.yml`** runs on `release: published` and `workflow_dispatch`. It greps
  `version` out of `gradle.properties`, routes `-SNAPSHOT` to `/snapshots` and anything else to
  `/releases`, then publishes both modules. Credentials come from the `MAVEN_REPOSITORY_*` secrets (the
  URL secret holds the bare host; the workflow appends the suffix).
- **The version lives in the root `gradle.properties`** and nowhere else. (Sibling project `realty`
  greps its version from its conventions script instead — its workflow's pattern is not this one's.)
- **Publishing is not gated on tests.** No `build.yml` here; a broken `core` reaches the repo if nobody
  ran the suite locally.

## Module architecture

Modules: `api`, `core`, `platform:paper-plugin`, `platform:discord-adapter`,
`platform:essentials-mail-converter`. Type-safe project accessors are on (`projects.api`, …).
Dependency direction is **platform → core → api**; `api` depends on nothing but Paper.

- **`api`** (`…playernotifications.api`) — dependency-light public API, `compileOnlyApi` on `paper-api`.
  - `NotificationService` — `enqueueNotification`, `resolveNotifications(UUID)` (a pure read, no delete),
    `clearNotification(s)`, `deleteNotificationTarget(s)`, `clearExpiredNotifications`,
    `dataTypeRegistry()`, `categoryRegistry()`, plus the inbox read API.
  - `NotificationDataTypeRegistry` — `dataType` string → payload class, and payload class →
    `NotificationProcessor` / `PayloadSerializer` / `NotificationRenderer`; also
    `registerDisplayName`/`displayName`/`unregisterDisplayName` (see "Type names"). The extension point.
  - `NotificationSinkRegistry` — keyed by **medium** (`chat`, `dialog`, `discord-dm`), not data type.
    `"discord-channel-ping"` is **reserved but unimplemented**.
  - `Notification` / `ResolvedNotification` — persisted vs. target-resolved forms; the latter holds a
    `NotificationTarget` (player UUIDs), the `notifPayloadType` and the `String` payload.
  - **`api.processor`** — `NotificationProcessor<T>` is a `@FunctionalInterface`:
    `NotificationDisposition receiveNotification(T payload, UUID target)`, **one target per call**,
    returning `RETAIN` or `MARK_SEEN`. Composition lives in `NotificationProcessorBuilder`
    (`andThen`/`andThenIf`/`onComplete`, folding MARK_SEEN-wins). `FixedDelayProcessor` adds a delay.
  - **`api.render`** — the renderer/sink architecture: `RenderableNotification` (medium-neutral
    `Component` title + body), `NotificationRenderer<T>` (one per payload type), `NotificationSink` (one
    per medium, returning `DeliveryResult` = `DELIVERED`/`UNREACHABLE`/`UNSUPPORTED`),
    `NotificationPreferences` (`preferredMedia(UUID)` and `preferredMedia(UUID, dataType)` — resolved on
    `dataType`, no category involved — plus `default boolean isMuted(UUID)`), and `RenderingProcessor<T>`,
    the single framework-supplied processor, constructed with the `dataType` it dispatches for
    (`@NotNull`). `NotificationSink` carries `default displayName()`/`description()` for player-facing UI
    (`displayName()` title-cases the key, so `discord-dm` → "Discord Dm"). `api.render.sink` holds
    `ChatSink` and `DialogSink`. The `"none"` medium is **not a sink**: it is the constant
    `NotificationPreferences.SILENCED_MEDIUM`, filtered out by `RenderingProcessor`.
    **"Silence" and "mute" are deliberately different words** — a silence is a standing per-type choice,
    a mute a temporary player-level suspension; every surface keeps them apart.
  - **`api.category`** — `NotificationCategoryRegistry` (in-memory `DefaultNotificationCategoryRegistry`)
    for code-declared categories and `dataType` claims.
  - `InboxEntry` / `InboxPage` — one notification as the viewer sees it (payload plus their `seenTime`,
    with `unread()`), and one page (`entries`, `page`, `pageSize`, `totalEntries`, `unreadCount`,
    `totalPages()` at least 1).
  - **`api.link`** — `AccountLinkProvider` (`providerKey`, title-casing `default displayName()`, the
    static `defaultDisplayName(key)` for an unregistered key, and `begin`/`status`/`unlink` returning a
    `Component` rather than sending it) and `AccountLinkRegistry` (synchronized, keys lower-cased on both
    ends). Exposed on `PlayerNotificationsPlugin`, deliberately **not** on `NotificationService`, which
    has impls in `core` and in tests and no stake in linking.
  - **`api.serialize`** — `PayloadSerializer<T>` (JSON ↔ `T`) and `PayloadSerializationException`. The
    only serialization type crossing the boundary; the JSON library stays the supplier's business.
- **`core`** (`…core`) — MyBatis persistence, `DefaultNotificationService`, `NotificationDelivery`
  (dispatches directly on `dataType`), `DatabaseNotificationPreferences`, `category.NotificationCategories`
  (read-only display merge), `serialize.JacksonPayloadSerializer`. `api("org.mybatis:mybatis")`,
  `api("org.spongepowered:configurate-yaml")`, `implementation` mariadb driver, `paper-api` compileOnly
  **plus `testRuntimeOnly`** (see "Testing gotchas").
- **`platform:paper-plugin`** (`…paper`) — the Paper bootstrap. `onEnable` loads config (including
  `categories.yml`), builds `MariaDatabase`, migrates, constructs `DefaultNotificationService`, registers
  it under `NotificationService.class` in the `ServicesManager`, builds the sink registry (`ChatSink`,
  `DialogSink`), `DatabaseNotificationPreferences` and `NotificationCategories`, constructs
  `NotificationDelivery`, registers the Brigadier commands, an `InboxRouter` per surface with its
  `InboxQuitListener`, a `PreferenceQuitListener` and a `JoinDeliveryListener`, schedules the async prune
  task, starts the module system, and finally warns about any `categories.yml` data type with no payload
  mapping. Exposes `database()`/`notificationService()`/`sinkRegistry()`/`preferences()`/`categories()`/
  `notificationDelivery()`/`typeNames()`/`inboxPageSize()` for modules. Applies `shadow` (relocating
  `org.mariadb`, `org.mybatis`, `org.apache.ibatis`, `org.spongepowered`, `io.leangen.geantyref`,
  `com.fasterxml.jackson`) and `run-paper`, and declares `testRuntimeOnly("io.papermc.paper:paper-api")`.
- **`platform:discord-adapter`** (`…discord`) — feature module delivering Discord DMs. `DiscordModule`
  registers `DiscordDmSink` under `discord-dm` — a **sink**, so unlike mail's RETAIN processor it
  participates in preferences and fan-out normally. It **owns its own schema** under `discord.schema`;
  `core` knows nothing of Discord. Applies `paper-adapter` plus shadow, bundling relocated JDA.
- **`platform:essentials-mail-converter`** (`…essentials.convert`) — feature module importing EssentialsX
  mailboxes into first-party mail, once, through `/essmailconvert`. Registers no sink, processor,
  renderer, category or schema: a migration tool, deletable once run. `paper-adapter` plus `compileOnly`
  on `net.essentialsx:EssentialsX`.

### Build conventions

`buildSrc/src/main/kotlin/` holds precompiled convention plugins, and `HostShading.kt` — the host's
relocation prefix and package list, shared so the host's `shadowJar` and every shading adapter cannot
drift apart (see "Discord adapter" for why an adapter relocates packages it does not bundle).

- `player-notifications-conventions` — Java 25 toolchain, UTF-8, JUnit 5, Paper/mavenLocal/mavenCentral.
  Applied by every module.
- `paper-adapter` — for feature modules. Base conventions plus `compileOnly(project(":platform:paper-plugin"))`
  (compile against the host, never bundle it) **and `testImplementation` on the same project**
  (`compileOnly` reaches neither `compileTestJava` nor the test runtime), plus the
  `maven.democracycraft.net/snapshots` repo. It declares a consumable **`moduleJar`** configuration whose
  artifact is `jar`, swapped to `shadowJar` lazily under `plugins.withId("com.gradleup.shadow")` — so a
  shading adapter publishes its `-all` jar and the host's `installFeatureModules` never learns which
  adapters shade. Because `paper-plugin` exposes `api`/`compileOnlyApi` dependencies, this one dependency
  transitively gives adapters `api`, `core`, `plugin-infrastructure` and `paper-api`.

Precompiled conventions apply siblings with `id("player-notifications-conventions")`, not the backtick
accessor. Version comes from the root `gradle.properties`.

### plugin-infrastructure dependency

`com.minecraftcitiesnetwork:plugin-infrastructure` (from `https://maven.democracycraft.net/snapshots`)
provides `…pluginInfrastructure.modules`, `.sql`, `.configurate` and `.util`. A normal repo dependency,
**not** an `includeBuild`. The group id and repo host disagree (`minecraftcitiesnetwork` vs.
`democracycraft`) — that is upstream's naming, not a typo; verify against an actual import.

## Module system

Feature modules are jars in `<dataFolder>/modules/`, each with a `module-manifest.yml` and an entry class
implementing `PluginModule<T extends Plugin>` (or extending `SimplePluginModule`). The host drives them
with a `ModuleLifecycleManager` (`start()` on enable after the service is registered, `stop()` first on
disable). A module's `initialize` receives the host plugin and typically resolves `NotificationService`
from the `ServicesManager`.

Manifest keys are the `ModuleManifest` record's component names in **kebab-case** (Configurate's
`NamingSchemes.LOWER_CASE_DASHED`):

```yaml
module-name: discord-adapter
entry-class: io.github.md5sha256.playernotifications.discord.DiscordModule
author: md5sha256
expected-plugin-class: io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin
reloadable: false
```

A camelCase key does not fail — it silently does not match and the component deserializes to `null`,
invisible except for a single-word key like `author`. `ModuleLoader.loadModulesFromDisk` then NPEs on
`manifest.expectedPluginClass().equals(...)`, aborting `onEnable`. The same rule governs every
`@ConfigSerializable` record here (`prune-interval-seconds`, `default-media`, `bot-token`,
`uncategorized-label`). `expectedPluginClass` must equal the host's runtime FQCN or the module is skipped.

**A module can own its own schema.** It cannot add to `MariaSchemaMigrator.DEFAULT_MIGRATIONS` or
`MariaDatabase`'s mapper list (both fixed), but `SqlSessionWrapper#session()` exposes everything needed:
`getConnection()` for its own DDL, `getConfiguration().addMapper(...)` (guarded by `hasMapper`),
`getMapper(...)`, and the `UUID` ↔ `BINARY(16)` handler already registered on the shared `Configuration`.
`platform:discord-adapter` does exactly this over the **host's** pool and session factory — no second pool,
no second copy of the credentials. Three rules if you follow it: load scripts through **the module's own**
class loader (core's cannot see inside a module jar); registering a mapper mutates the host's shared
`Configuration`, the narrowest scope MyBatis offers; and **relocate MyBatis to the host's shaded prefix**
in the module's `shadowJar` (`HostShading.ADAPTER_PACKAGES`), or every call and annotation names a class
that does not exist at runtime.

## Rendering & delivery media

Design doc: `docs/superpowers/specs/2026-07-27-notification-renderers-design.md`.

Payload *rendering* and medium *delivery* are separate axes, so a payload is converted **once** and fans
out to any number of media. Registrations are **N + M**, not N × M: the Discord DM sink landed without
touching a single payload.

- A payload author registers a `NotificationRenderer<T>` and never writes per-medium or preference logic.
  `NotificationService#registerJsonRenderable(dataType, type, renderer)` is the one-call form (mapping +
  reflective JSON serializer + renderer), mirroring `registerJsonPayload` on the processor path. It
  registers **no** processor by design — an explicit processor wins dispatch and would bypass preferences
  and sinks entirely. `paper.diagnostic.TestNotificationRenderer` is the in-tree example.
- A medium owner registers one `NotificationSink`.
- `RenderingProcessor<T>` resolves `preferredMedia(target, dataType)`, renders once, and delivers to each
  medium's sink. A medium with no registered sink is logged at `fine` and skipped.

**Dispatch precedence in `NotificationDelivery`:** an explicitly registered `NotificationProcessor` always
wins (keeping a bespoke processor working unchanged, preferences included). **One in-tree type uses this
deliberately** — `mail`'s RETAIN processor. Otherwise a registered `NotificationRenderer` dispatches
through `RenderingProcessor` built with the notification's `notifPayloadType`; otherwise the notification
is logged and retained. `NotificationDelivery` has a 3-arg constructor (no rendering) and a 5-arg one;
categories play no role.

**Fan-out is MARK_SEEN-wins:** any `DELIVERED` marks the notification seen for that target — **not**
deleted. It stays readable until dismissed or expired. `selectDueByPlayer` carries `AND t.seenTime IS
NULL`, so a seen notification is never pushed again. Consequences:

- **Partial delivery is silent but not lossy.** Chat succeeding while Discord transiently fails marks it
  seen and Discord never gets it — but it remains in the inbox. Per-medium tracking is deferred; retrying
  is not a workaround, since chat is not idempotent.
- When **nothing** was delivered and some medium returned `UNSUPPORTED`, a `warning` is logged — a player
  whose only medium is permanently unreachable would otherwise accumulate unread notifications silently.
- `RenderingProcessor` drops `SILENCED_MEDIUM` and returns `RETAIN` if nothing deliverable remains,
  leaving it **unread**. That is the per-`dataType` silence only; the player-level mute never reaches here
  because `NotificationDelivery.deliver` returns first. Either way it means "do not interrupt me", not
  "do not tell me".
- A sink throwing is caught, logged and treated as `UNREACHABLE`, so one broken sink cannot abort the rest.

`RenderableNotification` holds **no `Player` and no `Audience`** — Discord DM reaches absent players.
`Component` is the lingua franca and non-Minecraft sinks serialize it down, so bodies must not rely on
in-game-only affordances such as click events. Actions/buttons are intentionally **out of scope** (a
dialog button and a Discord button share no execution model), so dialogs are read-and-dismiss.

### Join delivery

Design doc: `docs/superpowers/specs/2026-07-30-join-delivery-trigger-design.md`.

`paper.JoinDeliveryListener` delivers a joining player's due, **unseen** notifications, gated by
`deliver-on-join` and delayed by `join-delivery-delay-seconds`. It also sends one line naming the unread
count and pointing at `/notifications`, **outside** that gate and delay — a player who turned push off
still needs to know something arrived, which is the whole point of separating push from the inbox. A zero
count sends nothing, and so does a **player-level mute**: the announcement lines sit behind a
package-private `announcements(UUID) : List<Component>` returning empty when `isMuted`, which is also what
makes the gate unit-testable without a live `Player`. It is a **trigger** for the existing delivery loop,
like the prune task — not a registry extension, which is why it lives in the Paper bootstrap. A toggle
inside `NotificationDelivery` was rejected: `core` has no event surface, and a flag there would also gate
`/notifications test`, which must keep working.

- **Always registered, gated internally**, so `/notifications reload` can flip it through
  `reloadSettings(boolean, long)` with no `HandlerList` surgery. `enabled`/`delaySeconds` are `volatile`
  — the same reload idiom as `reloadDefaultMedia` and `reloadCategories`.
- **Delivery is scheduled async** (`runTaskAsynchronously`, or `…Later…` with `delaySeconds * 20` ticks):
  mappers and preference lookups do blocking JDBC and `DiscordDmSink` refuses the main thread outright.
- **The scheduled body re-checks `isOnline()` and the toggle.** An offline `Audience` still makes
  `ChatSink` report `DELIVERED`, so delivering to a player who quit mid-delay would mark it seen unread.
- A `RuntimeException` from `deliver` is logged at `WARNING` and swallowed — an uncaught throw in a
  scheduled task is reported with no useful attribution and the player did not ask for a diagnostic.
- **The delay is not cancellable.** Join → quit → rejoin inside the window schedules two tasks; the second
  finds nothing due. Two queries, judged cheaper than a per-player pending-task map.
- **`onJoin` itself is unverified** — `PlayerJoinEvent` and the scheduler need a live server.
  `JoinDeliveryListenerTest` covers the gate and the reload swap, which is why the gate lives in a
  package-private `deliver(UUID)`. Manual checklist: Task 3 of
  `docs/superpowers/plans/2026-07-30-join-delivery-trigger.md` — **not yet run**.

### Discord adapter

Design doc: `docs/superpowers/specs/2026-07-29-discord-adapter-design.md`.

A feature module registering `DiscordDmSink` under **`discord-dm`**. Once the jar is in `modules/` and
configured, "Discord DM" appears in the preference dialogs automatically — they enumerate
`registeredMedia()`. `DiscordMedia.CHANNEL_PING` is reserved but unimplemented; the key exists so the DM
sink is not squatting a generic `"discord"` name and a channel sink can be added without migrating a
preference row.

- **Owns its own schema**: `sql/discord/V1__discord_account_link.sql` (in the *module* jar),
  `schema.DiscordSchemaMigrator`, `schema.DiscordMigrationStep`, its own `discord_schema_version` table
  and its own entity/mappers. `grep -ri discord core/src/main` returns nothing. Only the **connection** is
  borrowed via `plugin.database()`. `initialize` migrates **before** constructing the store, and a failure
  fails module startup. Two traps this hit: scripts load through *this module's* class loader, and `--`
  comments are stripped before splitting on `;`.
- **DiscordSRV is genuinely optional.** `link-providers` defaults to `[embedded, discordsrv]`; `embedded`
  resolves the plugin's own table and needs no other plugin. Listing both is the migration setting: new
  links land in the embedded table and win, old DiscordSRV links keep resolving.
- **The link flow.** `/notifications link discord` (plus `… status` and `/notifications unlink discord`)
  issues a 6-character single-use code; the player sends `/link <code>` to **this module's** bot and
  `LinkSlashCommandListener` writes the row. `DiscordLinkFlow` holds every decision and message — the
  provider and the JDA listener are logic-free adapters over it, because neither can be unit tested.
  `DiscordLinkFlow.LINK_COMMAND`/`UNLINK_COMMAND` are the single source for the names replies quote.
  Codes live in `LinkCodeService`, in memory only: a code's life is minutes, so a restart costs a re-run
  whereas persisting it would mean a migration for state designed to expire. `redeem` folds expiry into
  `UNKNOWN_CODE` — it removes the expired entry, so nothing remains to tell the two apart.
- **Redemption is a slash command, not a DM read.** Interactions need no gateway intent, so `DiscordBot`
  keeps `createLight` with an empty intent set; reading DM *content* would need privileged
  `MESSAGE_CONTENT`. `DiscordBot.start` takes listeners attached at build time so none can miss ready —
  the command is registered from `onReady`, since `awaitReady()` is never called. Global, with
  `setContexts(BOT_DM, GUILD)`.
- **Linking is a registration, not a command.** `DiscordAccountLinkProvider` (a thin delegate over
  `DiscordLinkFlow`) registers in the host's `AccountLinkRegistry` under `DiscordMedia.LINK_PROVIDER_KEY`
  (`"discord"`) — only when `link-providers` lists `embedded`. The module owns **no** command and **no**
  permission: the host's `/notifications link|unlink` subtree is gated by `playernotifications.command.link`.
  `shutdown` unregisters the provider (flag-guarded, idempotent); the node stays and the host replies
  "Discord linking is not available on this server". This replaced a module-owned `/discordlink` whose
  teardown needed `Bukkit.getCommandMap().getKnownCommands()` surgery.
- **A chain with nothing available warns at startup.** `ChainedDiscordAccountProvider.reportAvailability`
  logs one line per provider and a `WARNING` naming `discord-dm` when none can answer; that case
  previously produced no log until a notification was dropped.
- **It runs its own JDA bot with its own token.** DiscordSRV relocates its bundled JDA to
  `github.scarsz…`, so its instance is not type-compatible with upstream `net.dv8tion`. DiscordSRV is a
  **link source only**.
- **The shading is load-bearing in both directions.** `shadowJar` relocates every *bundled* package under
  `…discord.libraries`; modules load parent-first through `new URLClassLoader(jarUrl, hostClassLoader)`
  and the host already shades Jackson, so an unrelocated copy would collide. **Verify the relocation set
  against the built jar after any dependency bump** (`unzip -l …-all.jar`, look for classes outside
  `io/github/md5sha256/`). It also relocates packages it does **not** bundle — MyBatis and Configurate —
  to the *host's* prefix, because it compiles against the unrelocated coordinates but resolves them at
  runtime from the host jar, where only the relocated names exist; without that, startup dies on
  `NoSuchMethodError: SqlSessionWrapper.session()` and `@Select` is not the annotation MyBatis looks for.
  The set is not duplicated across build scripts — `HostShading` owns it, the host applies `PACKAGES` and
  an adapter `ADAPTER_PACKAGES` (the same list minus Jackson, which the module bundles).
- **`paper-plugin.yml` carries a soft `dependencies: server: DiscordSRV` with `join-classpath: true`** —
  Paper plugins are classloader-isolated by default and the module's loader is parent-first onto the
  host's, so without it `DiscordSrvAccountProvider` silently reports itself unavailable.
- **The provider swap point.** `DiscordAccountProvider` (`providerKey`, `discordIdFor`, `isAvailable`,
  plus `default playerFor(long)`) resolves UUID → Discord id. `link-providers` is an ordered key list
  resolved against `DiscordAccountProviderRegistry` and wrapped in `ChainedDiscordAccountProvider` (first
  link wins; unknown key warned and skipped; unavailable skipped un-queried; a throwing provider logged
  and skipped so it cannot mask a working one). Two ship: `EmbeddedDiscordAccountProvider` (always
  available) and `DiscordSrvAccountProvider`. Adding one is a class, a registration and a config line.
- **Rendering.** `DiscordMarkdownSerializer` drives Adventure's `ComponentFlattener` to Discord markdown
  (bold, italic, underline, strikethrough, obfuscated → spoiler), escaping ``\ * _ ~ | ` >`` and dropping
  colours — Discord message text cannot be coloured. `DiscordMessageFactory` builds `MessageCreateData` in
  one of three `message-format`s (`embed`|`markdown`|`plain`) and truncates to Discord's limits (title
  256, description 4096, content 2000) *before* JDA's builders, which throw rather than trim.
- **Shutdown blocks on purpose.** `DiscordBot.shutdown()` calls `jda.shutdown()` then `awaitShutdown`
  (5s, escalating to `shutdownNow`): `shutdown()` alone only *requests* teardown, and JDA's websocket
  thread then lazily loads `WebSocketClient.onShutdown`, failing with `IllegalStateException: zip file
  closed` if the module's loader is already closed. Do not make this fire-and-forget again.
- **An unloaded DiscordSRV poisons this module's class loading — known, unfixed, operational.** The
  *other* `zip file closed`, and the one you will be shown: a stack trace on a healthy server blaming our
  relocated JDA with `PluginClassLoader.findClass` for **DiscordSRV** underneath. Read it as delegation,
  not use. The chain: `join-classpath: true` puts DiscordSRV's loader in our group → the module's loader
  is parent-first → an **unloaded** DiscordSRV (not merely disabled; a plugin manager or `/reload` closes
  the loader) answers a routine "not found" by *throwing*, aborting the load instead of falling through.
  - **Independent of `discord.yml`** — the group is built before any config is read.
  - **The class being loaded is irrelevant**; JDA only appears because a Discord-initiated gateway
    reconnect was the next thing needing an unloaded class. It breaks host-class loads the same way.
  - **Fix: remove the DiscordSRV jar from `plugins/`.** `paper-plugin.yml` now carries **two**
    `dependencies: server:` entries (DiscordSRV and Essentials), so both are sources of this exposure.
  - Two code fixes were designed and **deliberately not taken**: dropping `join-classpath` and driving
    `DiscordSrvAccountProvider` reflectively through DiscordSRV's own loader, and a `ModuleLoader`
    fallback retrying the module's own jar when the parent chain *throws*. Reopen only with the user.
- **Result mapping.** No linked account → `UNSUPPORTED`; `CANNOT_SEND_TO_USER`/`UNKNOWN_USER` →
  `UNSUPPORTED`; not connected, rate-limited, timed out or any other exception → `UNREACHABLE`.
  `DiscordDmSink` refuses the main thread (warn + `UNREACHABLE`).
- **`discord.yml`** (bundled in the *module* jar, written to `modules/discord.yml`): `bot-token` (blank
  refuses startup), `message-format`, `embed-color`, `delivery-timeout-seconds` (`long`, not a
  `Duration`), `link-providers`, `link-code-expiry-seconds` (default 600), `commands-enabled` (boxed
  `Boolean`, default `true`). Loaded by `ModuleConfigs`, which reproduces the host's
  copy-defaults-then-merge idiom because `copyDefaultsYaml` is private and reads the *host* jar. Merge
  only *adds* absent keys, so an upgrade does not silently switch a server onto `embedded`.
- **Untested by automated tests:** `DiscordBot`, `JdaDiscordMessenger`, `DiscordSrvAccountProvider`,
  `DiscordModule`, `LinkSlashCommandListener`, and every `command` class touching a JDA event
  (`SlashCommandRegistrar.onReady`, `MailCommandListener`, `NotificationsCommandListener`,
  `InboxInteractionListener`, `PreferenceInteractionListener`) — they need a live server, a real token and
  a Discord account. Three manual checklists exist and **none has been run**: Task 8 of
  `…/plans/2026-07-29-discord-adapter.md`, Task 7 of `…/plans/2026-07-30-embedded-discord-linking.md`,
  Task 13 of `…/plans/2026-08-20-discord-slash-commands.md`. Everything else is unit tested — including
  `DiscordLinkFlow` and every `command` view class, which is where the logic deliberately lives.

### Discord slash commands

Design doc: `…/specs/2026-08-20-discord-slash-commands-design.md`. Plan:
`…/plans/2026-08-20-discord-slash-commands.md`.

A linked player can read and send mail, read and dismiss notifications, and edit preferences from
Discord. Package `discord.command`. A **second client** onto the same host APIs, not a registry
extension, so it follows linking's rule: every decision in a plain unit-tested class (`InboxView`,
`DiscordMailService`, `PreferenceView`, `ComponentIds`, `DiscordUserResolver`, `InboxReplies`, both
message factories), with the JDA listeners logic-free.

- **Registration has exactly one owner, and that is load-bearing.** `JDA#updateCommands()` *replaces* the
  whole global set, so a second registering listener silently deletes the first's commands, with the
  loser decided by event ordering. `SlashCommandRegistrar` owns `onReady` and the single call for
  `/link`, `/mail` and `/notifications`. **Do not add an `onReady` anywhere else in this module.**
- **Names match the in-game ones** — no root literal. `/mail send|list|read|delete|clear` and
  `/notifications list|read|delete|clear|prefs|mute|unmute`, every reply ephemeral, all
  `setContexts(BOT_DM, GUILD)`. A bare `/mail` falls back to `list`.
- **`/mail send <player>` takes the message in a modal**, not a slash option — there is no `compose`
  subcommand any more, and a slash option is a single line with no room to review. The modal is the
  *initial* response (a modal cannot follow a defer), so the linked check happens on submit in
  `onModalInteraction`.
- **An entry's detail carries Delete / Mark as unread / Back**, on both surfaces. Opening marks it seen,
  so *Mark as unread* is how a player undoes that; it edits back to the listing (the detail embed shows
  no read state, so the row's bullet is the only place the change is visible). On `/notifications` this
  makes the notification due again — it will be pushed on the next join.
- **Entry indexing is stateless.** `page` is an explicit option defaulting to 1, and a component's custom
  id carries its own page and notification key (`ComponentIds`, `pn|surface|action|args`, capped at 100
  characters). No per-player cursor, so the stale-cursor bug class cannot arise and nothing needs dropping
  on disconnect. An out-of-range `entry` is **rejected naming the page's real size**, not clamped: the
  inbox changed under the player, and acting on the wrong mail is worse than a second command.
- **`InboxView` is constructed twice**, filtered to `mail`/"Mail" and unfiltered/"Notifications" — the
  same shape as the host's two routers. The surface key (`inbox` vs `inbox-mail`) is in every component
  id, so a mail listing's click can never be answered by the unfiltered view. Its page size is an
  `IntSupplier` over `PlayerNotificationsPlugin#inboxPageSize()`, so `/notifications reload` reaches it.
- **Mail sent from Discord is always plain text.** `DiscordMailService` passes `MiniMessage#escapeTags` as
  `MailRecipients.resolve`'s formatter — the importer's rule, for the same reason: the text passed no
  permission gate. `MailFormatting` and its fifteen permissions are **not** consulted; there is no
  `CommandSender` to check them against. Recipient resolution matches `MailCommand`, `hasPlayedBefore()`
  included, and the arrival notice fires through `MailNotifier` unchanged — but only *after* the enqueue
  succeeds, since announcing mail that was never stored sends the recipient to an empty inbox.
- **The mail arrival notice's DM carries a "Read mail" button**, a shortcut for `/mail read 1`.
  `command.MailNoticeButton` builds the row and `DiscordDmSink` adds it; nothing else changes. Discord-only
  by construction, so the notice stays medium-neutral and no other sink learns anything. Its id is
  `pn|inbox-mail|read|1|1`, and **`read` is the one button action answered with a new ephemeral reply
  rather than `deferEdit`** — it sits on an ordinary DM, so editing would consume the notice.
- **Preferences are components, not a modal.** A modal opens only in response to an interaction and
  submits once, so it cannot re-render as a player toggles. `/notifications prefs` posts an ephemeral
  message with a data-type select, a media multi-select (min 0) and four buttons: Apply / Discard /
  **Silence everything** / **Silence this type**. Modals carry only prose. A *Reply* button is
  deliberately absent — `InboxView.Row` carries the rendered title, not the sender's name.
- **The staged session is the host's `PreferenceEditSession` in a session manager this module owns** —
  not the host's: the dialogs' "Back abandons this screen's checkboxes" semantics assume one owner, and an
  Apply from Discord would otherwise commit a half-finished in-game edit invisibly. Consequence, accepted:
  a staged edit on one surface is invisible on the other, last Apply wins. Apply calls
  `applyChanges(player, changes, Set.of(), stagedMute)` — an always-empty reset set (no player-facing way
  back to the server default here either) and a `stagedMute` always `null` from this surface, passed
  rather than hardcoded so a session staged elsewhere would commit with the rest. `/notifications
  mute|unmute` stay immediate.
- **The screen edits silences; the mute is not on it.** `SILENCED_MEDIUM` used to be a media *row*, which
  meant ticking "Silence this type" **and** "Chat" silently discarded the chat tick. The key is now
  filtered out of an incoming selection and silencing is two buttons. **`Silence everything` is one-way
  once applied** — a silence writes explicit rows and nothing returns a type to the server default, so
  Discard *before* Apply is the only way back; staging is what makes that safe. The player-level mute
  belongs to `/notifications mute|unmute`; the screen only **reports** it (a muted player would otherwise
  read correct-looking media and receive nothing). The embed likewise names a silenced type, since a
  silence and an unconfigured type both show as an empty select. A screen with **no** registered medium
  omits the media row rather than sending an empty select, which Discord rejects — the same shape of bug
  as the empty `multiAction` dialog.
- **The reverse lookup is on the provider chain, not the store** (`default playerFor(long)`, so a provider
  compiled against the old interface still builds), which is what makes the commands work on a
  `link-providers: [discordsrv]` server. An unresolved user is told to run `/notifications link discord`.
- **`commands-enabled: false` leaves `/link` registered** and withholds the other two, for a server that
  wants Discord as a delivery medium only.
- **Type labels come from the host**: `PreferenceView` takes a `Function<String, String>` seam (so it
  stays testable with no host plugin) and `DiscordModule` wires `plugin.typeNames()::plainName`. A rename
  in `type-names.yml` reads the same here, minus colour.
- **Discord's 25-option select cap** truncates the data-type and media lists with a warning naming the
  overflow. An empty page carries no components at all. A listing or preference message stops working when
  its interaction token expires (15 minutes) and answers a click with "This message has expired".

## Mail

Design doc: `…/specs/2026-08-10-first-party-mail-design.md`. Plan: `…/plans/2026-08-10-first-party-mail.md`.

Player-to-player mail (`/mail send <player> <message>`, `/mail` to read) replaced the deleted
`platform:essentials-adapter`. Mail is a `dataType` (`"mail"`, `api.mail.MailPayload.DATA_TYPE`) — a
payload, not a medium — reusing the existing machinery (storage, targeting, paging, seen/unread, pruning
minus expiry) rather than a bespoke table.

**Mail is stored, but never delivered — on purpose.** `onEnable` registers a processor for `MailPayload`
that always returns `RETAIN`:

```java
service.dataTypeRegistry().registerProcessor(MailPayload.class,
        (payload, target) -> NotificationDisposition.RETAIN);
```

This exploits dispatch precedence: an explicit processor wins over the renderer/sink path and bypasses
preferences. For every other type that precedence is a *quirk*; for mail it is the required behaviour,
because nothing about a mail's *content* should be pushed through chat or Discord — mail waits until the
player asks for it. **Do not "simplify" this processor away or fold it into the renderer path.**

`RETAIN` rather than `MARK_SEEN` matters too: the loop never stamps `seenTime`, so a mail becomes seen
only when opened in `/mail`. Otherwise the unread count and the bold row would lie.

**A `MailRenderer` is still registered** (`registerJsonRenderable(...)`) — not for delivery, which the
processor short-circuits, but because `InboxEntryRenderer` resolves payload → renderer on **read**.
Without it `/mail` would show the "unrenderable payload" placeholder. It titles the notification
`"Mail from <senderName>"` with `Component.text(...)` — a player name is not a formatting document, and
parsing it would smuggle tags past the message's permission gate — and renders the message body with
**MiniMessage**. That inverts the original rule, because the stored string is no longer raw player input:
it is what `MailFormatting.sanitize` produced from only the tags the sender held a permission for. The
parse is guarded all the same — MiniMessage *throws* on a legacy `§` code, and a mail stored before this
change can contain one, so a `RuntimeException` falls back to literal text. It ignores `target`; a mail
reads the same to everyone.

**The mail preference rows are real, but they route the arrival *notice*, not the mail.** A processor
bypasses preferences, so a `mail` row cannot affect the mail itself. `categories.yml` ships a `mail`
category all the same, and its checkboxes answer a real, different question: "where do I want to be told
that mail arrived". Silencing `mail` means "don't tell me when mail arrives"; the mail still lands unread.

**The arrival notice** is exactly one line, verbatim, never templated with a sender, count or preview:

> **You have new mail!**

It is **not** a notification — never enqueued, never stored (that would put a second row in the inbox
announcing the first) — but it *is* routed through the ordinary sink machinery. `paper.mail.MailNotifier`
is the whole of this, and the single instance it delivers is published as `MailNotifier.ARRIVAL_NOTICE` so
a sink can recognise it and add an affordance — the Discord adapter's "Read mail" button. Recognition is
an **identity** check, not a wording comparison: matching text would mean rewording silently dropped the
decoration and would decorate any notification rendering the same way. It returns immediately when the
recipient is **muted**, and otherwise resolves `preferredMedia(recipient, mail)`, drops `SILENCED_MEDIUM`,
and delivers a fixed `RenderableNotification` to each sink, catching a throwing sink. It deliberately does
**not** reuse `RenderingProcessor` — that renders a *stored* notification's payload and reports a
disposition, and the notice has neither; sharing it would mean inventing a fake notification. It fires
from two places: `MailCommand` after a successful `send` (unconditionally, not gated on the recipient
being online — a Discord DM reaches them either way), and `JoinDeliveryListener`, which adds a
mail-specific line driven by `unreadCount(playerId, mail)`, sending nothing at zero, **outside** the
`deliver-on-join` gate and delay. A player can hear about the same unread mail twice — accepted, since
both are true statements about mail that is still unread.

**Sending.** `paper.mail.MailSender.send(sender, senderName, recipient, message[, sentAt])` builds and
enqueues a `MailPayload`: key `"mail-" + UUID.randomUUID()` (per-send random, since concurrent sends must
not collide), `notifExpiryTime` **`null`** — mail is correspondence, and correspondence evaporating on a
prune tick is a bug report waiting to happen (the cost is an unbounded mail inbox). `senderName` is
captured at send time and stored, because `MailRenderer` runs on read, potentially much later, and
`getOfflinePlayer(uuid).getName()` blocks and returns `null` for an unseen player; the sender UUID is kept
for a possible reply command. `MailSender` does no validation — that is `MailCommand`'s job, so a module
calling it programmatically gets the mail sent rather than an exception across a module boundary.

**`paper.mail.MailRecipients.resolve(name, message, resolver[, formatter])`** is the testable half of
`/mail send`'s argument handling, taking a `Function<String, UUID>` seam: an unknown name is rejected; a
blank or over-`MailPayload.MAX_MESSAGE_LENGTH` (256) message is rejected **without truncation**.
`MailCommand` resolves the recipient itself on the async thread — online by name first, else
`getOfflinePlayer(name)` accepted only when `hasPlayedBefore()` (that method fabricates a UUID for any
string) — then calls `resolve` for the message rule. The four-argument overload runs a
`UnaryOperator<String>` (in production `MailFormatting.sanitize`) **after** the length check, so the limit
bounds what the sender typed rather than what it serialises to, and rejects a blank formatter result.

### Mail formatting

`/mail send`'s message is **MiniMessage**, with **one permission per tag group** under
`playernotifications.command.mail.format.` (`MailFormatting.PERMISSION_PREFIX`). **All fifteen are
`default: op`** — a formatted mail is something an operator opts a rank into, so an ordinary player's
message stays literal until a server says otherwise. The split is fine-grained anyway, so a server can
grant the cosmetic groups (`color`, `decoration`, `gradient`, `rainbow`, `reset`, `newline`) without
`click`/`hover` (which attach a runnable command or payload to text in someone else's inbox) or
`score`/`nbt` (server-side state); `font` sits with those because a client-side font can render text
misleadingly. `MailFormatting.Group` carries no "granted by default" component — it would be the same
value fifteen times — and `MailFormattingTest` asserts the defaults against `paper-plugin.yml` itself.

**Parsing happens at send time, not render time**, because the permission check needs the *sender*.
`MailCommand` builds the resolver with `MailFormatting.resolverFor(sender::hasPermission)` **on the
command thread** (Bukkit permission state belongs to the main thread) and closes over it; `sanitize`
deserializes with only those tags, re-serializes with standard `MiniMessage`, and *that* string is stored.
A forbidden tag was never registered, so it survives as text and is escaped on the way out — the recipient
sees `<click:run_command:/op me>` verbatim rather than the sentence silently shortened. `sanitize` returns
`""` when nothing readable survives, judged on the *rendered* text: `<red>` alone still serialises back to
`<red>`, so a blankness check on the stored string would trip `MailPayload`'s constructor instead.

Consequences: **permissions are evaluated once, at send time** — revoking `…format.click` later does not
neutralise sent mail, deliberately, since the alternative makes an old mail's appearance depend on the
sender's current rank. And `MAX_MESSAGE_LENGTH` bounds the typed message, not the stored one. Design doc:
`…/specs/2026-08-20-console-mail-and-minimessage-design.md`.

**`/mail` is a second `InboxRouter` instance**, not the shared one. `InboxRouter` takes a
`@Nullable String dataTypeFilter` and a `Component title`, threaded into every
`inbox`/`unreadCount`/`markAllSeen`/`dismissSeen` call; the plugin constructs one with `null`/
"Notifications" and one with `mail`/"Mail". Two instances rather than one filtered router, because the
page cursor and last-listed-index map are per-screen state: `/mail list 2` must not make a stale
`/notifications read 1` resolve against the mail page. `InboxQuitListener` holds a `List<InboxRouter>`.

**The `/mail` command table** (`paper.command.MailCommand`, every branch off the main thread; player-only
**except `send`**, which acts on someone else's inbox and so runs for any `CommandSender` — a console send
is attributed to `MailSender.SERVER_SENDER` (nil UUID) / `SERVER_NAME` ("Server")):

| Command | Behaviour |
|---|---|
| `/mail` | opens the mail inbox dialog (filtered `InboxDialog`, titled "Mail") |
| `/mail send <player> <message>` | sends mail (greedy string, MiniMessage) and fires the notice |
| `/mail list [page]` | chat fallback, `#<entry> [Time] [Sender] <content>` (`MailChatRow`) |
| `/mail read <entry>` | reads entry `<entry>` of the last-listed page; marks it seen |
| `/mail delete <entry>` | deletes entry `<entry>` |
| `/mail clear` | `markAllSeen` + `dismissSeen`, both filtered to `mail` |

Two command permissions, both `default: true`: `playernotifications.command.mail` gates the root, and
`…mail.send` is an **additional** requirement on `send` only — so a server can make mail read-only for a
rank, while revoking the root hides `/mail` entirely. The fifteen `…mail.format.*` nodes are separate from
both.

**Nothing in this feature has been exercised on a live server** — see "Current state". Everything
unit-testable is tested: `MailSenderTest`, `MailRecipientsTest`, `MailNotifierTest`, `MailRendererTest`,
`MailFormattingTest`, and against a real MariaDB `FilteredInboxTest` and `core.MailNotDeliveredTest` (the
regression test for the central claim: a mail and a renderable notification enqueued together, only the
non-mail one reaches a recording sink, the mail's `seenTime` stays null and it is still in
`inbox(player, 1, 10, "mail")`).

## Broadcast

Design docs: `…/specs/2026-08-21-broadcast-command-design.md` (the transient original) and
`…/specs/2026-08-29-persistent-offline-broadcast-design.md` (`--chain`, `--persistent`, `--offline`,
`--limit`). Plans alongside both.

```
/broadcast <content> [--perm <node>]… [--chain and|or] [--persistent] [--offline] [--limit <n>] [--bypass]
```

An announcement to matching players through each one's preferred media. Op-only
(`playernotifications.command.broadcast`) and usable from the console. **None of the flags carries a
permission of its own** — a second gate on a flag of an op-only command distinguishes nothing.

**Two independent axes.** `--persistent` decides *whether the message is stored*; `--offline` decides
*who it goes to*. Default (neither) is the original behaviour exactly: transient, online-only, never
written to the database.

- **Transient (no `--persistent`)** — fanned out immediately and **never written to the database**. Its
  audience is "whoever is online and holds the permission right now", which does not survive being
  written down: a player joining ten minutes later was never a recipient. Follows `MailNotifier`'s
  precedent — a `RenderableNotification` with no payload, key or disposition. Nothing enqueued, so
  nothing prunable, readable in the inbox, or recoverable once missed.
- **Persistent (`--persistent`)** — a real `Notification` row, so it lands in the inbox, is pushed on the
  recipient's next join, and stays readable until dismissed. One notification carrying **every recipient
  in a single `NotificationTarget`**; `notifExpiryTime` is `null`, matching mail.

**`--offline` requires `--persistent` *and* at least one `--perm`**, both rejected naming the reason.
The first because `ChatSink` reports `DELIVERED` for an absent player — the trap `JoinDeliveryListener`
re-checks `isOnline()` to avoid — so a transient offline broadcast would claim a success nobody saw. The
second because the permission filter is the only thing bounding the *shape* of an offline audience;
without it, `--offline` addresses every player the permission backend has ever heard of.

**`--limit <n>` bounds the *size*.** Absent means unlimited on both paths, so existing commands are
unchanged and the flag is purely opt-in. **`--limit 0` is rejected**, not treated as unlimited: zero
reads as "send to nobody", and overloading it would make the most dangerous setting look like the
safest. There is no magic value. The check runs after `resolve` and before anything is enqueued or
delivered, and the reply **names the resolved count**, which is what the operator types back as
`--limit` to confirm — so that key must keep quoting `<count>` through any rewording.

**Mute and silence both apply; `--bypass` overrides both together** — for the announcement class that is
not a subscription, a restart warning. There is deliberately no flag overriding only one. On the
persistent path `--bypass` reaches only the recipients the push skipped (`Broadcaster.suppressed`), who
keep their **unread** inbox copy: they were reached out of band, and the stored record is what makes the
message recoverable. It does **not** override the mute for the stored copy — the gate sits above the
whole delivery loop in `NotificationDelivery.deliver`, and no per-notification flag can reach it. A
bypassed recipient can therefore be told twice (now, and on next join while still unread), the same
trade `JoinDeliveryListener`'s mail line already accepts.

**`broadcast` is now a fully registered renderable type**, not a mapping-only key. `onEnable` calls
`registerJsonRenderable(BROADCAST_DATA_TYPE, BroadcastPayload.class, new BroadcastRenderer(messages))`,
so a stored broadcast round-trips and reads properly. **Still no processor**, deliberately: an explicit
processor wins dispatch and would bypass preferences and sinks entirely — right for `mail`, wrong here.
The mapping is also what makes `broadcast` enumerate in `dataTypes()`, which the preference dialogs walk,
so a player can silence it. `categories.yml` ships a `broadcast` category.

The types, in `paper.broadcast` unless noted — every decision in a class with no Bukkit dependency, the
command reduced to wiring, the same split `/mail` uses:

| Type | Holds |
|---|---|
| `BroadcastArguments` | The flag syntax and every rejection case. Brigadier cannot express repeated flags, so `<content>` is one greedy string and the flags are parsed out of it. |
| `BroadcastAudience` | Permissions + chain in, UUIDs out. **Called off the command thread.** |
| `OnlineBroadcastAudience` | Online players. Marshals to the main thread *itself*. |
| `OfflineBroadcastAudience` | Delegates to `PermissionLookup`, drops anyone online. **Blocks.** |
| `PermissionLookup` | "Who holds these nodes", set-valued. The one question Bukkit cannot answer. |
| `LuckPermsPermissionLookup` | The only implementation. The three-stage group resolution below. |
| `LuckPermsBinding` | The isolation guard. **Names no LuckPerms type at all.** |
| `BroadcastRecipients` | The AND/OR match, over a `Predicate<String>` seam. Online path only. |
| `Broadcaster` | The transient per-recipient sink fan-out, plus `suppressed`. |
| `PersistentBroadcaster` | Enqueue once, push the online, bypass the suppressed. |
| `BroadcastRenderer` | Renders a stored broadcast for the inbox. |
| `paper.command.BroadcastCommand` | The Brigadier node. Wiring only. |

- **Parsing rule.** Everything before the **first** flag token is the content, taken from the raw string so
  interior spacing survives; the remainder must be value-taking flags (`--perm`, `--chain`, `--limit`) and
  bare ones (`--bypass`, `--persistent`, `--offline`) in any order. Anything else is **rejected naming the
  token** — which is exactly what made adding four flags a pure addition: it can only turn text that was
  already a hard error into a flag, never change the meaning of a command that parsed before. Cost,
  accepted: none of the literal flag tokens can appear in the text. `--chain` and `--limit` take the last
  occurrence when repeated, consistent with `--bypass`'s idempotence. The two `--offline` prerequisites
  are checked **after** the token loop, since flags may appear in any order relative to it.
- **`<content>` is full MiniMessage with no per-tag gate** — unlike `/mail send`, whose fifteen nodes exist
  because a mail is stored and rendered later for a *recipient* from a *sender* who may be gone. A parse
  failure is reported to the sender; there is no fallback to literal text, because the sender can fix it.
  The **stored** payload holds the raw MiniMessage, not the rendered component, so `BroadcastRenderer` is
  the only thing deciding how a stored broadcast reads.
- **Resolution moved off the command thread**, inverting the original rule, because the offline lookup
  blocks on a permission backend. `OnlineBroadcastAudience` still needs the main thread for
  `Player#hasPermission` and marshals back via `callSyncMethod` — with an `isPrimaryThread()`
  short-circuit that is **not tidiness**: without it a main-thread call deadlocks waiting on a task only
  the main thread can run. Each audience owning its own threading is what keeps the command branch-free.
- **The async order is fixed:** `resolve` → `--limit` → empty-audience → fan-out. A refused command is
  indistinguishable from one never run, apart from the queries it took to count.
- **The persistent push uses `NotificationDelivery.deliver`, not `Broadcaster`** — because it stamps
  `seenTime`, so a recipient who read it live is not pushed it again on their next join. It also honours
  the mute gate and per-`dataType` preferences. **Offline recipients are not pushed**, deliberately: a
  false `DELIVERED` from `ChatSink` would mark it seen unread. So an offline recipient with a linked
  Discord account waits until their next join; reaching them sooner needs per-medium delivery tracking,
  still deferred.
- **An enqueue failure propagates**; a single failed push is logged and the rest still run. Announcing a
  broadcast the inbox cannot show is worse than the command failing outright.
- **The `--bypass` fallback is `chat`, and only when nothing else remains.** A bypassed recipient with
  usable media is delivered to *those* — bypass overrides suppression, not choice of medium. Only an empty
  resolved set falls back to `Broadcaster.FALLBACK_MEDIUM`, chosen because `ChatSink` is registered
  unconditionally; `default-media` was rejected because a misconfigured server would make bypass silently
  deliver nothing, the exact failure the flag exists to rule out.
- **The failure replies are worded apart on purpose** — "No online player matched those permissions." vs.
  "No recipient had broadcasts enabled. Use --bypass to deliver regardless." Only the second is fixable
  with the flag. And a persistent send where every recipient was suppressed reports **stored, pushed 0**
  rather than "nothing enabled": it *was* stored and is readable.

### The offline permission lookup

**`PermissionLookup` is set-valued (`matching(nodes, chain) : Set<UUID>`), not per-player.** The obvious
shape — `Optional<Predicate<String>> forOfflinePlayer(UUID)`, feeding `BroadcastRecipients` like an
online candidate — was designed first and rejected: it forces one backend load per candidate, N storage
round trips for a set the backend can compute in a handful of queries. Cost, accepted: **the two paths
match by different code** and can disagree, most plausibly on context-conditional nodes. Sharing one
matcher is impossible while one side answers "who" and the other "whether".

**`searchAll(NodeMatcher.key(permission))` alone is wrong** — it matches nodes as *stored*, and an
inherited permission is not stored on the user. Only their group membership is. That is the way in;
`LuckPermsPermissionLookup` does three stages per node:

1. **Which groups grant it** — `getLoadedGroups()` filtered by each group's *resolved* `checkPermission`.
   No I/O (groups are in memory) and inheritance is resolved, so a group inheriting from a granting group
   is caught. `loadAllGroups()` is awaited once first so the set is complete.
2. **Who is in those groups** — one `searchAll(NodeMatcher.key(InheritanceNode.builder(group).build()))`
   each. Group membership *is* stored, which is why this works where stage 3 alone does not.
3. **Who holds it directly** — one `searchAll(NodeMatcher.key(node))`, adding holders and **subtracting
   explicit negations**, dropping `hasExpired()` nodes.

Two to four queries per node. `Chain.OR` unions the per-node sets, `Chain.AND` intersects (and
short-circuits once empty). **The `default` group is special-cased**: it grants implicitly and is stored
on nobody, so stage 2 would find no one — that case falls back to `getUniqueUsers()`.

- **`LuckPermsBinding` names no LuckPerms type in any signature *or bytecode*.** The factory lives on
  `LuckPermsPermissionLookup.create` precisely so nothing can be loaded before `isPluginEnabled` has
  passed — lazy constant-pool resolution would probably suffice, but HotSpot's verifier may load types
  named in a method body while verifying it, and the binding loads on every server. Verify with
  `javap -c -p …/LuckPermsBinding.class | grep -i luckperms`: string literals and the call to our own
  `create` only, never a `net/luckperms` type. Same shape as `EssentialsMailBinding`.
- **A `LinkageError` is caught**, as in the converter module: it is not a `RuntimeException`, so nothing
  above would handle it, and an optional capability failing to load must not disable the plugin.
- **LuckPerms is a third `join-classpath: true` server dependency** in `paper-plugin.yml`, alongside
  DiscordSRV and Essentials — so it is a **third source of the unloaded-dependency class-loading hazard**
  documented under "Discord adapter". Same fix: remove the jar rather than leave it unloaded.
- **A lookup failure fails the whole command** rather than proceeding with a partial audience — the
  recipients it would miss are not present to notice they were missed.
- **Approximations, both inherent:** context-conditional nodes are answered in
  `QueryOptions.defaultContextualOptions()` (there is no per-player context for an absent player), and a
  player with no stored LuckPerms data is never matched except via the default-group fallback.

**Tested:** `BroadcastArgumentsTest` (38), `BroadcastRecipientsTest` (9), `BroadcasterTest` (15),
`PersistentBroadcasterTest` (7), `BroadcastRendererTest` (4). **Not covered, needing a live server and a
real LuckPerms install:** `BroadcastCommand`, both audiences, `LuckPermsPermissionLookup` and
`LuckPermsBinding`. The three-stage lookup is the one piece with real logic that cannot be unit tested —
the price of it being a query against someone else's storage. **The 13-item manual checklist in
`…/plans/2026-08-29-persistent-offline-broadcast.md` has not been run.**

## EssentialsX mail converter

Design doc: `…/specs/2026-08-20-essentials-mail-converter-design.md`. Plan:
`…/plans/2026-08-20-essentials-mail-converter.md`.

A **one-shot migration tool**, not an integration: it reads EssentialsX mailboxes and writes them into
first-party mail, so retiring EssentialsX does not throw away everybody's correspondence. It registers
nothing anywhere and owns one command; once run, the module can be deleted.

- **`/essmailconvert`** — `preview` (counts, writes nothing) and `confirm` (imports). The bare command
  imports nothing and prints usage plus a warning, and `confirm` is a **literal rather than a flag**,
  because the conversion is deliberately **not idempotent**: the EssentialsX copy is left untouched (a bad
  import destroys nothing and can be re-run) and nothing de-duplicates, so running `confirm` twice gives
  every player two copies. De-duplication would cost a fingerprint table or a full scan, for a command run
  once. An `AtomicBoolean` refuses a second concurrent run. Console is a valid sender.
- **The host declares `Essentials` in `paper-plugin.yml`, `required: false`, `join-classpath: true` — and
  the module does not work without it.** `EssentialsMailBinding` names `com.earth2me` types and a module's
  loader is parent-first onto the host's, so with Paper's isolation those classes are unreachable:
  `register` dies on `NoClassDefFoundError: com/earth2me/essentials/IEssentials` at its `instanceof`, on a
  server where EssentialsX is installed **and enabled**. This hid because DiscordSRV's own `join-classpath`
  plus its Essentials hook drags Essentials' loader into our group — so the module worked by accident
  wherever DiscordSRV also ran. Read a `NoClassDefFoundError` here as a *missing declared route*, not the
  `isPluginEnabled` guard leaking: the guard sits in the entry class, and a failure inside the binding
  proves it already passed. The cost is the exposure under "Discord adapter". The entry can be deleted once
  every server has converted, along with the module.
- **A `LinkageError` from the binding is caught and the module skipped.** `ModuleLoader` catches only
  `ModuleLoadException`, so before this the error propagated out of `startModules()` → `onEnable` and
  **disabled the entire host plugin**. It now logs at `SEVERE` and returns.
- **The permission `essentialsmailconverter.command.convert` is declared nowhere.** `paper-plugin.yml`
  belongs to the host, and programmatic registration is the pattern the Discord adapter was cleaned of. An
  undeclared permission resolves op-only, which is the intended gate.
- **A module-owned command, reversing the Discord adapter's precedent, and safely.** That retreat was about
  *teardown*. This module is `reloadable: false` and never unregisters, so `shutdown` is empty.
  Registration goes through `LifecycleEvents.COMMANDS` from `initialize` — modules start inside `onEnable`
  after `registerCommands()`, and Paper fires COMMANDS after enable.
- **The entry class names no EssentialsX type, and that is load-bearing.**
  `EssentialsMailConverterModule` checks `isPluginEnabled("Essentials")` and calls
  `EssentialsMailBinding.register(plugin)` through an EssentialsX-free signature; the binding and
  `EssentialsMailReader` are the only classes mentioning `com.earth2me`/`net.essentialsx`. Verifying a
  method that names an EssentialsX type would force that type to load *before* the guard ran. Verify with
  `javap -c -p …/EssentialsMailConverterModule.class | grep -i earth2me` — only string literals should
  match. EssentialsX absent is logged and returns; it is **not** a `ModuleInitializationException`, because
  a converter with no source is a no-op.
- **The read is on the main thread in chunks; the write is not.** `EssentialsMailReader` sweeps
  `getAllUserUUIDs()` at `USERS_PER_TICK = 100` per tick via a `BukkitRunnable`, using `loadUncachedUser`
  (documented for whole-server sweeps, and it does not fill the user cache). EssentialsX user loading is
  not thread-safe, but loading thousands of userdata files in one tick would stall a live server. The
  finished list goes to the async executor, because enqueueing is blocking JDBC. An unreadable account is
  logged against its UUID and skipped.
- **`ImportedMail` is the seam** — every EssentialsX `MailMessage` flattened into a record containing no
  EssentialsX type, by a static factory parameterised by that message's *fields*. Everything downstream
  (`EssentialsMailConverter`, which holds every rule) is a plain unit test with no server.

The mapping rules, each a decision:

| EssentialsX | Result |
|---|---|
| `isExpired()` | skipped — Essentials would not have shown it either |
| unexpired with a future `timeExpire` | imported with `notifExpiryTime = null`; the Essentials expiry is **dropped** |
| `isLegacy()` | sender unknown by construction, `§` codes stripped |
| modern with a null sender UUID | nil-UUID sentinel `ImportedMail.UNKNOWN_SENDER`, real sender name kept |
| blank message | skipped — `MailPayload` rejects it anyway |
| over `MailPayload.MAX_MESSAGE_LENGTH` | **imported whole**; 256 is a `/mail send` input rule, not a storage constraint, and truncating an archive is silent data loss |
| `isRead()` | imported, then `markSeen` — otherwise an archive arrives as hundreds of unread mails |

**Imported text is escaped, so it can only ever render as the plain text it was.** Live mail reaches its
stored state through `MailFormatting.sanitize`; imported mail went through no such gate, so
`asStoredMail` runs it through `MiniMessage#escapeTags` before storing. Without that, a years-old mail
reading `<red>` or `<click:run_command:...>` would become live formatting the moment it was imported.
`escapeTags` rather than a sanitize-with-no-tags round trip: escaping cannot drop or rewrite content, and
there is no permission decision to model. Legacy `§` codes are stripped one step earlier for the same
reason — and note `MailRenderer` *throws* on a `§`, so leaving them in would force the fallback path.

**No arrival notice ever fires.** "You have new mail!" for a five-year-old message would be false, and once
per imported mail it would flood every Discord DM on the server.

**The one host change** is `MailSender.send(…, Instant sentAt)`, the four-argument form delegating with
`Instant.now()`. `sentAt` becomes `notifScheduledTime`, the inbox's primary sort key — without it a decade
of correspondence would land on top of genuinely new mail in sweep order.

A failed enqueue is counted in `ConversionReport.failed` and does not stop the run. A failed `markSeen` is
logged but **not** counted a failure: the mail is stored and readable, merely unread, and re-running is not
an available remedy. The report goes to the sender *and* the log, so a console run and an in-game run leave
the same record of a non-repeatable operation.

## Notification inbox

Design doc: `…/specs/2026-08-07-notification-inbox-design.md`. Plan: `…/plans/2026-08-07-notification-inbox.md`.

Every notification targeting a player stays readable until they dismiss it or it passes `notifExpiryTime`.
**Delivery marks seen; it does not consume.** Three states, one nullable column, `NotificationTarget.seenTime`:

| State | Storage | In the inbox |
|---|---|---|
| unread | row exists, `seenTime IS NULL` | listed, marked, counted on join, pushed |
| seen | row exists, `seenTime` set | listed, not counted, never pushed again |
| dismissed | target row deleted | absent |

`markUnread(key, playerId)` is the exact inverse of `markSeen`, keeping no record that it was read. That
returns the notification to **due**, so a trigger-driven push sends it again — which is what unread means,
but is why nothing calls it on a player's behalf. It is reached only from *Mark as unread* on a Discord
entry's detail; `InboxDetailDialog` does not carry it yet.

**Every player-facing surface calls this "delete", not "dismiss"** — both in-game dialogs, the Discord
button, and `/mail delete` / `/notifications delete`, and the button is red on both surfaces, since it is
the one control that destroys something. The word "dismiss" survives **below** the UI (`dismissSeen`,
`InboxView#dismissByKey`, `InboxRouter#dismissInChat`); renaming that too would touch the public API for a
wording change.

Dismissal **deletes the target row** rather than setting a third timestamp: an absent row makes "never
reappear" true without any query knowing the rule, and it reuses `trg_delete_targetless_notification`.

- **Read API** (on `NotificationService`): `inbox(playerId, page, pageSize)` → `InboxPage`,
  `unreadCount(playerId)`, `unreadCountsByDataType(playerId)`, `markSeen`, `markUnread`,
  `markAllSeen(playerId)`, `dismissSeen(playerId)`, `pruneOrphanedTargets()`. Dismissing **one** needs no new method — `deleteNotificationTarget` does it.
  `page` is 1-based and clamped into `1..totalPages`, `pageSize` into `1..20`, so a stale dialog button
  cannot produce an error screen. The page query's
  `ORDER BY n.notifScheduledTime DESC, n.notifPriority DESC, n.notifKey DESC` is a **total** order; without
  the key tiebreak two notifications sharing a timestamp could appear twice or not at all.
- **Data-type-filtered overloads**, added for `/mail` and widened to a set for the filtered inbox:
  `inbox(playerId, page, pageSize, dataTypes)`, `unreadCount`, `markAllSeen`, `dismissSeen`, each taking a
  `@Nullable Collection<String>`. **`null` means "no filter"; an *empty* collection means "match
  nothing"** — deliberately different, because a category may claim only unregistered types, and
  collapsing empty onto `null` would show a player everything under a heading claiming to contain none of
  it. The single-`String` and unfiltered forms are `default` delegates, so no caller changed **except one
  passing a bare `null`**, which is now ambiguous and needs a `(Collection<String>) null` cast. The four
  in-tree `NotificationService` test fakes (paper-plugin ×2, discord-adapter, essentials-mail-converter)
  implement these, so widening is not confined to `core`. On the mapper side the filter is a `<choose>`
  with three branches — nothing for `null`, **`AND 1 = 0` when empty**, and `AND n.notifPayloadType IN
  <foreach>` otherwise. The empty branch is load-bearing, not defensive: `<foreach>` over an empty
  collection emits `IN ()`, which MariaDB rejects. `NotificationTarget` has no `notifPayloadType` column,
  so `markAllSeen`'s filter is an `EXISTS` rather than a join. Filtered `dismissSeen` cannot be one
  `DELETE`: its subquery would read `Notification` while the trigger writes it, so it is
  `selectSeenKeys(playerId, dataTypes)` followed by one delete per key. Clamping applies against the
  **filtered** total.
- **`resolveNotifications` is deliberately unchanged** and still unfiltered: it means "every notification
  currently targeting the player", and the inbox has its own query rather than redefining it.
- **Rendering happens on read.** `paper.inbox.InboxEntryRenderer` resolves payload class → serializer →
  renderer, the same three lookups `NotificationDelivery.dispatch` does, extracted so the two cannot drift
  and so it is testable without a server. Its `decodePayload(entry)` is **public**, because a caller can
  need a field the rendered form does not carry: `MailChatRow` needs the sender name, which survives into
  the title only inside "Mail from X". Any missing lookup, or a throwing decode or render, yields a
  **placeholder** naming the data type — hiding the entry would leave it counted in `totalEntries`.
- **Paper UI:** `paper.inbox.InboxRouter` owns the screens, the per-player cursors
  (`paper.inbox.InboxCursors`, dropped by `InboxQuitListener`) and the async marshalling — the same shape
  as `PreferenceDialogRouter`. `InboxDialog` is the paged list (unread rows bold, *Mark all read*, *Delete
  all read*, Previous/Next, and on a filterable router a **Back** button returning to the filter picker,
  first in the grid so the way out sits in a fixed place); **no inbox screen carries a *Preferences*
  button** — the inbox is for reading, and jumping into preferences left the player with no way back to
  what they were reading. **A chat listing's row wording is `paper.inbox.InboxChatRow`**, a per-instance
  constructor argument like the title, command label and filter — so `/mail` gets its own format without
  the listing loop learning that `mail` is special. `InboxChatRow.titleOnly()` (`#<entry> <title>`, sharing its leading column with the mail row) is what
  `/notifications` uses; `paper.mail.MailChatRow` is the mail one: `#<entry> [Time] [Sender] <content>`,
  time as `HH:mm` today, `d MMM` this year and `d MMM yyyy` beyond (mail never expires and an import can be
  years old), content flattened to one line and cut at 40 characters — the row clicks through to `read`
  anyway. Its zone, clock and payload decode are injected; a non-`MailPayload` or undecodable payload falls
  back to the rendered title rather than showing empty columns. `InboxDetailDialog` shows one entry with
  *Delete* and *Back*, Back-doesn't-commit as in the preference editors. Opening a row marks it seen.
- **An empty inbox replies in chat and opens no dialog at all.** `InboxRouter.openInbox` returns early with
  `EMPTY_MESSAGE`. Not cosmetic: with no entries there are no row buttons, no Previous/Next, no *Mark all
  read* and no *Delete all read*, so `DialogType.multiAction` was handed an **empty** list — and vanilla's
  `MultiActionDialog` codec wraps `actions` in `ExtraCodecs.nonEmptyList`, so the dialog failed to encode
  and the player saw nothing. `exitAction` is a separate optional field and does not satisfy that
  constraint. **Any future `multiAction` screen that can reach zero buttons has the same bug**; today none
  of the others can.
- **`inbox-page-size` is clamped twice**, by `PluginSettings`/`paper.ui.PageBounds` and again by
  `DefaultNotificationService.inbox`. Deliberate: one is a UI helper, the other a public-API trust
  boundary, and neither should assume the other ran.
- **Marking seen is not transactional with delivery.** The processor runs outside the transaction, so a
  crash between a sink delivering and the `seenTime` write leaves it unread and it is pushed again. Chat is
  not idempotent, so that can duplicate a message. Accepted: the window is milliseconds and the alternative
  holds a transaction across a Discord round trip.

### Inbox filtering

Design doc: `…/specs/2026-08-23-filtered-inbox-design.md`. Plan: `…/plans/2026-08-23-filtered-inbox.md`.

The inbox **dialog** can be narrowed to one category; the **commands cannot**, deliberately.

- **`/notifications` lands on the picker, not the list** (`InboxRouter.openEntryScreen`, which falls back
  to the list on a pinned router, so bare `/mail` is unchanged). `InboxFilterDialog` carries one row per
  category plus an unconditional "All notifications" row, and **Close** — a grid button rather than the
  `exitAction`, which renders on its own row beneath the grid and would show the word twice. **Clicking a
  row is the search**: it applies that scope and opens the list in one gesture, so there is no confirm
  step, and "All notifications" is how the unfiltered list is reached. The list's **Back** button is the
  route back to the picker. The unconditional row is also what stops the picker ever handing `multiAction`
  an empty `actions` list. **A row whose scope holds unread notifications ends in a yellow `*`**, the "All
  notifications" row included — presence, not a count, since the count belongs to the list screen that
  states it for the scope you are in. The whole screen costs **one** read: `unreadCountsByDataType` groups
  the player's unread total by data type once and each row folds that map through its own category, so the
  picker opens off the main thread like the list does. Every category is listed regardless of contents, so
  rows do not move between opens — the asterisk is what tells full from empty without moving anything. A filtered list titles itself with the category label, which is where its
  scope is stated.
- **`paper.inbox.InboxFilters`** resolves a category key to its data-type set via
  `NotificationCategories#dataTypesForCategory`, reading the categories **and** the registry live on every
  call rather than snapshotting — so a late module registration and `/notifications reload` are both picked
  up with no change listener. It holds no Bukkit type and is unit tested. `resolve(null)` is `null`
  (unfiltered); every other key yields a possibly-empty set. `hasUnread(categoryKey, unreadByDataType)`
  folds a grouped unread count through that same resolution, so the filter picker's asterisk inherits the
  `null`-vs-empty rule: an unfiltered scope asks whether anything at all is unread, while a category
  claiming nothing registered is never marked however much sits elsewhere.
- **The filter lives in `InboxRouter.dialogCategory`, which no command reads.**
  `/notifications list|read|delete|clear` act on the whole inbox — filtering is a browsing affordance, and
  a filter set ten minutes ago is invisible in a command surface. `/mail` is pinned (`pinnedFilter`, and no
  `InboxFilters` at all), so it shows no Back button.
- **The page cursor is split per surface** (`InboxCursors`: `dialogCursor`/`dialogListed` vs `chatListed`).
  Previously both wrote one shared pair of maps, so paging a dialog changed what `/notifications read 1`
  resolved against. **Behaviour change:** a player who has only used the dialog and then types `read 1` is
  now told to list first.
- **An empty inbox replies in chat and closes**, opening no screen in its place — filtered or not. An
  earlier version reopened the picker on a filtered miss, because the list was then the only route to the
  filter; once `/notifications` began landing on the picker, that route existed anyway and the bounce only
  dropped the player into a screen they had not asked for.
- **Unverified on a live server** — the 14-item checklist in the plan's Task 6 **has not been run**.
  Tested: `InboxFiltersTest` (15), `InboxCursorsTest` (9), `core`'s `FilteredInboxSetTest` (5) and
  `FilteredInboxTest`'s five `unreadCountsByDataType` cases.

## Global mute

Design doc: `…/specs/2026-08-20-global-mute-design.md`. Plan: `…/plans/2026-08-20-global-mute.md`.

A player-level do-not-disturb switch, **orthogonal to the per-`dataType` media matrix**. It suppresses
every unsolicited push; it does not stop anything reaching the inbox. A muted player still accrues
notifications, stored unread, readable through `/notifications` and `/mail`.

**A mute is not a silence.** A mute is temporary and player-level and touches no preference row; turning
one `dataType` off for good is a **silence** (`SILENCED_MEDIUM`, the `medium = 'none'` row). The wording is
kept apart everywhere: in game, `/notifications mute|unmute` and the "Mute everything" dialog are the
switch while the editors silence a type by emptying its selection; in Discord, `/notifications mute|unmute`
are the switch and the screen's buttons read "Silence this type" / "Silence everything".

- **API:** `NotificationPreferences#isMuted(UUID)`, a `default` returning `false` — feature modules are
  compiled separately, so a new abstract method would break them. Mutation stays off the read interface:
  `DatabaseNotificationPreferences#mute`/`unmute`, the placement `resetAll` already had.
- **Storage:** `PlayerNotificationMute(playerUuid BINARY(16) PK, mutedTime DATETIME NOT NULL)`, migration
  `V3__player_mute.sql`. Presence of the row is the mute; `mutedTime` is read by nothing and exists so an
  operator can see when it was set and a future *timed* mute has somewhere to land. `mute` is idempotent;
  `unmute` on an unmuted player is a no-op. A separate table rather than a `dataType = '*'`, `medium =
  'none'` row: that row already means "blanket fallback", so overloading it would make unmuting
  indistinguishable from clearing one — and would put the mute back inside the set this design pulled it
  out of.
- **Enforcement — one gate, three push paths.** The gate sits at the **top of
  `NotificationDelivery.deliver(UUID, Instant)`**, before the due query: a muted target's notifications are
  not decoded, dispatched or marked seen. That placement is the point — `deliver` is the single funnel, so
  the mute applies to the renderer path **and** to bespoke processors, which the `SILENCED_MEDIUM` filter
  inside `RenderingProcessor` never could. The other two paths are `MailNotifier#notifyArrival` and
  `JoinDeliveryListener`'s announcements. `RenderingProcessor` was not changed.
- **Unmuting restores exactly what the player had**, because the flag never touches a preference row. This
  replaced `muteAll`, which rewrote every type to `{none}` and so had no meaningful inverse.
- **Commands and UI:** `/notifications mute` / `unmute`, both mirrored under `preferences`;
  `PreferenceEditSession` stages the flag (`muted()`, `setMuted`, `stagedMuteChange()`, counted by
  `dirtyCount()`), and `applyChanges` has a four-argument overload taking that `@Nullable Boolean` so a
  staged mute and staged matrix edits commit in one transaction. The three-argument form passes `null`.
- **`/notifications test`** enqueues as usual but `deliver` no-ops, so `TestNotificationSender.report`
  checks `isMuted` first and says so, naming `/notifications unmute`. The notification still lands in the
  inbox — the correct outcome to observe.
- **Deliberate consequences:** a muted player is told *nothing at all*, so the inbox is their only
  discovery path; there is no timer; unmuting does **not** back-deliver what accumulated, since a burst on
  unmute is precisely the interruption being avoided; and there is no admin surface, so a stuck mute needs
  a manual `DELETE` against `PlayerNotificationMute`.

## Type names

Design doc: `…/specs/2026-08-23-configurable-type-names-design.md`. Plan:
`…/plans/2026-08-23-configurable-type-names.md`.

A `dataType` is a registry key chosen by a module author. `paper.localisation.TypeNames` resolves it to the
name a player sees, through three layers, **live on every call** — no snapshot, so unlike the category
registry this needs no change listener:

| Source | Format |
|---|---|
| the operator's `type-names.yml` entry | MiniMessage — always wins |
| `NotificationDataTypeRegistry#displayName`, registered by a module | MiniMessage |
| `TypeNames.titleCase` over the key | plain |

- **The module default lives on `NotificationDataTypeRegistry`, not a new registry** — that class already
  keys on `dataType` and owns the payload class, processor, serializer and renderer for it. It is a
  concrete class with no implementors, so the new methods break nothing. A display name is **independent of
  the payload mapping**: naming a type does not make `dataTypes()` report it, so a name for an unregistered
  type is inert rather than conjuring a row into the preference screens.
- **An entry renames the type only, not the whole row.** The in-game medium editor row is
  `primaryCategory.label + ": " + TypeNames#name` (`PreferenceDialogs.dataTypeLabel`) — the category prefix
  stays, because it is what makes a flat checkbox list read as grouped. Discord's select has no prefix, so
  there the name is the whole label (colour dropped: an option carries a string, not a component).
- **The two malformed cases warn differently, on purpose.** An operator value is parse-checked in `load`,
  logged once at `WARNING` naming the key, and **dropped** — warning in `name` would reprint on every
  screen open, whereas deciding it at load puts the line in the console when the edit is read and makes
  `/notifications reload` the thing that reprints it. A module default cannot be validated at load (a
  module may register later), so it is caught at render and warned **once per `dataType`**, guarded by a
  set `load` clears. A **blank** value at either layer falls through silently: emptying an entry is how an
  override is deleted.
- **`load` collects then `putAll`+`retainAll`**, never `clear()`-then-fill, so the map is never observably
  empty to a screen opening mid-reload. `MessageContainer.load` takes the same care.
- **`defaults/type-names.yml` lists every registered type, not only those a module named** — a deliberate
  divergence from `defaults/categories.yml`. The file answers "what can I rename and what does it say now",
  and a module-only dump would omit exactly the types most worth renaming. It reports the **default**,
  never `TypeNames#name`, which would echo the operator's own override back as a module's. Provenance is in
  the header (three sorted lists) rather than per-entry comments, because Configurate's YAML comment
  emission is version-dependent and the body must stay a flat map so a line copies straight across.
- **Deliberately excluded from `warnAboutMissingConfigKeys`** — a partial override map has no missing keys.
- `core.config.GeneratedYaml` carries the shared write contract for both generated files (never throws,
  plain-text header, deliberately non-atomic); `CategoryDefaultsWriter` delegates to it.

## Notification categories

Design doc: `…/specs/2026-07-28-categorised-notification-preferences-design.md`.

**Categories are a display/grouping concept only — preferences are stored and resolved per `dataType`.** A
category is a coarser player-facing grouping over one or more `dataType`s, used purely by the dialogs' "by
notification type" pivot and its bulk fan-out; nothing in the dispatch path touches categories at all.

Categories are **many-to-many** and come from two sources merged at read time: `categories.yml`
(`core.category.NotificationCategoriesConfig` / `NotificationCategoryDefinition`) and the code-driven
`api.category.NotificationCategoryRegistry`. `core.category.NotificationCategories` builds the merge at
construction and exposes `resolve(dataType): Set<String>` (a type claimed by two categories resolves to
both, no collision to resolve), `categoryKeys()`, `label(key)`, `description(key)`,
`dataTypesForCategory(categoryKey, allKnownDataTypes)` (the complement for `UNCATEGORIZED`), and
`typesWithNoPayloadMapping(registry)` (checked once at startup, after modules load). A category-key
collision logs at `fine`; config's label/description wins.

**The code registry is also dumped to `<dataFolder>/defaults/categories.yml`, written and never read.**
`core.category.CategoryDefaultsWriter` renders only the `categories:` subtree, reusing
`NotificationCategoryDefinition` so the shape cannot drift from the live file's, so an operator can see
what a module registered and copy the block by hand.

- **It changes no resolution behaviour.** Reading it back as a middle layer was rejected — it is
  regenerated from the registry every reload, so it would be a loaded copy of in-memory state, and anything
  deleted from it would reappear.
- **Keys and each category's `types` are sorted**, so two writes of the same registry are byte-identical;
  the operator's workflow is diffing this against `categories.yml`.
- **Written from three places**: the coalesced `rebuildCategories`, `/notifications reload`, and a one-tick
  task at the end of `onEnable`. That last is not redundant with the change listener — `addChangeListener`
  has a `default {}` body for binary compatibility, so a third-party registry never notifies the host.
- **It rots once copied.** A block copied into `categories.yml` does not track later rewording; that is the
  price of the operator holding sole authority. The fix, if operators report stale labels, is making
  `categories.yml` entries field-wise patches. Design doc:
  `…/specs/2026-08-23-module-category-defaults-design.md`.

A `dataType` no category claims resolves to `Set.of(NotificationCategories.UNCATEGORIZED)`, which is always
a real, selectable category — a new module's notifications are configurable immediately.

`DatabaseNotificationPreferences` stores `(playerUuid, dataType, medium)` and resolves
`preferredMedia(player, dataType)` in this precedence: exact rows, else rows for the reserved
`ALL_DATA_TYPES_KEY` (`"*"`, a blanket fallback nothing in the dialogs writes), else `default-media`. The
single-argument form is the same lookup against `"*"`.

## Player commands

Registered in `PlayerNotificationsPlugin.registerCommands()` through Paper's Brigadier API
(`LifecycleEvents.COMMANDS`) — **not** a `commands:` block, which `paper-plugin.yml` does not support.

- `/notifications` (alias `/notifs`) — **opens the inbox's filter picker** (`InboxFilterDialog`), where
  clicking a category row opens the list in that scope. The name was reserved for the inbox; the preference
  subcommands sit under `preferences` so the top level stays clear for the inbox's own verbs.
- `/notifications list [page]` / `read <entry>` / `delete <entry>` — the **chat fallback** for clients where
  the dialog does not render. `<entry>` indexes the page most recently listed for that player. Each row
  carries a hover and a `runCommand` click for its own `read`, worded from the router's `commandLabel` so
  the same rows work under `/mail list`, and a listing running to more than one page ends with
  `InboxChatFooter`'s clickable pager. `read` frames the entry with `inbox.read-title` and
  `inbox.read-body` rather than printing the raw title and body. Player-only, under `playernotifications.command.preferences`, off
  the main thread.
- `/notifications clear` — empties the inbox outright, **unread included**, as a shorthand for "Mark all
  read" then "Delete all read". Composed from `markAllSeen` + `dismissSeen` rather than a new service
  method, and drops the page cursor so a stale `read`/`delete` cannot resolve.
- `/notifications preferences` — the root preferences dialog; `… media` and `… types` jump to the two
  pickers; `… mute` / `… unmute` set or clear the player-level mute **immediately** (no staging).
- `/notifications mute` / `unmute` — the same two kept **also** at the top level, as proxies onto the same
  `PreferenceDialogRouter.muteImmediately`/`unmuteImmediately` (not second implementations), since they are
  the operations most often wanted in a hurry.
- `/notifications link [provider] [status]` / `unlink [provider]` — account linking, gated by its own
  `playernotifications.command.link` (`default: true`).
- `/notifications test [message]` — enqueues a `test` notification targeting the sender and delivers it
  immediately, off the main thread. Admin-only (`playernotifications.command.test`) and player-only. The
  **only** caller of `NotificationDelivery.deliver(UUID)` in the tree. Its reply names the media
  *attempted*, not delivered (`RenderingProcessor` reports no per-sink outcome), labelled via
  `NotificationSinkRegistry#displayName(String)` so they match the dialogs, and it separates the three
  preference states: a `{none}` silence and an empty selection get their own "nothing was sent" replies,
  and a preferred medium with **no registered sink** is called out, since `RenderingProcessor` skips it
  silently. Backed by `paper.diagnostic.TestNotificationSender`, which takes a
  `Supplier<NotificationDelivery>` because `reload()` replaces that object. `TestNotificationRenderer`
  titles it `[Test] Test Notification` and the body says what a test notification is and that
  `/notifications test` sent it — it lands in the same inbox as real ones with no other context. It names
  the target rather than printing a raw UUID, taking a `Function<UUID, String>` seam;
  `usingServerNames()` is the production wiring.
- `/notifications reload` — re-reads `categories.yml`, `settings.yml` and `messages.yml` (re-reads only;
  it writes to none of them, and regenerates the `defaults/` files). Admin-only
  (`playernotifications.command.reload`) and usable from console, unlike every other subcommand, so
  `NotificationsCommand` dispatches it outside the player-only `run()` helper. Deliberately does **not**
  reload `database.yml`, which would mean rebuilding the pool mid-request. Also refreshes
  `JoinDeliveryListener`'s toggle and delay.

**There is no player-facing way back to the server default.** `/notifications preferences reset`, the root
screen's "Reset all to server default", the category editor's "Use server default" and the picker's
"(server default)" suffix were removed together, along with `PreferenceEditSession.resetDataType`/
`dataTypesToReset`/`isUsingServerDefault` and `PreferenceDialogRouter.resetImmediately`: players read
"defaults" as a fourth preference state they had to reason about. Every edit is now an explicit choice, and
the only way out is to pick different media or silence the type. `default-media` still applies to a
`dataType` the player has never configured. `core` kept `resetAll`/`explicitlyConfiguredDataTypes` and
`applyChanges`'s `dataTypesToReset`, now **uncalled** — persistence-level operations left in place (and
still tested) for a future admin command.

**`/mail` and `/broadcast` are sibling command trees**, not subcommands — see their sections. `/broadcast`
is gated by `playernotifications.command.broadcast` (`default: op`, the only op-only root here) and is
open to the console. It no longer *never* stores anything: `--persistent` writes a real notification, and
`--offline` (which requires it) reaches players who are not connected.

The player-facing subcommands are player-only under `playernotifications.command.preferences`
(`default: true`). `link`/`unlink` carry an **additional** `playernotifications.command.link` (also
`default: true`); because Brigadier `requires` nest, revoking the root still hides linking.

**Account linking lives under this tree**, via `api.link.AccountLinkRegistry` — a host-owned registry a
module registers an `AccountLinkProvider` against. This **reverses** an earlier decision (linking used to
be the Discord module's own `/discordlink`, kept out of the host "to avoid a registry it has no other use
for"); it still has one consumer, but it deleted more host-adjacent machinery than it added, and linking is
now discoverable from the command a player already knows.

- `/notifications link` lists every registered provider; `… <provider>` starts a link; `… <provider> status`
  describes the current one; `/notifications unlink <provider>` is a **sibling** literal of `link`.

`<provider>` is a Brigadier **argument**, not one literal per provider: the tree is built when Paper fires
`LifecycleEvents.COMMANDS` and modules register during `startModules()`, so static literals would freeze
the provider set. Suggestions and resolution read the registry live. The node exists even with nothing
registered; an absent provider gets an explanatory reply — removing the node would need mutation of an
already-registered Brigadier node, which Paper does not expose. `paper.command.AccountLinkDispatcher` holds
all of this precisely so it is unit-testable; the `link` subtree is wiring only. Both branches are
player-only and off the main thread, since providers block on JDBC.

`paper.command.NotificationsCommand` builds the node and delegates every subcommand to a
`paper.preferences.PreferenceDialogRouter`, the single object owning the six dialog screens and the shared
`PreferenceSessionManager`:

- `PreferenceRootDialog` — pick a pivot, or go to "Mute everything". **Purely navigational**: no Apply and
  no Discard at all, since every edit is made and committed on a screen of its own and a commit button here
  would belong to no particular edit. It still shows the `stagedSummary` line.
- `MuteConfirmDialog` — the mute/unmute confirmation. One screen either way: title, intro and direction read
  `PreferenceEditSession#muted()`, and the root button's label flips with it. Structurally an editor with
  no inputs: Apply/Discard via `addEditorCommitButtons`, plus Back to the root. The flip is staged by
  **Apply's commit callback**, not on the way in, so Back genuinely changes nothing.
- `MediumPickerDialog` → `MediumEditorDialog` — pick a medium, then one checkbox per **`dataType`** (grouped
  and labelled by its primary category), e.g. "which notifications reach me on Discord".
- `CategoryPickerDialog` → `CategoryEditorDialog` — pick a category, then one checkbox per **medium**; each
  fans out to every `dataType` the category claims, showing "(partly on)" when members disagree.
  **Structurally the mirror of the medium pair**, differing only in which axis is the row.

**Apply and Discard are on every screen that can edit something** — the two editors, the mute confirmation,
and the two pickers once the session is dirty — along with a `PreferenceDialogs.stagedSummary` line naming
the pending count, which the root shows too. This replaced an Apply/Discard pair on the root screen only,
which players reported as the main confusion.

- **The editors have no Save.** `addEditorCommitButtons` shows Apply and Discard *unconditionally*;
  `addStagedButtons` (the two pickers, which have no inputs) shows them only while dirty. The distinction
  matters: an editor's checkbox state lives in the dialog response until a button is pressed, so a first
  edit on a clean session has nothing staged yet and a dirty-gated Apply would be missing exactly when
  needed. A Save that staged without persisting, next to an Apply that did both, was a third option whose
  difference from Apply nobody could state.
- **Apply folds in the on-screen response first**, via the `Consumer<DialogResponseView>` the editors pass —
  without it Apply would write only what was staged and silently drop the ticks in front of the player.
- **`Back` on an editor abandons that screen's checkboxes** — deliberately not a commit. It is not a
  session-wide undo: edits applied or staged elsewhere are untouched, and Discard remains the button that
  throws the whole session away.
- **Both actions take *reloading* router entry points** (`openRoot`/`openMediaPicker`/`openCategoryPicker`,
  plus `openMediaEditor`/`openCategoryEditor`) because both drop the session the caller holds. Apply returns
  to the picker; an editor's Discard reopens *that editor*, so the reverted checkboxes are visible.

Both editors mutate the same `paper.preferences.session.PreferenceEditSession`, keyed by `dataType` (not
category), so the two pivots can never disagree. Checkboxes reach the session only when a button commits
them, which only Apply does; nothing persists until **Apply**, which writes every dirty `dataType` in one
transaction (`applyChanges` with an empty `dataTypesToReset`). A `dataType` emptied to nothing stages a
silence (`{"none"}`); there is no staged form of "fall back to the server default". In
`CategoryEditorDialog`, committing always writes every member `dataType`'s state for every medium shown,
even untouched ones — opening a category editor and pressing Apply with no changes converts every member
from unconfigured to an explicit row matching what was displayed.

**Three preference states per `dataType`:**

| State | Storage | `preferredMedia(player, dataType)` |
|---|---|---|
| Unconfigured | no exact rows | `*` rows, else `default-media` |
| Explicit selection | one row per medium | that set |
| Explicit silence | a single `medium = 'none'` row | `{none}` |

A silenced type's notification is **retained unread**. **This table is the per-`dataType` axis only** — the
player-level mute is a separate switch on top of it.

**Unconfigured is now a one-way state**: nothing in the UI returns a `dataType` to it. The row remains
because `preferredMedia` still resolves it — for a type the player has never touched, and for one
registered by a module installed after they last edited.

`"none"` is **not a registered sink** — `NullSink` was deleted with the inbox work, because reporting
`DELIVERED` would mark a silenced notification seen and hide it from the unread list, precisely backwards.
It survives as `SILENCED_MEDIUM`, stored as a row rather than zero rows because zero rows already means
"has expressed no preference", and excluded from every checkbox list. It is reached only by emptying a
type's selection: `/notifications mute` and `MuteConfirmDialog` no longer write `{none}` rows at all, and
`muteAll` was deleted with the global mute — it destroyed the player's real choices, which is why there was
no unmute to pair with it.

Implementation notes:
- `PreferenceSessionManager` expires a session after 15 minutes idle; `PreferenceQuitListener` drops it on
  quit; reopening starts fresh from the database.
- `/notifications preferences mute`/`unmute` write **immediately** and discard any open staged session with
  a chat notice — the one deliberate asymmetry with `MuteConfirmDialog`'s staged flip.
- `DatabaseNotificationPreferences` does blocking JDBC while `Player#showDialog` must run on the main
  thread, so dialog loads/writes marshal onto the async scheduler and back (`PreferenceDialogs.withSession`).
- **`paper.ui`** (`PageBounds`, `PagedDialogs`, `DialogSupport`) holds the paging arithmetic, the
  Previous/Next buttons and page indicator, and the callback options / main-thread marshalling / `message`
  helpers. **The package imports nothing from `io.github.md5sha256.playernotifications`** — only Paper,
  Bukkit and Adventure — so lifting it into `plugin-infrastructure` would be a package rename. It stays
  in-tree until a second consumer exists; a change that would only ever make sense for the inbox belongs in
  `paper.inbox`. Verify with `grep -rn "playernotifications" …/paper/ui/` — only `package` lines should match.
- Dialog input keys are **positional** (`medium_0`, `category_0`, …) with a key→value map, because
  medium/category keys are arbitrary strings and any sanitizing transform risks two colliding onto one input.
- Button callbacks use `ClickCallback.Options` with `uses(1)` and a one-hour lifetime; a dialog left open
  past that has inert buttons and must be reopened.
- **Known quirk, now also a deliberate feature:** an explicitly registered `NotificationProcessor` wins
  dispatch and bypasses **per-`dataType`** preferences, so a player who silenced one type would still
  receive its notifications. It does **not** escape the player-level mute. The one in-tree instance is
  `mail`'s RETAIN processor, where it is intentional: mail is never sent through any medium, so there is
  nothing for a silence to bypass, and the skipped `mail` rows are reused to route the arrival notice. The
  quirk remains a footgun for any third-party processor not making that trade deliberately.
- The six dialog classes and the router are **unverified by automated tests** — check by hand with
  `:platform:paper-plugin:runServer`.
- `onEnable()` builds `NotificationCategories` twice: once from config only (to unblock
  `registerCommands()`, which constructs the router before modules have registered code-side claims), then
  again after `startModules()`, swapped in via `reloadCategories` — the mechanism `/notifications reload`
  uses. `reload()` swaps the reloaded categories into a freshly constructed `NotificationDelivery` (which
  no longer takes a `NotificationCategories` at all) and into `PreferenceDialogRouter`, and the reloaded
  `default-media` into `DatabaseNotificationPreferences` (`reloadDefaultMedia`, a `volatile` field) — both
  without reconstructing objects other code holds references to. A player with an open staged session keeps
  editing against the `dataType` set known when it loaded; its keys are still valid strings to write on
  Apply. The prune task is cancelled and rescheduled if `prune-interval-seconds` changed.

## Configuration

All config uses **Configurate** (`YamlConfigurationLoader`), not Bukkit's `getConfig()`. On enable the
plugin copies a bundled default into the data folder **only when the file is absent**, then loads it into
`@ConfigSerializable` records.

**No config file is ever rewritten after that first copy.** `copyDefaultsYaml` used to load, `mergeFrom`
the bundled resource and `save` back on every call — so `categories.yml` was re-emitted at startup, on
every reload *and* on every late-registration rebuild, stripping the operator's comments and reordering
their keys each time. Two things replace what that delivered:

- **`messages.yml` gets a defaults-underlay, in memory only.** `reloadMessages` loads the bundled resource
  and calls `mergeFrom(operatorNode)` on it, so the operator's values win and an absent key resolves to the
  shipped wording. It is the one file with this treatment, because a missing key there is not a neutral
  absence — `MessageContainer` renders an unknown key as its own name, so a message added in a later
  release would print `mail.sent` to players, and `MessageKeysTest` checks the shipped resource, not the
  operator's file. Note the direction: `a.mergeFrom(b)` fills keys absent from `a`, so the **bundled** node
  must be the receiver.
- **The other three get `warnAboutMissingConfigKeys`**, logging one warning per file naming any key the
  bundle has and theirs lacks, without touching the file. `paper.config.ConfigKeyGaps` is the diff (dotted
  leaf paths, sorted, one-directional, a list node treated as a single leaf so trimming `default-media` is
  not reported). Load-bearing, not belt-and-braces: without it an added primitive key arrives as
  `0`/`false` and an added `@Required` reference key throws and disables the plugin, with nothing naming
  the cause. It cannot distinguish a deliberate deletion from an upgrade's new key, so a removed category
  warns on every startup — accepted, since suppressing it needs a state file, the thing this change exists
  to stop writing. Design doc: `…/specs/2026-08-23-config-files-are-never-rewritten-design.md`.

The files:
- `database.yml` → `DatabaseSettings` (in `core`): `url` (JDBC url **without** the `jdbc:` prefix),
  `username`, `password`.
- `settings.yml` → `PluginSettings`: `prune-interval-seconds` (3600); `default-media` (`List<String>`,
  `[chat]`) — assumed when a player has no stored rows; `deliver-on-join` (`true`);
  `join-delivery-delay-seconds` (`long`, 3, `0` meaning immediately and a negative value clamped to `0`,
  not defaulted, unlike `prune-interval-seconds`); `inbox-page-size` (`int`, 7, clamped to `1..20` in the
  compact constructor, with `0` — the value an absent key deserializes to — falling back to the default).
  The primitive keys are deliberately **not** `@Required`: that rule guards against a missing key becoming
  `null`, which a primitive cannot do.
- `defaults/` — **everything the plugin generates and never reads** (`GeneratedYaml.DIRECTORY_NAME`; the
  first write creates it). Each file shares the **basename of the live file it mirrors** rather than a
  `-defaults` suffix, so comparison is a plain `diff defaults/categories.yml categories.yml` and the data
  folder's top level stays files the operator owns.
- `type-names.yml` → `paper.localisation.TypeNames` — a flat `dataType` → MiniMessage name map. Not a
  `@ConfigSerializable` record: the keys are unknown at compile time, so it is read with `childrenMap()`
  like `MessageContainer`. Ships with every example commented out.
- `categories.yml` → `NotificationCategoriesConfig`: `uncategorized-label` and `categories` (key →
  `{label, description, types}`).
- `messages.yml` → a `MessageContainer` rather than a record, since its shape is a flat key/value map.

Conventions when editing config:
- Every non-null (`@NotNull`, reference-typed) `@Setting` field must also be `@Required`, so a missing key
  fails loudly instead of deserializing to null.
- Configurate 4.2.0 has **no built-in `java.time.Duration` serializer** (`node.get(Duration.class)` returns
  null). Use a `long`-seconds field, or register a custom serializer.

## Messages

Design doc: `…/specs/2026-08-21-configurable-messages-design.md`. Plan:
`…/plans/2026-08-21-configurable-messages.md`.

Player-facing chat text lives in `messages.yml` as MiniMessage, loaded into `plugin-infrastructure`'s
`MessageContainer` — the pattern `realty` uses. `PlayerNotificationsPlugin.messages()` exposes it;
`paper.localisation.MessageKeys` declares every key.

- **The container is used unsubclassed**, diverging from realty, which adds `deserializeRaw` for a command
  substituted into a `<click>` tag argument — a position no `TagResolver` can fill. The listing rows here
  have the same shape of need but attach their click in Java, on the rendered `Component`, so the key holds
  only text. **Do not move a click target into `messages.yml` without adding the subclass.**
- **A missing key renders as the key's own name**, so a typo prints `mail.sent` rather than throwing.
  `MessageKeysTest` is the whole defence: it walks `MessageKeys` by reflection against the shipped file
  **in both directions**, so a constant pointing at nothing and a key nothing reads each fail. **Adding a
  message means adding both** a constant and a YAML line.
- **`value()` for anything a player typed, `markup()` only for a `Component` the plugin built.** Player
  names, mail bodies, broadcast tokens and exception messages all go through `value()`; a stray `<` would
  otherwise open a tag in a message its recipient never consented to.
- **The container is final and reloaded in place**, never replaced, so every command and listener takes it
  once at construction and reload reaches all of them with no re-registration. `rawMessages` is a
  `ConcurrentHashMap` (verified with `javap`), so a read racing a reload is stale at worst, never corrupt.
- **Text whose value varies per call gets a key per case, not a ternary.** Singular and plural are separate
  keys (`join.unread-one`/`-many`, `inbox.cleared-one`/`-many`), and a listing row's unread and read states
  are separate keys too — the original applied `colorIfAbsent`, a no-op once text carries a colour, so one
  key an operator had coloured would have silently erased the unread distinction.
- **Two rule classes carry values, not sentences**, so their wording can live in the file:
  `MailRecipients.Result` is `UnknownPlayer`/`BlankMessage`/`MessageTooLong(maxLength)`, and
  `BroadcastArguments.Result` is `Parsed`/`BlankContent`/`FlagMissingValue(flag)`/`UnrecognisedToken(token)`.
  Their tests assert on a **case**, so rewording no longer fails a rule test. Note `discord-adapter`'s
  `DiscordMailService` consumes `MailRecipients.Result` too — every module compiles against `paper-plugin`,
  so "internal to the host" does not mean "unreferenced".

**Notification type names are a separate file, not keys here** — `MessageKeysTest` walks both directions,
and type-name keys are per-`dataType` and unknown at compile time. `type-names.yml` has no such contract.

### Message styling

Design doc: `…/specs/2026-08-23-message-styling-design.md`. Plan:
`…/plans/2026-08-23-message-styling.md`.

The file is written to one vocabulary borrowed from `realty`, so replies from different commands read
as the same plugin talking. `<green>` a thing that happened, `<red>` a thing that did not, `<yellow>`
a state the player is in, `<gold>` the thing being acted on, `<white>` a live value or a command they
can type, `<gray>` the hint under a reply and anything already read, `<dark_gray>` structure only. The
vocabulary and the prefix rule are restated in the file's own comment header, which is where an
operator will look.

- **`<prefix>` is on some keys and deliberately not others.** A message carries it when it is the
  plugin speaking unprompted or answering a command in one line — when it could be the first thing a
  player sees. A line sitting *inside* something already introduced does not: listing rows, the usage
  hint and pager under a listing, `link.entry` under `link.header`, `test.no-sink` (appended to
  `test.sent`), `preferences.session-discarded` (appended to `muted`/`unmuted`), `inbox.read-body`
  (under `inbox.read-title`), and the three `*.title` keys, which are screen **names** rather than
  messages. Before this the `prefix` key existed and **no message referenced it**, so the brand mark
  had never appeared in game at all.
- **The prefix opens with `<newline>`**, realty's rhythm: every reply gets a gap above it so a block
  is not swallowed by surrounding chat. It applies to one-line replies too. Deleting that one tag is
  the operator's escape hatch, and it is the single edit that changes everything at once.
- **`inbox.title` is `Inbox`, not `Notifications`.** It is both the dialog's title and the `<title>`
  of `inbox.header`, so with the prefix applied the header read "Notifications » Notifications —
  page 1 of 3". `/mail` titles the same screen `Mail`; the two now read as siblings.
- **`inbox.row.title-only-*` leads with `#<entry>`**, sharing its column with `mail.row.prefix` so a
  notification listing and a mail listing read as one screen.
- **A backslash escapes only the *opening* bracket.** `inbox.usage` shipped `\<entry\>` for years;
  `\>` is not a MiniMessage escape, so the backslash reached players as text.
- **`paper.inbox.InboxChatFooter` is the chat listing's pager** (`« Page 2 of 3 »`), returning `null`
  below two pages so a single-page listing ends at its usage hint. Its own class, not a private
  method, for the reason `InboxFilters` is: it holds a decision and names no Bukkit type, so
  `InboxChatFooterTest` covers it with no server. **The click is attached in Java** — a `<click>` tag
  *argument* is the position no `TagResolver` can fill, and the arrow keys hold arrow text only. An
  arrow at the end of its range is dimmed and inert rather than omitted, so the footer keeps its width
  from page to page — and its **live and inert forms are two keys**, because MiniMessage renders
  `<yellow>«</yellow>` as a parent carrying a coloured child, so `Component#color` on what `messageFor`
  returns is a no-op and the first version's inert arrow still rendered yellow. The command label is a constructor argument because both routers share the class;
  hardcoding it would let a mail listing page the other inbox.
- **`PreferenceDialogRouter` takes a `MessageContainer`.** Its seven replies — save succeeded/failed,
  discarded, muted, unmuted, mute failed, unmute failed — were hardcoded `Component.text(...)`, and the
  two failures were built as `"Could not " + (muted ? "mute" : "unmute")`, the exact construction the
  key-per-case rule exists to prevent. `setMutedImmediately` now takes a success key and a failure key.

**Deliberately still hardcoded:**

- **`MailNotifier.ARRIVAL_NOTICE`** — the Discord adapter recognises it by **reference identity**
  (`MailNoticeButton.java:39`). A configurable notice is rebuilt on reload, changing its identity and
  dropping the button *silently*. Configuring it needs a marker a reload cannot invalidate.
- **The dialogs** — the six preference screens, the three inbox screens, and `ui/PagedDialogs`. Their
  *chat replies* now come from the container; their on-screen text does not. Additive when wanted, but
  `paper.ui` must import nothing from this plugin, so its strings have to arrive as `Component`
  parameters rather than as a container.
- **Renderer titles** (`MailRenderer`, `TestNotificationRenderer`) — these render *stored* notifications, so
  editing them changes how old notifications read, a different question from changing a command reply.
- **Both feature modules.** The Discord adapter's strings are Discord-shaped and belong in a
  `discord-messages.yml` of its own if ever configured.

**No per-player locale** — one file, one language.

**Unverified on a live server:** that an edited `messages.yml` takes effect on reload, and that a deleted
key prints its own name in game. Everything else is covered by `MessageKeysTest` plus the per-class suites,
which assert against the shipped defaults via `paper.localisation.TestMessages` rather than a hand-built
fixture — so a wording assertion is also an assertion that the default exists and is spelled as asked.

## Persistence layer (`core`)

MyBatis over MariaDB, structured like a smaller `realty`:
- `database.Database` / `database.SqlSessionWrapper` — vendor-neutral; the wrapper exposes typed mappers
  bound to one `SqlSession`/transaction.
- `database.entity` — records mirroring the DDL.
- `database.mapper` — vendor-neutral mapper interfaces.
- `database.maria` — `MariaDatabase` (builds the `SqlSessionFactory`, registers mappers and the
  `UUIDAsBin16Handler`), `MariaSqlSession`, `MariaSchemaMigrator`.
- `database.maria.mapper` — MariaDB mappers with `@Select`/`@Insert`/`@Delete` (and `<script>`/`<foreach>`).
- `database.migration.MigrationStep` + `core/src/main/resources/sql/migrations/V*.sql` — the migrator tracks
  applied versions in `schema_version`. **Adding a migration means adding both the `V*.sql` file and a
  `MigrationStep` entry to `MariaSchemaMigrator.DEFAULT_MIGRATIONS`** — that list is hardcoded, not
  discovered. Three steps: `V1__maria_initial_schema.sql`, `V2__notification_inbox.sql` (`seenTime` plus its
  index) and `V3__player_mute.sql`. Pre-V1 migrations were collapsed into V1 back when there was no data to
  preserve; **that is no longer the rule** — V2 was layered precisely because there is now deployed data.
  `SchemaUpgradeTest` covers the already-at-V1 path `AbstractDatabaseTest` cannot. **A feature module can
  own its own migrations** without joining this list — see "Module system".

Schema (`V1`), plus V2/V3 additions:
- `NotificationTarget(notifTargetId INT, playerUuid BINARY(16), seenTime DATETIME NULL, PRIMARY KEY(notifTargetId, playerUuid))`
  — a target group is the rows sharing a `notifTargetId`. `seenTime` (V2, indexed with `playerUuid`) is
  that member's read marker. New group ids come from `MAX(id)+1` inside the enqueue transaction.
- `Notification(notifKey PK, notifScheduledTime, notifExpiryTime NULL, notifTargetId, notifPayloadType, notifPayload JSON, notifPriority)`
  with indexes on `notifTargetId`, `notifPayloadType`, `notifScheduledTime`, `notifExpiryTime`.
- A trigger `trg_delete_targetless_notification` (`AFTER DELETE ON NotificationTarget`) deletes a
  notification once its group has no members. It is a **single-statement body** (no `BEGIN…END`) because
  `MariaSchemaMigrator` splits scripts on `;`.
- `PlayerNotificationMute(playerUuid BINARY(16) PK, mutedTime DATETIME NOT NULL)` — V3, presence is the mute.
- `PlayerNotificationPreference(playerUuid BINARY(16), dataType VARCHAR(64), medium VARCHAR(64), PRIMARY KEY(all three))`
  — one row per preferred medium **per `dataType`**, so a preference is set-valued within each type.

`player-notifications.drawio` is the schema's design source (conceptual names like `notif_key`; the DDL
uses camelCase). It predates the `dataType` column (originally `category`) and has not been updated.

## Testing gotchas

- `:core:test` **and `:platform:discord-adapter:test`** need a **running Docker daemon**. Without it the
  suite fails rather than skipping. The adapter's fixture (`discord.schema.DiscordSchemaTestSupport`) hands
  each test its **own fresh schema** rather than truncating a shared one, because its migrator tests assert
  on DDL and version bookkeeping and so must start with no tables.
- `api`, `core` **and `platform:paper-plugin`** declare **`testRuntimeOnly("io.papermc.paper:paper-api")`**.
  `compileOnlyApi` is not on the test runtime classpath, so without it tests touching Adventure/Bukkit types
  die at discovery with `NoClassDefFoundError: net/kyori/adventure/text/Component`.
- **Counting results: glob `*.xml`, not `TEST-*.xml`.** On Windows, Gradle shortens result filenames for
  `@Nested` classes to dodge the path-length limit, producing `__TEST-<hash>…` names. Several classes here
  (`NotificationMapperTest`, `PlayerNotificationPreferenceTest`) put **all** their `@Test` methods inside
  `@Nested` inner classes, so a `TEST-*.xml` glob silently omits them.
- `--tests "<pattern>"` can report **BUILD SUCCESSFUL while matching nothing.** Check the result count, not
  the exit status.

Current baseline, **every module run fresh with Docker available**: **39 in `:api:test`, 136 in
`:core:test`, 258 in `:platform:paper-plugin:test`, 214 in `:platform:discord-adapter:test`, 21 in
`:platform:essentials-mail-converter:test`** — all passing, after persistent/offline broadcasts landed.

Two of these correct long-stale entries rather than growing: `:core:test` was recorded as 126 and was
actually **131** before this change (136 after the five new `PersistentBroadcastTest` cases), and
`:platform:paper-plugin:test` was recorded as 217 and was actually **218**. Every previous entry here has
been wrong by a few — 206 against an actual 212, 30 against an actual 34 — so treat these as needing a
fresh run rather than arithmetic on the last one.

## Current state

The project builds end-to-end. The enqueue → deliver path is complete: `enqueueNotification` persists the
`notifPayloadType`; `NotificationDelivery.deliver(UUID[, Instant])` resolves due notifications, decodes each
payload, dispatches by the precedence rule, and stamps `seenTime` for `MARK_SEEN` — delivery no longer
destroys its own input. Preferences are stored and resolved per `dataType` end-to-end, with categories
layered on top purely as display/grouping.

Known gaps / notes:

- **`NotificationCategoryRegistry` has no in-tree consumer.** No module calls
  `claimDataType`/`registerCategory`, so the code-registry half (and the two-pass rebuild ordering in
  `onEnable`) is exercised only by unit tests, never end-to-end through a real module class loader. **The
  same applies to `defaults/categories.yml`**: a stock install writes an empty `categories` node forever.
  Moving the host categories out of `categories.yml` into the code registry was considered and rejected —
  it would change what a stock install's dialogs are built from.
- **The module-supplied type-name layer has no in-tree consumer.** Nothing calls `registerDisplayName`, so
  every entry in `defaults/type-names.yml` is a title-cased fallback and that branch of `TypeNames` runs
  only in unit tests. Naming the host's own three types was left out deliberately.
- **Configurable type names are unverified on a live server.** Task 7's checklist in
  `…/plans/2026-08-23-configurable-type-names.md` **has not been run**. Underneath: `TypeNamesTest` (13),
  `TypeNameDefaultsWriterTest` (9), five api registry cases, and one `PreferenceDialogsTest` case pinning
  that only the row's second half is configurable.
- **Delivery has two triggers: joining, and `/notifications test`.** The remaining gap is that a
  notification enqueued for an **already-online** player waits until their next join — there is no push
  path, because that would need the enqueue call to reach the Paper layer, which `NotificationService` in
  `core` deliberately does not do.
- **`ChatSink`, `DialogSink` and the six preference dialogs are unverified by automated tests** — check by
  hand with `runServer`.
- **The Discord adapter's end-to-end path has never been run.** JDA login, the DiscordSRV lookup, module
  class loading through the shaded jar, and an actual DM landing all need a real token and a link.
  Checklist: Task 8 of `…/plans/2026-07-29-discord-adapter.md`. `/notifications test` is the intended way
  to drive it, and `/notifications link discord` can supply the link instead of a DiscordSRV account.
- **`/notifications test`'s own wiring is unverified.** The path underneath it (`registerJsonRenderable` →
  enqueue → deliver → render → fan-out → prune) **is** covered by `core`'s `RenderedDeliveryTest`.
- **The Discord slash-command surface has never run against a live bot.** Every view class is unit tested
  (82 tests), but registration, the `/mail send` modal, component interactions and ephemeral editing need a
  real token and a linked account. **Task 13's 19-item checklist has not been run**, and it predates the
  four-button preference screen.
- **Discord linking's end-to-end path has never been run.** Checklists: Task 7 of
  `…/plans/2026-07-30-embedded-discord-linking.md` and Task 6 of
  `…/plans/2026-07-30-account-link-subcommand.md` (**not yet run**; `AccountLinkDispatcher` underneath **is**
  unit tested). Remaining gaps in the feature: **no admin link management** (a stuck link needs a manual
  `DELETE` against `DiscordAccountLink`); **no rate limiting** on link attempts (a 6-character code over a
  32-character alphabet with a 10-minute window makes brute force impractical rather than impossible — add
  a per-Discord-user limit if abuse is seen); codes do not survive a restart, by design; one Discord
  account per player, enforced in the schema.
- **`notifPayload` is a `JSON` column**, so payloads must be valid JSON. A payload mapped to `String.class`
  is JSON-encoded on write and arrives at its processor still quoted — the reason every in-tree payload owns
  a record. `DefaultNotificationService` still pre-registers a `String` serializer and nothing forbids
  `String.class`, so the trap is reachable; a registration guard was considered and deferred.
- **Stored `essentials-mail` *preference* rows are inert.** They predate the Essentials adapter's removal
  and name a medium nothing registers; the host renders an unregistered medium by its raw key and
  `RenderingProcessor` skips it. No admin command prunes them. Unrelated to
  `platform:essentials-mail-converter`, which registers no medium.
- **Partial delivery is silent** under MARK_SEEN-wins, though no longer lossy.
- Target-id allocation via `MAX(id)+1` is not concurrency-safe under parallel enqueues (fine at a plugin's
  write volume).
- **Rows for a category removed from `categories.yml` are kept, not pruned** — they resurface if it is
  re-added, and are invisible meanwhile.
- **The inbox's player-facing surface is unverified.** **Task 8's checklist in
  `…/plans/2026-08-07-notification-inbox.md` has not been run.** Covered underneath: `InboxDeliveryTest`,
  `InboxReadTest`, `InboxEntryRendererTest`, `PageBoundsTest`.
- **Mail's player-facing surface is entirely unverified — nothing about it has run on a live server.**
  **Task 4's 14-item checklist in `…/plans/2026-08-10-first-party-mail.md` has not been run**, nor **Task
  3's six-item checklist in `…/plans/2026-08-20-console-mail-and-minimessage.md`** (console `/mail send`,
  the "Mail from Server" title, a plain player getting literal text until a format node is granted, a
  non-op's `<click>` arriving literal, an op's arriving live, a tag-only message rejected as blank).
- **`/broadcast` has never run on a live server**, transient or persistent. **Task 4's 14-item checklist
  in `…/plans/2026-08-21-broadcast-command.md` and the 13-item one in
  `…/plans/2026-08-29-persistent-offline-broadcast.md` have both not been run.** Underneath: 73 unit
  tests, no Docker or server needed. The offline path additionally needs a **real LuckPerms install** —
  `LuckPermsPermissionLookup`'s three-stage group resolution is the one piece of real logic in this
  feature with no automated coverage at all, and the two cases most worth checking by hand are a
  permission held only through a group (stage 1) and a server with no LuckPerms at all (the guard).
- **The global mute's player-facing surface is unverified.** **The 12-item checklist in
  `…/plans/2026-08-20-global-mute.md` has not been run.** Covered: `PlayerMuteTest` and `MutedDeliveryTest`
  (against real MariaDB, including a case proving a *bespoke processor* is gated too), plus
  `MailNotifierTest`, `JoinDeliveryListenerTest`, `PreferenceEditSessionTest`.
- **The EssentialsX converter has never run against a real EssentialsX install.** The decision-holding
  pieces are fully unit tested (`ImportedMailTest`, `EssentialsMailConverterTest` — 19) and the entry
  class's EssentialsX-free property is checked with `javap`. Unverified: module loading through the real
  class loader, the guard on a server *without* EssentialsX (the `NoClassDefFoundError` isolation case,
  which takes the whole host plugin down if it regresses), the chunked sweep against a real `IUserMap`, the
  Brigadier tree and its gate, and a mail landing in `/mail`. **Task 6's 10-item checklist in
  `…/plans/2026-08-20-essentials-mail-converter.md` has not been run** — it needs an EssentialsX jar dropped
  into `platform/paper-plugin/run/plugins/` by hand, deliberately not in `downloadPlugins`.
- **The inbox filter's player-facing surface is unverified.** **Task 6's 14-item checklist in
  `…/plans/2026-08-23-filtered-inbox.md` has not been run** — including the two regressions that matter
  most: that a dialog filter does **not** reach `/notifications list` or `clear`, and that `read <entry>`
  now resolves against the last *chat* listing only. Note the checklist predates the `Filter: <label>`
  button becoming a plain **Back**.
- **Inbox size is unbounded** and **seen is per player, not per medium** — both accepted.
- **The orphaned-target leak is fixed.** `deleteExpired`/`deleteByKey`/`deleteByPayloadType`/`deleteByPlayer`
  still delete `Notification` rows without touching `NotificationTarget` (the trigger only fires the other
  way), but `pruneOrphanedTargets()` runs on the periodic prune task. It is a select plus per-group deletes,
  **not** one `DELETE … LEFT JOIN Notification`: MariaDB refuses a statement that reads `Notification` when
  the delete fires the trigger that writes it.
- Deferred to their own designs: **admin Discord link management**, a **`discord-channel-ping` sink**,
  **per-medium delivery tracking**, **actions/buttons** in `RenderableNotification`, and **admin commands**.
