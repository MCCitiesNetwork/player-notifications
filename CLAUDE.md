# CLAUDE.md

Guidance for Claude Code working in this repository. Design rationale lives in
`docs/superpowers/specs/` (`…/specs/` below) and plans in `docs/superpowers/plans/` (`…/plans/`).

## Workflow skills

Project-local skills in `.claude/skills/` replace `superpowers` (disabled here). Invoke before acting;
they run inline and dispatch no subagents. Subagents are opt-in.

| Situation | Skill |
|---|---|
| New feature, API or behaviour change — before code | `design` |
| Writing feature/bugfix code, executing a plan | `implement` |
| Any bug, test/build failure, unexpected behaviour | `debug` |
| Before claiming done, and before committing | `ship` |

Where docs and code disagree, **the code wins** — fix the doc in the same commit.

## Overview

PaperMC plugin for Minecraft **26.1.2**, Java **25**. Per-player notifications stored in MariaDB
(MyBatis, in `core`), delivered either by payload-typed processors or — usually — the **renderer/sink**
path, fanning out to each player's preferred media (chat, dialog, Discord DM). Module system, schema
migrator and Configurate helpers come from the external `plugin-infrastructure` library. First-party
`mail` is stored and read via `/mail` but deliberately never delivered.

## Build & run

Gradle 9.3.0 wrapper (`./gradlew` from Bash, `gradlew.bat` from PowerShell).

- `./gradlew build` — everything; `paper-plugin`'s `build` depends on `shadowJar`.
- `./gradlew :platform:paper-plugin:shadowJar` — the plugin jar.
- `./gradlew :platform:discord-adapter:shadowJar` — must be the **`-all`** jar (bundles relocated JDA).
- `./gradlew test`; `:core:test` and `:platform:discord-adapter:test` **need Docker** (Testcontainers,
  `mariadb:11.7`). `:platform:essentials-mail-converter:test` is hermetic.
- `./gradlew :core:test --tests "…SomeTest"` — single class/method.
- `./gradlew :platform:paper-plugin:runServer` — live Paper server under `platform/paper-plugin/run/`;
  needs MariaDB (`database.yml`), depends on `installFeatureModules`, downloads DiscordSRV `v1.30.5`.
- `installFeatureModules` `Sync`s module jars into `run/plugins/PlayerNotifications/modules/` (files, not
  classpath). Adding a module = one `featureModules(project(path = ":platform:<name>", configuration =
  "moduleJar"))` line in `platform/paper-plugin/build.gradle.kts`. The Sync owns only top-level `*.jar`.

## Publishing

`api` and `core` publish as `io.github.md5sha256:player-notifications-api`/`-core` (prefixed artifact
ids, unprefixed project names) to `https://maven.minecraftcitiesnetwork.com/releases`; 1.0.0 is deployed.

- Convention plugin: `buildSrc/src/main/kotlin/player-notifications-publish.gradle.kts`
  (`deployUrl`/`deployUsername`/`deployPassword` properties).
- `.github/workflows/deploy-api.yml` on release/dispatch; `-SNAPSHOT` → `/snapshots`. Secrets
  `MAVEN_REPOSITORY_*` (URL secret is the bare host).
- **Version lives only in root `gradle.properties`.**
- **Publishing is not gated on tests** — run the suite before releasing.

## Module architecture

Dependency direction **platform → core → api**; `api` depends only on Paper. Type-safe project accessors on.

- **`api`** — public API, `compileOnlyApi` on paper-api.
  - `NotificationService` (enqueue, resolve, clear/delete, prune, registries, inbox read API).
  - `NotificationDataTypeRegistry` — `dataType` ↔ payload class → processor/serializer/renderer/display
    name. `unmapDataType` removes a mapping only (never cascades).
  - `NotificationSinkRegistry` — keyed by **medium** (`chat`, `dialog`, `discord-dm`;
    `discord-channel-ping` reserved, unimplemented).
  - `api.processor` — `NotificationProcessor<T>` (one target per call, `RETAIN`/`MARK_SEEN`),
    `NotificationProcessorBuilder`, `FixedDelayProcessor`.
  - `api.render` — `RenderableNotification` (Component title+body, **no Player/Audience**),
    `NotificationRenderer<T>`, `NotificationSink` (`DELIVERED`/`UNREACHABLE`/`UNSUPPORTED`),
    `NotificationPreferences` (`SILENCED_MEDIUM = "none"`, not a sink; `isMuted` default `false`),
    `RenderingProcessor<T>`, sinks `ChatSink`/`DialogSink`.
  - `api.category`, `api.link` (`AccountLinkProvider`/`Registry`, on the plugin, not the service),
    `api.serialize` (`PayloadSerializer`), `InboxEntry`/`InboxPage`.
