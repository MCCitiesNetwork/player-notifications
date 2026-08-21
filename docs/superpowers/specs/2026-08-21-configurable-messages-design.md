# Configurable Messages — Design

**Date:** 2026-08-21
**Status:** proposed

## Goal

Move the plugin's chat-facing text out of Java string literals and into a server-editable
`messages.yml`, rendered as MiniMessage through the `MessageContainer` that `plugin-infrastructure`
already provides and that the sibling `realty` project already uses.

Scope for this pass is **chat replies and command output in the host plugin only** — roughly 40
call sites across ten classes. Dialog labels, renderer titles, the Discord adapter and the
EssentialsX converter keep their hardcoded text; see "Known limitations".

## Why this shape

The infrastructure `MessageContainer` is a flat `key -> raw MiniMessage string` map, with
`load(ConfigurationNode)` flattening a nested YAML tree into dotted keys. Its public surface:

| Method | Use |
|---|---|
| `messageFor(key, TagResolver...)` | render to a `Component` |
| `plaintextMessageFor(key, TagResolver...)` | render and flatten to `String` |
| `miniMessageFormattedFor(key)` | the raw stored string |
| `static value(name, String)` | a plain-text placeholder |
| `static markup(name, ComponentLike)` | a `Component` placeholder |
| `setMessage` / `clear` / `load` / `save` | population |

`<prefix>` is resolved by the base class from the `prefix` key, cached in a `volatile` field.

**A missing key renders as the key itself.** That is the most important property of this class for
our purposes: a typo does not throw, it prints `mail.sent` to a player. Everything in the testing
strategy below exists because of it.

### No subclass

`realty` subclasses `MessageContainer` to add one method, `deserializeRaw(String)`, because its
paginated commands substitute a `/realty …` command into a `<click>` tag argument — a position no
`TagResolver` can fill.

This plugin has the same *shape* of case: `InboxChatRow` and `MailChatRow` build a `runCommand`
click event from the router's `commandLabel`, which differs between `/notifications` and `/mail`.
But they attach it in Java, on the already-rendered `Component`, so the message key holds only text
and the click never appears in the YAML. We therefore use the base class **unmodified**.

This is a deliberate divergence from realty, on the grounds that copying a method across because
the situation resembles the one it was written for is how a codebase accumulates unused surface. If
a later pass genuinely needs an operator-movable click target, add the subclass then — the
reasoning above has expired at that point, and the file should say so.

The cost, accepted: an operator can change a listing row's *wording* but not where its click goes.

## Architecture

### New

- `paper.localisation.MessageKeys` — `public static final String` constants, grouped by command,
  mirroring realty's file. One constant per key in `messages.yml`, no exceptions: the constants are
  what the resolution test walks.
- `platform/paper-plugin/src/main/resources/messages.yml` — the shipped defaults.

### Changed

`PlayerNotificationsPlugin` gains:

```java
private final MessageContainer messages = new MessageContainer();

public @NotNull MessageContainer messages() { return this.messages; }

private void reloadMessages() throws IOException {
    this.messages.load(copyDefaultsYaml("messages"));
}
```

`reloadMessages()` is called from `onEnable` **before** `registerCommands()` — every command class
takes the container as a constructor argument — and again from `reload()`, alongside the existing
settings and categories reloads. `copyDefaultsYaml` already exists at
`PlayerNotificationsPlugin.java:499` and already does copy-then-merge, so a server that upgrades
gains new keys without losing its edits.

The container instance is **final and reloaded in place**, never replaced. Classes constructed at
enable hold the reference for the plugin's lifetime, so `/notifications reload` reaches them with no
re-registration — the same idiom `DatabaseNotificationPreferences.reloadDefaultMedia` uses for
`default-media`.

### Colour moves into the YAML

Today colour is a `NamedTextColor` argument in Java. It moves into the MiniMessage string, since a
hardcoded red is exactly what an operator wants to change. `Component.text("…", RED)` becomes a key
whose value is `<red>…</red>`.

## Call sites in scope

