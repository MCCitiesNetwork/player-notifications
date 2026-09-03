# `/notifications send` — design

**Goal:** send one operator-declared notification to one named player, from game or console.

## Why

`/broadcast` selects its audience by *permission*; `/mail send` is the only per-player command and is
mail — stored, never pushed, and titled "Mail from X". There is today no way to send an arbitrary
notification to one player. The workaround (a throwaway permission node plus `/broadcast --perm`) is a
hack, and the machinery for everything else already exists.

## Command

```
/notifications send <player> <type> <content> [--transient]
```

- `<player>` — a name. Online by name first, else `getOfflinePlayer(name)` accepted only when
  `hasPlayedBefore()`, exactly as `MailCommand.resolveRecipient` does and for the same reason
  (`getOfflinePlayer(String)` fabricates a UUID for any string). Suggestions are online player names.
- `<type>` — a key declared in `notification-types.yml`, resolved through `CustomNotificationTypes`
  and suggested from `keys()`. An undeclared key is refused **on the command thread**, before anything
  is queried, as `/broadcast --type` does.
- `<content>` — greedy MiniMessage, with the same no-per-tag-gate rule as `/broadcast`: this command is
  op-only and the sender can fix a parse failure themselves.
- `--transient` — parsed out of the greedy string, not a Brigadier node, because Brigadier cannot put an
  optional literal after a greedy argument. `SendArguments` applies `BroadcastArguments`' rule verbatim:
  content is everything before the **first** `--` token, and every remaining token must be a recognised
  flag or the command is rejected naming it. Cost, accepted and identical to `/broadcast`'s: the literal
  token `--transient` cannot appear in the content.

**Persistent by default.** The target is one named player who may well be offline, so the useful
default is the one that survives being missed: a real `Notification` row, readable in `/notifications`
and pushed on their next join. `--transient` opts out for the fire-and-forget case (a nudge to a player
who is standing right there). That inverts `/broadcast`'s default, because the audiences differ —
`/broadcast`'s "whoever is online and holds the node right now" does not survive being written down, and
a named player does.

**No `--bypass`, no `--limit`, no `--offline`.** One recipient makes `--limit` meaningless, `--offline`
is a *selection* concern this command has no selection to do, and `--bypass` is deferred until asked
for: overriding one player's mute is a heavier decision than overriding a broadcast audience's, and
adding it later is additive exactly as it was for `/broadcast`.

**Mute and silence both apply**, through the existing paths — the gate at the top of
`NotificationDelivery.deliver` for the persistent push, and `Broadcaster`'s own checks for the transient
send. A muted or silenced recipient still gets the stored copy on the persistent path and reads it when
they choose to; the sender is told nothing was pushed.

## Permission

`playernotifications.command.send`, **`default: op`** — it writes into someone else's inbox, so it sits
with `/broadcast` rather than with the `default: true` player-facing subcommands. It is an *additional*
`requires` on the `send` literal, so the root's `playernotifications.command.preferences` still gates it
too, the shape `test` already uses.

**Any `CommandSender`**, console included: like `send` on `/mail`, it acts on another player's inbox
rather than the sender's own, so there is nothing player-only about it.

## Types and files

| Type | Role |
|---|---|
| `paper.send.SendArguments` (new) | `parse(String) : Result` — `Parsed(content, transientSend)` / `BlankContent` / `UnrecognisedToken(token)`. Holds every syntax decision, names no Bukkit type, unit tested. |
| `paper.command.SendCommand` (new) | The Brigadier node. Wiring only: parse, resolve the type, parse MiniMessage, then one async task that resolves the recipient and fans out. |
| `paper.broadcast.Broadcaster` | Reused unchanged for `--transient`, via the existing typed `broadcast(title, content, dataType, recipients, bypass)` overload with `bypass = false`. |
| `paper.broadcast.PersistentBroadcaster` | Reused unchanged for the default path, via the existing typed `broadcast(title, content, dataType, payload, recipients, bypass)` overload. |
| `paper.customtype.CustomNotificationPayload` | The stored payload, `(typeKey, rawContent)` — the same record `/broadcast --type` stores, so a sent notification renders through `CustomTypeRenderer` with no new registration. |
| `paper.localisation.MessageKeys` + `messages.yml` | The new `send.*` keys below. |
| `paper-plugin.yml` | Declares `playernotifications.command.send`, `default: op`. |
| `PlayerNotificationsPlugin.registerCommands()` | Passes the two broadcasters and `CustomNotificationTypes` into `NotificationsCommand.create`; it already holds all three for `/broadcast`. |