- **`core`** — MyBatis persistence, `DefaultNotificationService`, `NotificationDelivery`,
  `DatabaseNotificationPreferences`, `NotificationCategories`, `JacksonPayloadSerializer`.
- **`platform:paper-plugin`** — bootstrap, commands, dialogs, listeners, module host. Exposes
  `database()`/`notificationService()`/`sinkRegistry()`/`preferences()`/`categories()`/
  `notificationDelivery()`/`typeNames()`/`inboxPageSize()` for modules.
- **`platform:discord-adapter`** — Discord DM sink, linking, slash commands; owns its own schema.
- **`platform:essentials-mail-converter`** — one-shot EssentialsX mail import.

**"Silence" ≠ "mute"**: a silence is a standing per-type `{none}` choice; a mute is a temporary
player-level switch. Keep the words apart on every surface.

### Build conventions (`buildSrc/src/main/kotlin/`)

- `player-notifications-conventions` — Java 25, UTF-8, JUnit 5, repos. Every module.
- `player-notifications-shared-relocation` — host **and every module**; relocates `HostShading.PACKAGES`
  to `HostShading.BASE`. Packages a module bundles itself go in `sharedRelocation { bundledByModule.add(...) }`.
- `paper-adapter` — feature modules: `compileOnly` **and `testImplementation`** on `:platform:paper-plugin`,
  shadow, shared relocation, a `moduleJar` configuration that is **always the `-all` jar**.
- Shadow 9.3.1 comes from buildSrc — apply `id("com.gradleup.shadow")` without a version. Conventions apply
  siblings by `id(...)`, not backtick accessors.
