# Broadcast command — design

**Date:** 2026-08-21
**Status:** approved

## Goal

Give operators `/broadcast <content> --perm [perm] --perm [perm] [--bypass]` — a
one-shot announcement delivered *immediately* to matching online players through the media each of
them prefers, and **never written to the database**.

Permissions are combined with **OR**: a player receives the broadcast if they hold *any* listed
permission. With no `--perm` flag at all, every online player receives it.

An ordinary broadcast is **mutable and silenceable by the player**: the global mute suppresses it and
a `broadcast` silence (`{none}`) stops it, exactly as for any other push. `--bypass` overrides both,
for the announcement class that is not a subscription — a restart warning, a server-wide alert. That
split is the whole reason the flag exists: without it, either operators can never be ignored or they
can never reach everyone, and both are wrong.

## Why it is not a notification

Everything else in this plugin that reaches a player is a stored `Notification`: enqueued, targeted,
paged in the inbox, pruned. A broadcast is the opposite kind of thing — it is *transient by
definition*. Its audience is "whoever is online and holds the permission right now", which is not a
set that survives being written down: a player who joins ten minutes later was never a recipient, and
storing the broadcast would put it in their inbox as though they were. So a broadcast follows
`MailNotifier`'s precedent — the one existing path that fans a `RenderableNotification` out through
the sink registry without a payload, a `notifKey`, or a `NotificationDisposition`.

It is therefore **not** a new registry axis. It reuses the medium axis (`NotificationSinkRegistry`)
exactly as it stands; no sink, processor, renderer, serializer or migration is added.

## Architecture

Four plain types plus one Brigadier node, all in `platform:paper-plugin`. The split is the one this
repo already uses for command-shaped features: every decision in a class with no Bukkit dependency,
and the command itself reduced to wiring.

**Who the recipients are is deliberately behind an interface** (`BroadcastAudience`), because
delivering to *offline* players is a wanted follow-up whose details are not settled. Everything either
side of that interface — parsing, fan-out, the command — is already written in terms that do not care
whether a recipient is online. See "Room for offline delivery".

### `paper.broadcast.BroadcastArguments` — parsing

```java
public record BroadcastArguments(@NotNull String content, @NotNull List<String> permissions,
                                 boolean bypass)
public static Result parse(@NotNull String raw)

public sealed interface Result {
    record Parsed(@NotNull BroadcastArguments arguments) implements Result {}
    record Invalid(@NotNull String message) implements Result {}
}
```

Brigadier cannot express repeated flags, so `<content>` is a single greedy string argument and the
flags are parsed out of it here. The rule, deliberately the simplest one that is unambiguous to a
reader:

- Split on whitespace. Everything before the **first** flag token (`--perm` or `--bypass`) is
  the content.
- From that token on, the remainder must be a sequence of `--perm <perm>` pairs and bare
  `--bypass` tokens, in any order. Anything else is rejected, naming the offending token.
- `--bypass` takes no value and is idempotent; repeating it is not an error.
- `--perm` as the final token (no value) is rejected.
- A permission value beginning with `--` is rejected — it is a missing value, not a permission node.
- Blank content is rejected. Duplicate permissions are de-duplicated, order preserved.

**Consequence, accepted:** the literal tokens `--perm` and `--bypass` cannot appear in a broadcast's
text. A first-occurrence rule and a scan-from-the-end rule both have that limitation; the
first-occurrence one produces a comprehensible error when a flag is malformed, whereas the end-scan
silently absorbs a typo'd flag into the message body, which is worse for a command whose output every
online player sees.

`content` is parsed with **full MiniMessage** — no per-tag permission gate like `/mail send`'s. The
command is op-only by default and its output is not stored, so the sender-vs-renderer split that
`MailFormatting` exists to solve does not arise. A MiniMessage parse failure (a legacy `§` code, an
unclosed tag) is caught in the command and reported to the sender; unlike `MailRenderer`, there is no
fallback to literal text, because the sender is present and can fix it.

### `paper.broadcast.BroadcastAudience` — who the recipients are

```java
@FunctionalInterface
public interface BroadcastAudience {
    @NotNull List<UUID> resolve(@NotNull List<String> permissions);
}
```

One method, one meaning: given the OR-list of permissions, return the UUIDs to deliver to. Today
there is exactly one implementation, `OnlineBroadcastAudience`, and this interface would be
over-engineering if offline delivery were not an expected follow-up — it is here so that follow-up is
a *new implementation* rather than a rewrite of the command, and so nothing upstream of it has to
learn what "online" means.