**Nothing is registered anywhere new.** This is a second *client* onto the machinery `/broadcast --type`
already drives, the same relationship the Discord slash commands have to the host APIs — which is why it
adds no payload class, renderer, sink, category or schema.

## Messages

All under `send.`, following the styling vocabulary and the prefix rule (every one of these is a
one-line reply to a command, so every one carries `<prefix>`):

| Key | Placeholders | When |
|---|---|---|
| `send.blank-content` | — | the content is blank |
| `send.unrecognised-token` | `<token>` | a token after the content is not a known flag |
| `send.unknown-type` | `<type>` | no such declared type |
| `send.parse-failed` | `<error>` | the content is not valid MiniMessage |
| `send.unknown-player` | `<name>` | no such player |
| `send.stored` | `<player>` | the persistent path stored it |
| `send.push-failed` | `<player>` | the persistent push threw; stored and unread regardless |
| `send.sent` | `<player>` | the transient path delivered to at least one medium |
| `send.nothing-enabled` | `<player>` | nothing was attempted — muted, or silenced to nothing |

`<player>`, `<name>`, `<type>`, `<token>` and `<error>` all go through `value()`, being what the sender
typed or an exception message. `send.stored` and `send.nothing-enabled` are separate keys rather than one
worded around the difference, per the key-per-case rule — and on the persistent path a suppressed
recipient still gets `send.stored`, because the notification genuinely is in their inbox.

## Error handling

- On the persistent path, `PersistentBroadcaster.Result.failed() == 1` sends `send.push-failed` **in
  addition to** `send.stored`: the notification is stored and will be pushed on the recipient's next
  join, so this is a warning, not a failure.
- An enqueue failure propagates out of `PersistentBroadcaster` (deliberately, as for `/broadcast`); the
  command catches `RuntimeException` around the whole fan-out and reports `send.push-failed`, since a
  sender told nothing would assume it worked.
- The transient path reports `send.nothing-enabled` when `Broadcaster.broadcast` attempts zero — the
  recipient is muted, or silenced down to no usable medium.
- Recipient resolution and the fan-out both run **async**: `hasPlayedBefore()` reads userdata, the
  enqueue is blocking JDBC, and `DiscordDmSink` refuses the main thread outright.

## Testing

- `SendArgumentsTest` — the parse table: bare content, `--transient` present, repeated `--transient`
  (idempotent, matching `--bypass`), blank content, an unrecognised `--foo`, content containing a lone
  `-` mid-word, and content whose interior spacing must survive.
- No new `core` test: the storage and delivery paths are the ones `PersistentBroadcasterTest` and
  `BroadcasterTest` already cover, and this command adds no branch to either.
- `MessageKeysTest` covers the new keys in both directions by construction.
- **`SendCommand` itself needs a live server** — Brigadier registration, `hasPlayedBefore()`, the
  scheduler. Manual checklist in the plan's final task.

## Known limitations

- **No `--bypass`.** A muted recipient is not pushed now; on the persistent path they read it when they
  next look. Deferred rather than rejected — add it as an additive flag if operators ask.
- **One recipient per command.** Several named players would need a different argument shape (a list, or
  repeated `--player`), and `/broadcast --perm` already covers "a group".
- **Operator-declared types only.** A module's type owns a payload record this command cannot construct;
  sending under it would store a wrong-shaped payload its own renderer would reject. Untyped sends under
  the `broadcast` data type were also left out: `/broadcast --limit 1` is close enough, and a per-player
  message with no type has nowhere sensible to take a title from.
- **The literal `--transient` cannot appear in the content**, inherited from `/broadcast`'s parsing rule.
- **`send.stored` does not mean the player saw it.** `NotificationDelivery.deliver` returns `void`, so
  the same honesty `/broadcast` and `/notifications test` observe applies: the reply says stored, never
  delivered.