| Class | Sites | Key group |
|---|---|---|
| `diagnostic/TestNotificationSender` | 12 | `test.*` |
| `command/AccountLinkDispatcher` | 7 | `link.*` |
| `inbox/InboxRouter` | 6 | `inbox.*` |
| `command/BroadcastCommand` | 5 | `broadcast.*` |
| `JoinDeliveryListener` | 4 | `join.*` |
| `command/MailCommand` | 4 | `mail.*` |
| `mail/MailChatRow` | 3 | `mail.row.*` |
| `broadcast/Broadcaster` | 1 | `broadcast.title` |
| `inbox/InboxChatRow` | 1 | `inbox.row.title-only` |
| `command/NotificationsCommand` | 1 | `notifications.players-only` |
| `PlayerNotificationsPlugin` | 4 | `reload.*`, `inbox.title`, `mail.title` |

`NotificationsCommand` ("Only players have notification preferences.") and `MailCommand` ("Only
players can use mail.") word the same idea differently today. They keep **separate keys** rather
than being unified — the two sentences name different things, and collapsing them is a wording
change smuggled into a mechanical one. `common.error` ("Something went wrong — try again shortly.")
is genuinely shared and does become one key.

## Prose leaves the rule classes

Two plain, unit-tested classes carry **sentences** today:

```java
// MailRecipients.java:52,56
return new Result.InvalidMessage("Mail cannot be blank.");
return new Result.InvalidMessage("Mail must be at most " + MAX_MESSAGE_LENGTH + " characters.");

// BroadcastArguments.java:55,68,74
return new Result.Invalid("Broadcast content cannot be blank.");
return new Result.Invalid(PERM_FLAG + " requires a value.");
return new Result.Invalid("Unrecognised token: " + token);
```

The commands then wrap `invalid.message()` in `Component.text(...)`. Left alone, half the wording in
`/mail send` and `/broadcast` stays hardcoded in a class the container cannot reach.

Both result hierarchies gain **typed cases carrying values instead of sentences**:

```java
// MailRecipients.Result
record UnknownPlayer(@NotNull String name) implements Result {}
record BlankMessage() implements Result {}
record MessageTooLong(int maxLength) implements Result {}

// BroadcastArguments.Result
record BlankContent() implements Result {}
record FlagMissingValue(@NotNull String flag) implements Result {}
record UnrecognisedToken(@NotNull String token) implements Result {}
```

`InvalidMessage` and `Invalid` are removed, not deprecated. Neither is in the `api` module, so no
separately-compiled feature module can be *frozen* against them.

**Corrected during implementation:** the claim that nothing outside `platform:paper-plugin` held a
reference was wrong — `discord-adapter`'s `DiscordMailService` matched on
`MailRecipients.Result.InvalidMessage` and passed its `reason()` straight through as a Discord
rejection. The adapter compiles against the host, so this surfaced as a compile error in
`./gradlew build`, not as a runtime surprise. It now matches the two new cases and words them
locally, beside the unknown-player line it already worded that way — it is not on `messages.yml`,
and its text is Discord-shaped. The lesson generalises: "internal to paper-plugin" is not the same
as "unreferenced", because every feature module compiles against paper-plugin.

The command maps case to key and resolvers. This is a **better test** than the one it replaces —
`MailRecipientsTest` and `BroadcastArgumentsTest` currently assert on prose, so a wording change
fails a rule test for no reason. Afterwards they assert on a type, and the wording is checked once,
in the message-resolution test.

## Two sites deliberately excluded

**`MailNotifier.ARRIVAL_NOTICE` stays hardcoded.** It is a `public static final
RenderableNotification`, and the Discord adapter recognises it by **reference identity** —
`MailNoticeButton.java:39` is `if (notification != MailNotifier.ARRIVAL_NOTICE)`. That check is what
puts the "Read mail" button under the notice, and CLAUDE.md records that identity was chosen over
text comparison precisely so that rewording the notice could not silently drop the button. Making
the notice configurable means rebuilding it on reload, which changes its identity and breaks the
button — silently, since a `!=` that stops matching just skips the decoration.

Configuring it needs a marker that a reload cannot invalidate (a sentinel type, or a `notifKey` on
`RenderableNotification`). That is a design of its own and not worth attaching to a mechanical text
pass. The one-line notice is also the most fixed text in the plugin.

**`InboxRouter.EMPTY_MESSAGE`'s two uses stay one key.** The constant is read both by `openInbox`'s
early return and by the chat fallback's `listInChat`, and CLAUDE.md records that sharing as
load-bearing (an empty inbox opens no dialog at all, because vanilla's `MultiActionDialog` codec
rejects an empty action list). One key, read in both places — splitting it would let an operator
make the two disagree.

`Broadcaster.BROADCAST_TITLE` **is** in scope: nothing compares against it, it is only ever used to
construct a `RenderableNotification`.

## messages.yml

Nested YAML, flattened to dotted keys by the container:

```yaml
prefix: "<gray>[<aqua>Notifications<gray>]<reset> "

common:
  error: "<red>Something went wrong — try again shortly.</red>"

notifications:
  players-only: "<red>Only players have notification preferences.</red>"

mail:
  players-only: "<red>Only players can use mail.</red>"
  sent: "<green>Mail sent to <recipient>.</green>"
  unknown-player: "<red>Player <name> has never played on this server.</red>"
  blank-message: "<red>Mail cannot be blank.</red>"
  message-too-long: "<red>Mail must be at most <max> characters.</red>"
  row:
    entry: "<dark_gray>#<entry> </dark_gray><gray>[<time>] </gray>"
    sender: "<aqua>[<sender>] </aqua>"

broadcast:
  title: "Broadcast"
  no-audience: "<red>No online player matched those permissions.</red>"
  nothing-enabled: "<red>No recipient had broadcasts enabled. Use --bypass to deliver regardless.</red>"
  blank-content: "<red>Broadcast content cannot be blank.</red>"
  flag-missing-value: "<red><flag> requires a value.</red>"
  unrecognised-token: "<red>Unrecognised token: <token></red>"

join:
  unread: "<yellow>You have <count> unread notifications. </yellow><gray>Use /notifications to read them.</gray>"
  unread-mail: "<yellow>You have unread mail. </yellow><gray>Use /mail to read it.</gray>"

inbox:
  empty: "<gray>Your inbox is empty.</gray>"
  already-empty: "<gray>Your inbox is already empty.</gray>"
  gone: "<red>That notification is no longer in your inbox.</red>"
  page-footer: "<gray> — page <page> of <total-pages></gray>"
  usage: "<gray>Use /<command> read <entry> or /<command> delete <entry>.</gray>"
  deleted: "<green>Deleted: <title></green>"
  row:
    title-only: "<entry>. <title>"

link:
  none-available: "<red>There is no account linking available on this server.</red>"
  header: "<green>Accounts you can link:</green>"
  entry: "  <name><gray> — run </gray><aqua>/notifications link <key></aqua>"
  unavailable: "<red><name> linking is not available on this server.</red>"

test:
  sent: "<gray>Test notification sent. Delivery attempted via: </gray>"
  separator: "<gray>, </gray>"
  muted: "<yellow>Your notifications are muted, so it was suppressed. </yellow><gray>Use </gray><white>/notifications unmute</white><gray> to receive notifications again.</gray>"
  silenced: "<yellow>Test notifications are silenced for you, so it was suppressed. </yellow><gray>Use </gray><white>/notifications preferences</white><gray> to choose how test notifications reach you.</gray>"
  no-media: "<yellow>Nothing was sent: you have no delivery methods chosen for test notifications. </yellow><gray>Use </gray><white>/notifications preferences</white><gray> to choose one.</gray>"
  no-sink: "<yellow>Not set up on this server, so it was skipped: <media></yellow>"
```

**The key list above is the design's; the shipped file is larger.** Reading the call sites turned
up cases it under-specified, all resolved in favour of more keys:

- **Pluralisation needs two keys, not a ternary.** `join.unread-one`/`-many` and
  `inbox.cleared-one`/`-many`. Which form to use is a wording decision, and a translator needs both
  forms in the file to change.
- **A listing row needs separate unread and read keys.** The original applied `colorIfAbsent` with
  a per-row colour; `colorIfAbsent` is a no-op once text carries a colour, so a single key an
  operator had coloured would have silently erased the unread distinction.
- **Seven strings the survey missed**, in `InboxRouter` (`cleared`, `header`, `list-first`,
  `no-entry`, `row-hover`), `BroadcastCommand` (`parse-failed`, `sent`) and
  `TestNotificationSender` (`failed`), plus the two screen titles and the two `/notifications
  reload` replies.

Per-key wording is whatever the corresponding literal says today. The strings above are transcribed
from the call sites; any drift is a transcription error to fix against the code, not a wording
decision to relitigate.

**Placeholder names are kebab-case** (`<total-pages>`), matching the config-key convention the repo
already enforces for Configurate records.

## Testing

`paper.localisation.TestMessages` (test sources) loads the **shipped** `messages.yml` off the
classpath into a real `MessageContainer` and hands it out. Every test of a class that now takes a
container uses it, so the existing wording assertions keep working — and keep asserting against what
actually ships, rather than against a fixture that can drift from it.

`MessageKeysTest` walks every `public static final String` on `MessageKeys` by reflection and
asserts that `miniMessageFormattedFor(key)` does not return the key itself, i.e. that every declared
key is present in the shipped file. It asserts the converse too — every key flattened out of
`messages.yml` has a `MessageKeys` constant — so a key added to the YAML and never wired up is
caught as well. Without both directions, `messageFor`'s render-the-key-on-miss behaviour means a
typo reaches a player.

Existing suites that change:

- `MailRecipientsTest` / `BroadcastArgumentsTest` — assert on result **type**, not prose.
- `TestNotificationSenderTest`, `AccountLinkDispatcherTest`, `JoinDeliveryListenerTest`,
  `MailChatRowTest`, `BroadcasterTest` — take a `TestMessages` container.

Nothing here needs Docker or a live server.

## Known limitations

- **The mail arrival notice is not configurable**, for the identity reason above. The most visible
  piece of mail text is therefore the one an operator cannot change. Revisit when
  `RenderableNotification` gains a marker that survives a reload.
- **`MessageContainer.load` mutates a shared map while async threads read it.** Delivery, `/mail`
  and every command branch run off the main thread; a reload calls `clear()` then repopulates. A
  reader racing a reload can observe a missing key and print the key itself. realty has the same
  exposure and has not hit it — a reload is a rare, operator-initiated event and the window is
  microseconds. **Verified during implementation:** `rawMessages` is a `ConcurrentHashMap`
  (`javap -c` on the constructor), so the worst case really is a stale read — one message printing
  as its own key — and not a corrupted map or an infinite loop. Accepted on that basis. If a future
  version of `plugin-infrastructure` changes that field, this entry expires and the wiring should
  load into a fresh container swapped behind a `volatile` field instead.
- **Dialogs are out of scope** — the six preference screens, both inbox dialogs, and
  `ui/PagedDialogs`. Adding them is additive, but `paper.ui` must import nothing from this plugin
  (CLAUDE.md records the rule and the `grep` that checks it), so its three strings have to arrive as
  `Component` parameters rather than as a container.
- **Renderer titles are out of scope** — `MailRenderer`'s "Mail from <sender>" and
  `TestNotificationRenderer`'s body. These render *stored* notifications, so an operator editing them
  changes how old notifications read, which is a different question from changing a command reply.
- **Feature modules are out of scope.** The Discord adapter's ~19 strings are Discord-shaped (embed
  titles, button labels, ephemeral replies), and its module classloader argues for it owning a
  `discord-messages.yml` of its own rather than adding keys to the host's file.
- **No per-player locale.** One file, one language. The container has no locale axis, and adding one
  is a change to `plugin-infrastructure`, not to this plugin.
- **`prefix` applies to command replies only**, not to inbox listing rows, which are already dense.
  An operator wanting it on rows must add `<prefix>` to the row keys themselves.