`BroadcastCommand` depends on the interface and is constructed with one; `PlayerNotificationsPlugin`
picks the implementation. That is the same shape as every other swap point in this plugin (a sink, a
link provider, a `DiscordAccountProvider`) — the difference is only that this one has no registry,
because a broadcast has exactly one audience at a time and choosing between several is not a decision
anything today can make.

### `paper.broadcast.OnlineBroadcastAudience` — today's implementation

```java
public OnlineBroadcastAudience(@NotNull Server server)
public List<UUID> resolve(@NotNull List<String> permissions)   // BroadcastAudience
```

Maps `server.getOnlinePlayers()` to `BroadcastRecipients.Candidate`s and delegates. Thin by design:
it is the one class in the feature that touches Bukkit, so it is the one class that cannot be unit
tested.

**It must be called on the command thread, not the async task.** Bukkit permission state belongs to
the main thread, so permissions are evaluated eagerly there and only the resulting `List<UUID>`
crosses into the async task — the same rule `MailCommand.send` follows for its `TagResolver`. An
offline-capable implementation will almost certainly need the opposite (a permission lookup that
blocks), which is noted as a constraint below rather than designed for now.

### `paper.broadcast.BroadcastRecipients` — the OR match

```java
public record Candidate(@NotNull UUID uuid, @NotNull Predicate<String> hasPermission)
public static List<UUID> select(@NotNull Collection<Candidate> candidates,
                                @NotNull List<String> permissions)
```

Pure, and the `Predicate` seam is what makes it unit-testable without a live server — the same device
`MailRecipients`' resolver and `TestNotificationRenderer.usingServerNames()` use. An empty
`permissions` list matches every candidate; otherwise a candidate matches when the predicate accepts
at least one listed permission (OR).

It is deliberately **separate from `OnlineBroadcastAudience`** rather than folded into it: the OR rule
is the part an offline implementation would reuse unchanged, since where a `Predicate<String>` comes
from is exactly the thing that differs between the two.

### `paper.broadcast.Broadcaster` — fan-out

```java
public Broadcaster(@NotNull NotificationSinkRegistry sinks,
                   @NotNull NotificationPreferences preferences,
                   @NotNull Logger logger)
public int broadcast(@NotNull Component content, @NotNull Collection<UUID> recipients,
                     boolean bypass)
```

Returns the number of recipients the broadcast was actually attempted for. For each recipient: skip
when `preferences.isMuted` **and not `bypass`**; resolve `preferences.preferredMedia(uuid,
BROADCAST_DATA_TYPE)`; drop `NotificationPreferences.SILENCED_MEDIUM`; deliver
`new RenderableNotification(BROADCAST_TITLE, content)` to each medium's sink; skip an unregistered
medium with a `fine` log; catch and log a throwing sink so one broken sink cannot suppress the
others. This is `MailNotifier.notifyArrival`'s shape, per recipient rather than for one.

**When `bypass` is set and the resolved set is empty** — the player silenced `broadcast`, or their
only preferred medium is `none` — delivery falls back to the single medium `FALLBACK_MEDIUM`
(`"chat"`). `ChatSink` is registered unconditionally by the host, so this is the one medium that
cannot be missing; falling back to `default-media` instead was rejected because a server that
misconfigures it would make bypass silently deliver nothing, which is precisely the failure the flag
exists to rule out. A bypassed recipient who *does* have preferred media is delivered to **those**,
not to the fallback — bypass overrides the suppression, not the player's choice of medium.

Without `bypass`, an empty set means the recipient is skipped and **not counted** — the same
"suppressed, not delivered" meaning a silence has everywhere else, minus the retention, since nothing
is stored.

`Broadcaster` takes `Collection<UUID>` and never asks whether a recipient is online — it is already
offline-neutral, and `RenderableNotification` holds no `Player` and no `Audience` precisely so that a
sink like `DiscordDmSink` can reach an absent player. Nothing in this class would change if the
audience started returning offline UUIDs; the `chat` fallback is the one exception, called out below.

`BROADCAST_TITLE` is `Component.text("Broadcast")` — non-chat sinks need a title (a Discord embed has
one whether or not we supply it), and `RenderableNotification` requires one anyway.

`BROADCAST_DATA_TYPE` is `"broadcast"`, declared on `Broadcaster`. Because a player must be able to
silence broadcasts, the type has to appear in `/notifications preferences`, and those dialogs
enumerate `dataTypeRegistry().dataTypes()` — so `PlayerNotificationsPlugin` registers a **payload
mapping only**:

```java
service.dataTypeRegistry().registerPayloadMapping(
        Broadcaster.BROADCAST_DATA_TYPE, BroadcastPayload.class);
```