- `plugin-infrastructure` is `com.minecraftcitiesnetwork:plugin-infrastructure` from
  `maven.democracycraft.net/snapshots` (group/host mismatch is upstream's, not a typo). Not an includeBuild.

## Module system

Jars in `<dataFolder>/modules/` with `module-manifest.yml` and an entry class implementing `PluginModule`.

```yaml
module-name: discord-adapter
entry-class: io.github.md5sha256.playernotifications.discord.DiscordModule
author: md5sha256
expected-plugin-class: io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin
reloadable: false
```

- **Keys are kebab-case** (applies to every `@ConfigSerializable` here). A camelCase key silently
  deserializes to `null`; a null `expected-plugin-class` NPEs and aborts `onEnable`.
- Modules may own schema over the host's pool: load scripts via **the module's own** class loader, guard
  `addMapper` with `hasMapper`, and relocate MyBatis to the host prefix.

## Delivery

Design: `…/specs/2026-07-27-notification-renderers-design.md`.

- **Dispatch precedence in `NotificationDelivery`:** explicit processor → renderer via
  `RenderingProcessor` → log and retain. An explicit processor **bypasses per-type preferences** (but not
  the mute). `mail`'s RETAIN processor relies on this — **do not remove or fold it into the renderer path**.
- **Fan-out is MARK_SEEN-wins**: any `DELIVERED` marks seen (not deleted). Seen is never pushed again.
  Partial delivery is silent but not lossy. A throwing sink counts as `UNREACHABLE`.
- `SILENCED_MEDIUM` is filtered in `RenderingProcessor` (retain unread). The **mute gate** is at the top
  of `NotificationDelivery.deliver`.
- Components are the lingua franca: no click-event-dependent bodies; no actions/buttons in notifications.
- **`ChatSink` returns `UNREACHABLE` for an offline player.** (Docs once claimed otherwise; that caused a bug.)
- Triggers: join (`JoinDeliveryListener`) and `/notifications test`. No push for already-online players.

Preference resolution, `preferredMedia(player, dataType)`: exact rows → `*` rows →
`delivery-defaults.yml` → `default-media`. The single-arg form never consults `delivery-defaults.yml`.
`effectiveMediaByDataType` must walk the identical chain (it drives the dialogs).

## Features — one line each

| Feature | Summary | Spec (`…/specs/`) |
|---|---|---|
| Join delivery | Push unseen on join, gated by `deliver-on-join`; unread-count line sent regardless (except when muted). Body re-checks `isOnline()`. | `2026-07-30-join-delivery-trigger-design.md` |
| Per-type delivery defaults | `delivery-defaults.yml` overrides `default-media` per type, below all player rows; `[none]` = opt-in; empty list and `*` key rejected. | `2026-09-04-per-type-delivery-defaults-design.md` |
| Discord adapter | `discord-dm` sink, own JDA bot/token/schema; DiscordSRV is a link source only. `DiscordBot.shutdown` **must block**. Verify relocation with `unzip -l` after dependency bumps. | `2026-07-29-discord-adapter-design.md`, `2026-07-30-module-owned-discord-schema-design.md` |
| Discord linking | `/notifications link discord` → `/link <code>` to the bot; `AccountLinkRegistry`, no module-owned command. | `2026-07-30-embedded-discord-linking-design.md`, `2026-07-30-account-link-subcommand-design.md` |
| Discord slash commands | `/mail`, `/notifications` from Discord. **Only `SlashCommandRegistrar` may call `updateCommands` / own `onReady`.** | `2026-08-20-discord-slash-commands-design.md` |
| Mail | `mail` dataType, stored never delivered (RETAIN processor), no expiry, MiniMessage sanitized at send time per 15 `…mail.format.*` perms (all `default: op`). | `2026-08-10-first-party-mail-design.md`, `2026-08-20-console-mail-and-minimessage-design.md` |
| Mail arrival notice | `MailNotifier`: sender + 100-char preview via sinks, recognised by identity marker (must render as nothing). Never enqueued. | `2026-08-30-mail-arrival-notice-sender-design.md` |
| Broadcast | `/broadcast` with `--perm --chain --persistent --offline --limit --type --bypass`. `--offline` needs `--persistent`, a `--perm`, and LuckPerms. `--limit 0` rejected. | `2026-08-21-broadcast-command-design.md`, `2026-08-29-persistent-offline-broadcast-design.md` |
| Operator-defined types | `notification-types.yml`; one shared `CustomNotificationPayload`; withdraw with `unmapDataType`, **never** `unregisterPayloadMapping`. | `2026-08-30-operator-defined-notification-types-design.md` |
| `/notifications send` | One declared type to one player, persistent by default. | `2026-09-03-notifications-send-command-design.md` |
| EssentialsX converter | `/essmailconvert preview|confirm`, not idempotent; entry class must name no EssentialsX type (check with `javap`). | `2026-08-20-essentials-mail-converter-design.md` |
| Inbox | Delivery marks seen; dismiss deletes target row. `null` filter = none, empty = match nothing. **A `multiAction` dialog with zero buttons fails to encode.** | `2026-08-07-notification-inbox-design.md` |
| Inbox filtering | `/notifications` opens a category picker; commands are never filtered. | `2026-08-23-filtered-inbox-design.md` |
| Global mute | `PlayerNotificationMute` table; never touches preference rows. | `2026-08-20-global-mute-design.md` |
| Type names | `type-names.yml` > module `registerDisplayName` > title-cased key. | `2026-08-23-configurable-type-names-design.md` |
| Categories | Display/grouping only; dispatch never reads them. `defaults/categories.yml` is written, never read. | `2026-07-28-categorised-notification-preferences-design.md`, `2026-08-23-module-category-defaults-design.md` |
| Messages | `messages.yml` via `MessageContainer`; see Messages below. | `2026-08-21-configurable-messages-design.md`, `2026-08-23-message-styling-design.md` |

**Class-loading hazard:** `paper-plugin.yml` has `join-classpath: true` soft dependencies on DiscordSRV,
Essentials and LuckPerms. An **unloaded** (not merely disabled) one makes class loading throw `zip file
closed`. Fix: remove that jar. Code fixes were designed and deliberately declined — reopen only with the user.
Optional-dependency bindings (`LuckPermsBinding`, `EssentialsMailBinding`) must name no foreign type, and
must catch `LinkageError`.

## Player commands

Brigadier via `LifecycleEvents.COMMANDS` (no `commands:` block). Blocking work runs off the main thread.

- `/notifications` (`/notifs`) — inbox filter picker. `list [page]`, `read|delete <entry>`, `clear`.
- `/notifications preferences [media|types|mute|unmute]`; `mute`/`unmute` also at top level (immediate).
- `/notifications link [provider] [status]`, `unlink [provider]` — `…command.link`.
- `/notifications send <player> <type> <content> [--transient] [--bypass]` — `…command.send`, op.
- `/notifications test [message]` — `…command.test`; `reload` — `…command.reload`, console-usable,
  does not reload `database.yml`.
- `/mail` (dialog), `send|list|read|delete|clear`; `send` needs `…mail.send` too and is console-usable.
- `/broadcast …` — `…command.broadcast`, op, console-usable.
- Root permission `playernotifications.command.preferences` (default true).

Preference dialogs (`PreferenceDialogRouter`): root (navigation only) → medium picker/editor, category
picker/editor, mute confirm. Apply/Discard on every editing screen; Back abandons that screen's
checkboxes; nothing persists until Apply. No player-facing reset to server default (core's `resetAll` is
kept, uncalled). Dialog input keys are positional. `paper.ui` must import nothing from this plugin.

## Configuration

Configurate, not `getConfig()`. Bundled defaults are copied **only when absent; files are never
rewritten.** `messages.yml` gets an in-memory defaults underlay (bundled node is the `mergeFrom`
receiver); `settings.yml`, `categories.yml`, `database.yml` get `warnAboutMissingConfigKeys`.

- `database.yml` — `url` (no `jdbc:`), `username`, `password`.
- `settings.yml` — `prune-interval-seconds`, `default-media`, `deliver-on-join`,
  `join-delivery-delay-seconds`, `inbox-page-size` (1..20).
- `categories.yml`, `type-names.yml`, `notification-types.yml`, `delivery-defaults.yml` — the last three
  are flat maps read with `childrenMap()` and excluded from missing-key warnings.
- `defaults/` — generated, never read.
- Every `@NotNull` reference `@Setting` must be `@Required`. Configurate 4.2.0 has no `Duration`
  serializer — use long seconds.

## Messages

- Every key needs a `MessageKeys` constant **and** a YAML line; `MessageKeysTest` checks both directions.
  A missing key renders as its own name.
- `value()` for anything a player typed; `markup()` only for plugin-built Components.
- Varying text = a key per case (singular/plural, read/unread), never a ternary.
- Clicks are attached in Java; don't put a click target in `messages.yml` without subclassing the container.
- Style vocabulary and `<prefix>` rules are in the file's header comment.
- Dialog text, renderer titles and both feature modules remain hardcoded on purpose.

## Persistence (`core`)

- `database.{Database,SqlSessionWrapper,entity,mapper}` vendor-neutral; `database.maria` MariaDB impls.
- Migrations: `V1__maria_initial_schema`, `V2__notification_inbox`, `V3__player_mute` in
  `core/src/main/resources/sql/migrations/`. **Adding one = the `.sql` file + a `MigrationStep` in
  `MariaSchemaMigrator.DEFAULT_MIGRATIONS`.** Layer new migrations; never collapse (there's deployed data).
- Tables: `Notification`, `NotificationTarget` (`seenTime`), `PlayerNotificationPreference`
  (`dataType VARCHAR(64)`), `PlayerNotificationMute`.
- Trigger `trg_delete_targetless_notification` is single-statement (the migrator splits on `;`). MariaDB
  rejects a DELETE that reads `Notification` while firing it — use select-then-delete.
- `<foreach>` over an empty collection emits `IN ()`; branch to `AND 1 = 0`.
- `notifPayload` is JSON — never map a payload to `String.class`.

## Testing gotchas

- `:core:test` and `:platform:discord-adapter:test` fail (not skip) without Docker.
- `api`, `core`, `paper-plugin` need `testRuntimeOnly("io.papermc.paper:paper-api")`.
- Count results with `*.xml`, not `TEST-*.xml` (Windows shortens `@Nested` result names).
- `--tests` can report success while matching nothing — check the count.

## Unverified on a live server

None of these manual checklists has been run.

| Feature | Plan (`…/plans/`) | Task |
|---|---|---|
| Discord adapter | `2026-07-29-discord-adapter.md` | 8 |
| Embedded Discord linking | `2026-07-30-embedded-discord-linking.md` | 7 |
| Account link subcommand | `2026-07-30-account-link-subcommand.md` | 6 |
| Discord slash commands | `2026-08-20-discord-slash-commands.md` | 13 |
| Join delivery | `2026-07-30-join-delivery-trigger.md` | 3 |
| Inbox | `2026-08-07-notification-inbox.md` | 8 |
| Filtered inbox | `2026-08-23-filtered-inbox.md` | 6 |
| Mail | `2026-08-10-first-party-mail.md` | 4 |
| Console mail / MiniMessage | `2026-08-20-console-mail-and-minimessage.md` | 3 |
| Global mute | `2026-08-20-global-mute.md` | — |
| Broadcast | `2026-08-21-broadcast-command.md` | 4 |
| Persistent/offline broadcast | `2026-08-29-persistent-offline-broadcast.md` | — |
| Operator-defined types | `2026-08-30-operator-defined-notification-types.md` | — |
| `/notifications send` | `2026-09-03-notifications-send-command.md` | 4 |
| Per-type delivery defaults | `2026-09-04-per-type-delivery-defaults.md` | — |
| Configurable type names | `2026-08-23-configurable-type-names.md` | 7 |
| EssentialsX converter | `2026-08-20-essentials-mail-converter.md` | 6 |

Also unverified by any automated test: `ChatSink`, `DialogSink`, the preference/inbox dialogs, the JDA
listeners, `LuckPermsPermissionLookup`.

## Known gaps

- `NotificationCategoryRegistry` has no in-tree consumer.
- No push to already-online players; no per-medium delivery tracking; partial delivery is silent.
- `MAX(id)+1` target ids aren't concurrency-safe. Inbox size is unbounded.
- Stale `essentials-mail` preference rows and rows for removed categories are kept.
- No admin commands (stuck mute or link needs a manual `DELETE`); no link-attempt rate limiting.
