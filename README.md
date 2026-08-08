# PlayerNotifications

PlayerNotifications is a plugin for [Paper](https://papermc.io/) Minecraft servers that stores
per-player notifications in a database and delivers them through pluggable, payload-typed
**processors**. Other plugins enqueue notifications against the shared `NotificationService`;
delivery back-ends (chat, dialogs, [EssentialsX](https://essentialsx.net/) mail, Discord DMs) are
added as small feature modules without touching the core. Every notification also stays in the
player's **inbox** (`/notifications`) until they dismiss it.

**Using the plugin as a player or server operator?** See [docs/USAGE.md](docs/USAGE.md).

## Requirements

- **Paper** 1.21.8+
- **Java** 21
- **MariaDB** (or MySQL) to store notifications
- **EssentialsX** — optional, only for the mail delivery module
- A **Discord bot token** — optional, only for the Discord DM module

## Build

From the repository root:

```bash
./gradlew :platform:paper-plugin:shadowJar
```

Install the JAR from `platform/paper-plugin/build/libs/` whose name ends with `-all.jar`.

The optional Essentials mail delivery module is built separately and dropped into the plugin's
`modules/` folder (see [Feature modules](#feature-modules)):

```bash
./gradlew :platform:essentials-adapter:jar
```

The Discord DM module must be built as its **shaded** jar — it bundles its own relocated JDA, so the
plain `jar` output contains no Discord library at all:

```bash
./gradlew :platform:discord-adapter:shadowJar
```

## Modules

| Module | Role |
|--------|------|
| `api` | Public API surface consumers program against (`NotificationService`, processors) |
| `core` | MyBatis/MariaDB persistence, `DefaultNotificationService`, and the delivery loop |
| `platform:paper-plugin` | The Paper plugin bootstrap |
| `platform:essentials-adapter` | Optional feature module: renders notifications as Essentials mail |
| `platform:discord-adapter` | Optional feature module: delivers notifications as Discord DMs, and owns its own account-link schema |

## Configuration

On first run the plugin writes three YAML files to its data folder (parsed with
[Configurate](https://github.com/SpongePowered/Configurate)):

`database.yml` — the database connection (the `url` omits the leading `jdbc:`):

```yaml
url: mariadb://localhost:3306/player_notifications
username: ''
password: ''
```

`settings.yml` — plugin behaviour:

```yaml
prune-interval-seconds: 3600     # how often expired notifications are pruned, in seconds
default-media: [chat]            # delivery methods for a player with no saved preference
deliver-on-join: true            # push waiting notifications when a player logs in
join-delivery-delay-seconds: 3   # how long after joining to wait; 0 = immediately
inbox-page-size: 7               # inbox entries per page; clamped to 1-20
```

`categories.yml` — the display grouping used by the "Notification types" preference screen. See
[docs/USAGE.md](docs/USAGE.md) for the full reference on all three files.

The schema is created and migrated automatically on enable.

## How it works

- A **notification** has a key, a scheduled/expiry time, a priority, an audience (a set of player
  UUIDs), a **data type** string, and a string payload.
- Callers register, per data type, a payload `Class` and a `NotificationProcessor` in the
  `NotificationDataTypeRegistry` (reached via `NotificationService#dataTypeRegistry`).
- Callers can instead register a `NotificationRenderer`, which converts the payload once into a
  medium-neutral title and body. The framework then fans it out to whichever media that player
  prefers, each backed by a `NotificationSink`. Adding a medium requires no change to any payload.
- The delivery loop resolves a player's due, unread notifications and, for each one, invokes the
  processor registered for its data type — **once per target**. Each call returns a
  `NotificationDisposition` (`RETAIN` or `MARK_SEEN`); `MARK_SEEN` stamps that target as read.
  **Delivery marks read; it does not delete.** The notification stays in the player's inbox until
  they dismiss it or it expires.

### Consuming the service

Other plugins obtain the service from Bukkit's `ServicesManager` (no hard dependency required):

```java
NotificationService notifications = getServer().getServicesManager()
        .load(NotificationService.class);

// Deliver "welcome" text to a player as Essentials mail.
notifications.enqueueNotification(new ResolvedNotification(
        "welcome:" + playerId,          // unique key
        Instant.now(),                   // scheduled time
        null,                            // no expiry
        new NotificationTarget(List.of(playerId)),
        "essentials-mail",               // data type
        "\"Welcome to the server!\"",   // JSON payload
        0),                              // priority
        true);                           // overwrite an existing key
```

## Feature modules

Delivery back-ends are **feature modules** — jars dropped into `<data folder>/modules/`, loaded at
runtime (via [plugin-infrastructure](https://github.com/MCCitiesNetwork/plugin-infrastructure)).
Each jar contains a `module-manifest.yml` and an entry class implementing `PluginModule`:

```yaml
module-name: essentials-mail-adapter
entry-class: io.github.md5sha256.playernotifications.essentials.EssentialsMailModule
author: md5sha256
expected-plugin-class: io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin
reloadable: false
```

Manifest keys are **kebab-case** — a camelCase key does not fail, it silently deserializes to null.

On `initialize` the module resolves the `NotificationService` and registers its processor, renderer
or sink. To build your own, apply the `paper-adapter` Gradle convention and model it on
`platform/essentials-adapter`.

## Development

- `./gradlew build` — build and test everything.
- `./gradlew :core:test` — run the persistence + delivery tests. **These require a running Docker
  daemon**; they spin up a real MariaDB container via [Testcontainers](https://testcontainers.com/).
  `./gradlew :platform:discord-adapter:test` needs Docker for the same reason.
- `./gradlew :platform:paper-plugin:runServer` — launch a Paper 1.21.8 test server with the plugin
  loaded (needs a reachable MariaDB).

See [CLAUDE.md](CLAUDE.md) for a deeper tour of the module layout, persistence design, and known gaps.