`paper.broadcast.BroadcastPayload` is a one-component record (`String message`) that exists solely to
be that mapping's key — no serializer, no renderer, no processor, and nothing ever enqueues one. A
mapping is what the dialogs read; registering the other three would be machinery for a stored
broadcast, which this design deliberately does not have. If a `broadcast`-typed notification were
somehow enqueued it would take `NotificationDelivery`'s "no processor, no renderer" branch: logged
and retained, which is the correct outcome for a row that should not exist.

`categories.yml` gains a `broadcast` category (label "Broadcasts") so the type is grouped under a
readable name rather than under "Other".

Blocking JDBC, and `DiscordDmSink` refuses the main thread, so `broadcast` is called from
`runTaskAsynchronously` — as every other command branch in this tree is.

### `paper.command.BroadcastCommand`

```java
public static final String PERMISSION = "playernotifications.command.broadcast"; // default: op
public static LiteralCommandNode<CommandSourceStack> create(@NotNull Plugin plugin,
                                                            @NotNull Broadcaster broadcaster,
                                                            @NotNull BroadcastAudience audience)
```

`/broadcast <content>`, `<content>` a greedy string. **Console is a valid sender** — a broadcast acts
on the server, not on the sender's own inbox, the same reasoning that makes `/mail send` and
`/notifications reload` non-player commands. On the command thread it parses, deserializes the
MiniMessage and calls `audience.resolve(permissions)`; then off-thread it calls
`Broadcaster.broadcast` and replies
"Broadcast sent to N player(s)." — or "No online player matched those permissions." when nothing
matched, and "No recipient had broadcasts enabled." when candidates matched but every one was muted
or silenced. An operator needs to be able to tell those two apart: the second is fixed by
`--bypass`, the first is not.

`--bypass` carries **no permission of its own**. The command is already op-only, and a second gate on
a flag of an op-only command distinguishes nothing.

There is no alias and no subcommand tree. Registered in
`PlayerNotificationsPlugin.registerCommands()` beside `/mail`, with the `Broadcaster` built there
from the already-constructed `sinkRegistry` and `preferences`.

## Room for offline delivery

Delivering broadcasts to offline players is wanted later and **not designed here**. What this design
commits to is that it will be an addition, not a rewrite. Three of the four pieces are already
audience-neutral:

| Piece | Ready for offline recipients? |
|---|---|
| `BroadcastArguments` | Yes — it parses text and knows nothing about players. An unknown flag is *rejected* today, so adding one later is a pure addition that cannot silently change an existing command's meaning. |
| `BroadcastRecipients.select` | Yes — the OR rule is over a `Predicate<String>`, whatever supplies it. |
| `Broadcaster` | Yes — takes `Collection<UUID>`, never asks about presence, and delivers through sinks that already reach absent players. |
| `OnlineBroadcastAudience` | No, and that is the point: it is the single class an offline design replaces or joins. |

The questions a later design has to answer — recorded so nobody has to rediscover them, **not**
answered now:

- **How permissions are checked for an offline player.** Bukkit cannot; a permission plugin's own API
  (LuckPerms and friends) can, at the cost of a hard dependency this plugin does not currently have.
  That is the decision that gates the whole feature.
- **How the candidate set is bounded.** "Every player who has ever joined" is unbounded and grows
  forever, so an offline audience needs a bound — recently seen, explicitly targeted, or paged — and
  a per-candidate permission lookup across it is not free.
- **Whether the resolve stays on the main thread.** It cannot, if the lookup blocks. Today's rule
  ("resolve on the command thread") is a property of `OnlineBroadcastAudience`, not of the interface,
  which is why the constraint is documented on the implementation.
- **What `chat` means for an absent player.** `ChatSink` reports `DELIVERED` for an offline player —
  the same trap `JoinDeliveryListener` re-checks `isOnline()` to avoid — so the `--bypass` fallback to
  `chat` would silently claim success for a recipient who saw nothing. An offline design must either
  pick a different fallback or make the fallback presence-aware.
- **Whether "never stored" survives.** It may not: a broadcast that should reach a player who is
  offline *and* has no out-of-game medium has to wait somewhere, and waiting means storage. This is
  the one decision that would genuinely change the shape of the feature — and it is also the one the
  design is already positioned for, because `BroadcastPayload` exists as a registered mapping. Giving
  it a serializer and a renderer turns a broadcast into a storable notification without inventing a
  new type or migrating a preference row.

## Files

Create:
- `platform/paper-plugin/src/main/java/.../paper/broadcast/BroadcastArguments.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/BroadcastAudience.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/OnlineBroadcastAudience.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/BroadcastRecipients.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/Broadcaster.java`
- `platform/paper-plugin/src/main/java/.../paper/command/BroadcastCommand.java`
- `platform/paper-plugin/src/test/java/.../paper/broadcast/BroadcastArgumentsTest.java`
- `platform/paper-plugin/src/test/java/.../paper/broadcast/BroadcastRecipientsTest.java`
- `platform/paper-plugin/src/test/java/.../paper/broadcast/BroadcasterTest.java`

