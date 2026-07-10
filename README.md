# PlayerNotifications

PlayerNotifications is a plugin for [Paper](https://papermc.io/) Minecraft servers that stores
per-player notifications in a database and delivers them through pluggable, payload-typed
**processors**. Other plugins enqueue notifications against the shared `NotificationService`;
delivery back-ends (chat, [EssentialsX](https://essentialsx.net/) mail, …) are added as small
feature modules without touching the core.

## Requirements

- **Paper** 1.21.8+
- **Java** 21
- **MariaDB** (or MySQL) to store notifications
- **EssentialsX** — optional, only for the mail delivery module

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

## Modules

| Module | Role |
|--------|------|
| `api` | Public API surface consumers program against (`NotificationService`, processors) |
| `core` | MyBatis/MariaDB persistence, `DefaultNotificationService`, and the delivery loop |
| `platform:paper-plugin` | The Paper plugin bootstrap |
| `platform:essentials-adapter` | Optional feature module: renders notifications as Essentials mail |

## Configuration

On first run the plugin writes two YAML files to its data folder (parsed with
[Configurate](https://github.com/SpongePowered/Configurate)):

`database.yml` — the database connection (the `url` omits the leading `jdbc:`):

```yaml
url: mariadb://localhost:3306/player_notifications
username: ''
password: ''
```

`settings.yml` — plugin behaviour:

```yaml
# How often expired notifications are pruned from the database, in seconds.
prune-interval-seconds: 3600
```

The schema is created and migrated automatically on enable.

## How it works

- A **notification** has a key, a scheduled/expiry time, a priority, an audience (a set of player
  UUIDs), a **data type** string, and a string payload.
- Callers register, per data type, a payload `Class` and a `NotificationProcessor` in the
  `NotificationDataTypeRegistry` (reached via `NotificationService#dataTypeRegistry`).
- The delivery loop resolves a player's due notifications, and for each one invokes the processor
  registered for its data type — **once per target**. Each call returns a `NotificationDisposition`
  (`RETAIN` or `DELETE`); a `DELETE` removes that player from the notification's audience, and once
  the last target is gone the notification itself is deleted.

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
moduleName: essentials-mail-adapter
entryClass: io.github.md5sha256.playernotifications.essentials.EssentialsMailModule
author: md5sha256
expectedPluginClass: io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin
reloadable: false
```

On `initialize` the module resolves the `NotificationService` and registers its processor for a data
type. To build your own, apply the `paper-adapter` Gradle convention and model it on
`platform/essentials-adapter`.

## Development

- `./gradlew build` — build and test everything.
- `./gradlew :core:test` — run the persistence + delivery tests. **These require a running Docker
  daemon**; they spin up a real MariaDB container via [Testcontainers](https://testcontainers.com/).
- `./gradlew :platform:paper-plugin:runServer` — launch a Paper 1.21.8 test server with the plugin
  loaded (needs a reachable MariaDB).

See [CLAUDE.md](CLAUDE.md) for a deeper tour of the module layout, persistence design, and known gaps.
