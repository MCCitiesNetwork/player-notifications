# `/notifications send` Implementation Plan

**Goal:** send one operator-declared notification to one named player, persistent by default and
transient with `--transient`.
**Spec:** `docs/superpowers/specs/2026-09-03-notifications-send-command-design.md`

## Task 1: `SendArguments`

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/send/SendArguments.java`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/send/SendArgumentsTest.java`

**Interfaces:**

```java
public record SendArguments(@NotNull String content, boolean transientSend) {
    public static Result parse(@NotNull String raw);
    public sealed interface Result {
        record Parsed(@NotNull SendArguments arguments) implements Result {}
        record BlankContent() implements Result {}
        record UnrecognisedToken(@NotNull String token) implements Result {}
    }
}
```

- [ ] Write the failing test `SendArgumentsTest` with these cases:
  - `parse("Server restarting")` → `Parsed`, `content` equals `"Server restarting"`, `transientSend` false
  - `parse("Server restarting --transient")` → `Parsed`, content `"Server restarting"`, `transientSend` true
  - `parse("Hello   there  now --transient")` → content `"Hello   there  now"` (interior spacing survives)
  - `parse("Ping --transient --transient")` → `Parsed`, `transientSend` true (idempotent, as `--bypass` is)
  - `parse("   ")` and `parse("--transient")` → `BlankContent`
  - `parse("Ping --transient --wat")` → `UnrecognisedToken("--wat")`
  - `parse("Costs 5-10 diamonds")` → `Parsed`, content unchanged (a lone hyphen is not a flag)
  - `parse("<red>Down in 5m --transient")` → content `"<red>Down in 5m"` (MiniMessage is not touched here)
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*SendArgumentsTest"` — expect FAIL: the class
      does not exist yet
- [ ] Implement `SendArguments.parse` mirroring `BroadcastArguments.parse` verbatim: split
      `raw.trim()` on `\s+`, find the first token in `Set.of("--transient")`, take content as
      `raw.substring(0, tokenOffset(...)).trim()` (never a re-join, so interior spacing survives),
      reject blank content, then loop the remaining tokens accepting only `--transient` and returning
      `UnrecognisedToken` for anything else. Copy `tokenOffset` across — it is private to
      `BroadcastArguments` and lifting it into a shared helper would be a refactor this task has no
      other reason to make. Javadoc the inherited cost: the literal token `--transient` cannot appear
      in the content, and an unknown `--foo` is only rejected when it follows a recognised flag,
      exactly as on `/broadcast`.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: The `send.*` messages

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/localisation/MessageKeys.java`
- modify `platform/paper-plugin/src/main/resources/messages.yml`

**Interfaces:** `MessageKeys.SEND_BLANK_CONTENT`, `SEND_UNRECOGNISED_TOKEN`, `SEND_UNKNOWN_TYPE`,
`SEND_PARSE_FAILED`, `SEND_UNKNOWN_PLAYER`, `SEND_STORED`, `SEND_PUSH_FAILED`, `SEND_SENT`,
`SEND_NOTHING_ENABLED` — values `send.blank-content`, `send.unrecognised-token`, `send.unknown-type`,
`send.parse-failed`, `send.unknown-player`, `send.stored`, `send.push-failed`, `send.sent`,
`send.nothing-enabled`.

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MessageKeysTest"` after adding the nine
      constants but *before* the YAML — expect FAIL: each constant points at a key nothing declares
- [ ] Implement the `send:` block in `messages.yml`, following the styling vocabulary
      (`<green>` happened, `<red>` did not, `<gold>` the thing acted on, `<white>` a live value), every
      key carrying `<prefix>`:

```yaml
send:
  blank-content: '<prefix> <red>Notification content cannot be blank.</red>'
  # <token>
  unrecognised-token: '<prefix> <red>Unrecognised token:</red> <white><token></white>'
  # <type> — declared types live in notification-types.yml.
  unknown-type: '<prefix> <red>There is no notification type named</red> <white><type></white><gray>. Declared types live in notification-types.yml.</gray>'
  # <error> — why the content would not parse as MiniMessage.
  parse-failed: '<prefix> <red>Could not parse the notification content:</red> <gray><error></gray>'
  # <name> — what the sender typed.
  unknown-player: '<prefix> <red>No player named</red> <white><name></white> <red>has played here.</red>'
  # <player> — stored, not necessarily seen: delivery reports no per-sink outcome.
  stored: '<prefix> <green>Notification stored for</green> <gold><player></gold><gray>. It is in their inbox and will be pushed on their next join.</gray>'
  # <player> — the push threw; the notification is stored and unread regardless.
  push-failed: '<prefix> <red>Could not push it to</red> <gold><player></gold><gray> right now. It is stored and stays unread in their inbox.</gray>'
  # <player>
  sent: '<prefix> <green>Notification sent to</green> <gold><player></gold><green>.</green>'
  # <player> — muted, or silenced down to no usable medium.
  nothing-enabled: '<prefix> <yellow><player></yellow> <red>has this type silenced or is muted, so nothing was pushed.</red>'
```

- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: `SendCommand` and its wiring

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/SendCommand.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/NotificationsCommand.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java:451`
- modify `platform/paper-plugin/src/main/resources/paper-plugin.yml`

**Interfaces:**

```java
public final class SendCommand {
    public static final String PERMISSION = "playernotifications.command.send";
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull MessageContainer messages,
                                                                @NotNull Plugin plugin,
                                                                @NotNull Broadcaster broadcaster,
                                                                @NotNull PersistentBroadcaster persistentBroadcaster,
                                                                @NotNull CustomNotificationTypes customTypes);
}
```

`NotificationsCommand.create` gains a final parameter
`@NotNull LiteralCommandNode<CommandSourceStack> sendNode`, attached with `.then(sendNode)`. The node is
built by the caller rather than inside `NotificationsCommand` so that class does not learn the broadcast
types; it already knows nothing about them.

This task has **no unit test**: every line of it is Brigadier registration, `Bukkit` lookups and the
scheduler, none of which construct without a live server — the exception the `implement` skill names.
Task 4 is its verification.

- [ ] Implement `SendCommand`:
  - Node shape: `Commands.literal("send").requires(source -> source.getSender().hasPermission(PERMISSION))`
    → `Commands.argument("player", StringArgumentType.word())` with suggestions from
    `Bukkit.getOnlinePlayers()` → `Commands.argument("type", StringArgumentType.word())` with
    suggestions from `customTypes.keys()` → `Commands.argument("content", StringArgumentType.greedyString())`
    `.executes(...)`.
  - On the command thread: `SendArguments.parse` (switch over the three cases, each replying with its
    key and returning 0), then `customTypes.get(type)` → `SEND_UNKNOWN_TYPE` when empty, then
    `MiniMessage.miniMessage().deserialize(arguments.content())` inside a `try`/`catch (RuntimeException)`
    → `SEND_PARSE_FAILED`. Same order and same reasons as `BroadcastCommand.run`.
  - Then one `runTaskAsynchronously` doing: `resolveRecipient(name)` (copied from
    `MailCommand.resolveRecipient` — online by name, else `getOfflinePlayer` accepted only when
    `hasPlayedBefore()`) → `SEND_UNKNOWN_PLAYER` when null; then, inside a
    `try`/`catch (RuntimeException)` replying `SEND_PUSH_FAILED`:
    - `--transient`: `broadcaster.broadcast(title, content, key, List.of(uuid), false)`; reply
      `SEND_SENT` when it returns 1, `SEND_NOTHING_ENABLED` when 0.
    - default: `persistentBroadcaster.broadcast(title, content, key,
      new CustomNotificationPayload(key, arguments.content()), List.of(uuid), false)`; reply
      `SEND_STORED`, and additionally `SEND_PUSH_FAILED` when `result.failed() > 0`.
  - `title` is `MiniMessage.miniMessage().deserialize(declaredType.title())`, parsed per send so a
    reloaded `notification-types.yml` takes effect — the same private helper `BroadcastCommand.title`
    is, and for the same reason.
- [ ] Wire it: build the node in `registerCommands()` alongside the existing `BroadcastCommand.create`
      call (which already has `this.broadcaster`, `this.persistentBroadcaster` and `this.customTypes` in
      scope) and pass it into `NotificationsCommand.create`.
- [ ] Declare the permission in `paper-plugin.yml`:

```yaml
  playernotifications.command.send:
    description: Send a notification to one player
    default: op
```

- [ ] Run `./gradlew build` — expect PASS (compilation is the check this task has)
- [ ] Commit

## Task 4: Manual verification on a live server

**Files:** none — `./gradlew :platform:paper-plugin:runServer`, with at least one type declared in
`run/plugins/PlayerNotifications/notification-types.yml`, e.g.

```yaml
restart-warning:
  title: "<red><bold>Server Restart</bold></red>"
  display-name: "<red>Restart Warnings</red>"
```

- [ ] `/notifications send <self> restart-warning <red>Down in 5m` → `send.stored`, and the message
      arrives in chat
- [ ] `/notifications` shows it, titled "Server Restart", body "Down in 5m"
- [ ] `/notifications send <self> restart-warning Ping --transient` → `send.sent`, arrives in chat, and
      is **not** in `/notifications`
- [ ] `/notifications send <offline player> restart-warning Welcome back` → `send.stored`; that player
      joins and is pushed it
- [ ] `/notifications send nosuchplayer restart-warning Hi` → `send.unknown-player`
- [ ] `/notifications send <self> nosuchtype Hi` → `send.unknown-type`, and nothing is stored
- [ ] `/notifications send <self> restart-warning §cHi` → `send.parse-failed`
- [ ] `/notifications send <self> restart-warning Ping --wat --transient` → `send.unrecognised-token`
      naming `--wat`
- [ ] `/notifications mute`, then send transiently → `send.nothing-enabled`; send persistently →
      `send.stored`, and it is in the inbox unread
- [ ] Silence `restart-warning` in `/notifications preferences`, then send transiently →
      `send.nothing-enabled`
- [ ] From the console: `notifications send <player> restart-warning Hello` works and replies in the log
- [ ] As a non-op with `playernotifications.command.preferences`: `/notifications send` does not appear
      in tab completion and is refused
- [ ] Tab completion suggests online player names for `<player>` and declared keys for `<type>`
- [ ] Edit the type's `title`, `/notifications reload`, send again → the new title is used, and the
      notification stored *before* the edit also reads with the new title
- [ ] Commit any fixes the checklist turns up

## Task 5: Update `CLAUDE.md`

**Files:** modify `CLAUDE.md`

- [ ] Add `/notifications send <player> <type> <content> [--transient]` to the "Player commands" list,
      noting its own `playernotifications.command.send` (`default: op`), that it is open to the console,
      and that it is persistent by default
- [ ] Note under "Operator-defined notification types" that `/broadcast --type` is **no longer** the only
      thing in-tree that sends one — correcting the sentence that says so
- [ ] Add the new test count to the "Testing gotchas" baseline from a fresh `./gradlew build`, and add
      Task 4's checklist to "Current state" as not-yet-run if it has not been
- [ ] Run `./gradlew build`
- [ ] Commit