Modify:
- `platform/paper-plugin/src/main/java/.../paper/broadcast/BroadcastPayload.java` (create)
- `PlayerNotificationsPlugin` — register the payload mapping in `onEnable` **before**
  `registerCommands()` (the dialogs enumerate the registry at construction, the same ordering the
  `test` type already relies on); construct the `Broadcaster` and register the node in
  `registerCommands`, wiring it with `new OnlineBroadcastAudience(getServer())`.
- `platform/paper-plugin/src/main/resources/categories.yml` — a `broadcast` category.
- `platform/paper-plugin/src/main/resources/paper-plugin.yml` — the one permission, `default: op`.
- `CLAUDE.md` — a "Broadcast" section and a line in "Player commands".

No change to `api`, `core`, any schema, `settings.yml` or `categories.yml`.

## Error handling

| Case | Result |
|---|---|
| Blank content | red chat reply, nothing sent |
| `--perm` with no value / a `--`-prefixed value | red reply naming the flag |
| Junk token in the flag region | red reply naming the token |
| MiniMessage parse failure | red reply with the parser's message |
| No candidate matched | reply "No online player matched those permissions." |
| Recipient muted, no `--bypass` | silently skipped, counted out of the reported total |
| Recipient muted, `--bypass` | delivered anyway |
| Recipient silenced `broadcast`, no `--bypass` | skipped, counted out of the total |
| Recipient silenced `broadcast`, `--bypass` | delivered to `chat` |
| Candidates matched but every one suppressed | reply "No recipient had broadcasts enabled." |
| Medium with no registered sink | `fine` log, skipped |
| Sink throws | `warning` log, other sinks still run |

## Testing

`BroadcastArgumentsTest` — every row of the parsing rules above, including the de-duplication and the
"content containing `--perm` is rejected" case. `BroadcastRecipientsTest` — empty permission
list matches all, OR across two permissions, no match, a candidate holding neither. `BroadcasterTest`
— recording fake sink and fake `NotificationPreferences` (the fixtures `MailNotifierTest` already
establishes): fan-out to two media, silenced type delivers nothing and is not counted, muted recipient
skipped and not counted, both of those delivered to `chat` under `bypass`, a muted recipient with
real preferred media receiving on **those** media under `bypass` rather than on the fallback,
unregistered medium skipped, throwing sink does not suppress the other, returned count.

`BroadcastCommand` and `OnlineBroadcastAudience`, like every other Bukkit-touching class in this
tree, are **not** covered by automated tests — they need a live server. `OnlineBroadcastAudience` is
kept to a map-and-delegate for that reason: everything it would be worth testing lives in
`BroadcastRecipients`. Manual checklist in the plan.

## Known limitations

- **A missed broadcast is unrecoverable.** Nothing is stored, so a player who muted or silenced
  broadcasts has no inbox copy to find afterwards — unlike every other suppressed push. That is the
  accepted cost of a transient audience, and `--bypass` is the escape hatch for anything an operator
  cannot afford a player to miss. A message that must survive being missed should be `/mail send`.
- **`--bypass` is all-or-nothing.** It overrides the mute and the silence together; there is no flag
  that overrides only one. Two flags for a distinction no operator has asked to make would be worse
  than the coarse one.
- **A bypassed broadcast can reach a player on a medium they never chose** (`chat`, via the
  fallback). Deliberate — a bypass that could still deliver nowhere would not be a bypass — and it is
  bounded: it happens only when the player has no usable preferred medium for `broadcast` at all.
- **`BroadcastPayload` is a mapping key, not a payload.** It has no serializer or renderer, so it is
  the one registered data type in the tree that cannot round-trip through storage. Registering it is
  what makes broadcasts silenceable; if broadcasts ever become storable, the serializer and renderer
  are the additions, not a rewrite.
- **Offline players are never recipients**, because Bukkit cannot answer a permission check for one.
  This is the limitation most expected to be lifted: `BroadcastAudience` exists so that lifting it is
  a new implementation rather than a rewrite, and the open questions are listed under "Room for
  offline delivery". Until then the behaviour matches what "broadcast" means everywhere else on a
  server.
- **Delivery is not tracked.** As everywhere else, the reply names the recipients *attempted*, not
  reached; per-medium delivery tracking remains deferred (see `CLAUDE.md`, "Rendering & delivery
  media").
- **No rate limiting and no confirmation.** The command is op-only; a mis-typed broadcast is sent.
- **The literal token `--perm` cannot appear in a broadcast's text**, as described above.
