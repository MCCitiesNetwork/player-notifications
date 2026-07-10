# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

PlayerNotifications is a PaperMC (Spigot) plugin for Minecraft **1.21.8**, targeting **Java 21**. It stores per-player notifications in a MariaDB database and delivers them to players via pluggable, payload-typed processors. Persistence is implemented with MyBatis in the `core` module; the Paper bootstrap and platform integrations live under `platform/`. Cross-cutting infrastructure (a runtime module system, schema migrator, Configurate helpers) comes from the external `plugin-infrastructure` library.

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
  - `NotificationDataTypeRegistry` — maps a string `dataType` → payload `Class<?>`, and payload class → `NotificationProcessor`. The extension point: callers register their own payload types and processors.
  - `Notification` / `ResolvedNotification` — records for the persisted vs. target-resolved forms. `ResolvedNotification` holds a `NotificationTarget` (list of player UUIDs) and carries `notifPayloadType` (the registry data-type string) plus the `String` payload.
  - **`api.processor`** package — `NotificationProcessor<T>` is a pure `@FunctionalInterface`: `NotificationDisposition receiveNotification(T payload, UUID target)` — it processes **one target (audience member) per call** and returns whether the notification should be `RETAIN`ed or flagged for `DELETE`. Composition lives in `NotificationProcessorBuilder` (fluent "chop-down" chaining via `andThen`/`andThenIf`/`onComplete`, folding dispositions with DELETE-wins). `FixedDelayProcessor` wraps a processor with a scheduled delay.
- **`core`** (`io.github.md5sha256.playernotifications.core`) — MyBatis persistence, `DefaultNotificationService`, and `NotificationDelivery` (the delivery loop). `api("org.mybatis:mybatis")`, `api("org.spongepowered:configurate-yaml")`, `implementation("org.mariadb.jdbc:mariadb-java-client")`, `paper-api` compileOnly. See "Persistence layer" below.
- **`platform:paper-plugin`** (`io.github.md5sha256.playernotifications.paper`) — Paper bootstrap. `PlayerNotificationsPlugin.onEnable` loads config, builds a `MariaDatabase`, runs schema migration, constructs `DefaultNotificationService`, registers it under `NotificationService.class` in the Bukkit `ServicesManager`, schedules the async prune task, and starts the module system. Exposes `database()` / `notificationService()` accessors for modules. Applies `shadow` (relocating `org.mariadb`, `org.mybatis`, `org.apache.ibatis`, `org.spongepowered`, `io.leangen.geantyref`) and `run-paper`.
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

## Configuration

All config uses **Configurate** (`YamlConfigurationLoader`), not Bukkit's `getConfig()`. On enable the plugin copies bundled defaults into the data folder, merges in any new keys, and deserializes into `@ConfigSerializable` records:
- `database.yml` → `DatabaseSettings` (in `core`): `url` (JDBC url **without** the `jdbc:` prefix), `username`, `password`.
- `settings.yml` → `PluginSettings` (in `paper-plugin`): `prune-interval-seconds` (default 3600) — how often the async task deletes expired notifications.

Conventions when editing config:
- Every non-null (`@NotNull`, reference-typed) `@Setting` field must also be annotated `@Required`, so a missing key fails loudly instead of deserializing to null.
- Configurate 4.2.0 has **no built-in `java.time.Duration` serializer** (`node.get(Duration.class)` returns null). Use a `long`-seconds field instead, or register a custom serializer.

## Persistence layer (`core`)

MyBatis over MariaDB, structured like a smaller version of the sibling `realty` project:
- `database.Database` / `database.SqlSessionWrapper` — vendor-neutral interfaces; `SqlSessionWrapper` exposes the typed mappers bound to one `SqlSession`/transaction.
- `database.entity` — record entities mirroring the DDL (`NotificationEntity`, `NotificationTargetEntity`).
- `database.mapper` — vendor-neutral mapper interfaces (`NotificationMapper`, `NotificationTargetMapper`).
- `database.maria` — `MariaDatabase` (builds the `SqlSessionFactory`, registers mappers + the `UUIDAsBin16Handler` UUID↔`BINARY(16)` type handler), `MariaSqlSession`, `MariaSchemaMigrator`.
- `database.maria.mapper` — MariaDB mappers with `@Select`/`@Insert`/`@Delete` (and `<script>`/`<foreach>` for batch ops), extending the neutral interfaces.
- `database.migration.MigrationStep` + `core/src/main/resources/sql/migrations/V*.sql` — the migrator tracks applied versions in a `schema_version` table and runs each script once.

Schema (`V1__maria_initial_schema.sql`), two tables:
- `NotificationTarget(notifTargetId INT, playerUuid BINARY(16), PRIMARY KEY(notifTargetId, playerUuid))` — a target group is the set of rows sharing a `notifTargetId`. New group ids come from `MAX(id)+1` allocated inside the enqueue transaction.
- `Notification(notifKey PK, notifScheduledTime, notifExpiryTime NULL, notifTargetId, notifPayloadType, notifPayload JSON, notifPriority)` with indexes on `notifTargetId`, `notifPayloadType`, `notifScheduledTime`, `notifExpiryTime`.
- A trigger `trg_delete_targetless_notification` (`AFTER DELETE ON NotificationTarget`) deletes a notification once its target group has no remaining members. It is a **single-statement trigger body** (no `BEGIN…END`) because `MariaSchemaMigrator` splits scripts on `;`.

`player-notifications.drawio` is the design source for the schema (note it uses conceptual names like `notif_key`; the DDL uses camelCase columns).

## Current state

The project builds end-to-end and the `core` persistence layer — including the delivery loop — is covered by Testcontainers-backed tests. The enqueue → deliver path is complete: `enqueueNotification` persists the notification's `notifPayloadType`; `NotificationDelivery.deliver(UUID[, Instant])` resolves a player's due notifications, looks up the processor for each payload type, invokes it per target, and prunes targets whose processor returns `NotificationDisposition.DELETE` (the trigger then removes notifications with no remaining targets). Known gaps / notes:
- **`NotificationDelivery` is not wired into the plugin yet** — nothing calls `deliver(UUID)` (e.g. on player join). That bootstrap step remains.
- **`notifPayload` is a `JSON` column**, so payloads must be valid JSON; a plain message string must be JSON-encoded. The current String-payload processors therefore receive the JSON-encoded form — consider a `TEXT` column or decoding on the way out.
- Typed (non-`String`) payloads have no decoder wired in `NotificationDelivery` yet; they are logged and retained rather than mis-delivered.
- Target-id allocation via `MAX(id)+1` is not concurrency-safe under parallel enqueues (fine for a plugin's low write volume).
