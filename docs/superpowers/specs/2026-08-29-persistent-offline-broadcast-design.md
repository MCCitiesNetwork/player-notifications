# Persistent and offline broadcasts — design

**Date:** 2026-08-29
**Status:** proposed
**Extends** `2026-08-21-broadcast-command-design.md`, whose "Room for offline delivery" section posed
the questions this document answers.

## Goal

Let an operator reach players who are **not online**, and let a broadcast **survive being missed**, by
adding four flags to the existing `/broadcast` command:

```
/broadcast <content> [--perm <node>]… [--chain and|or] [--persistent] [--offline] [--limit <n>] [--bypass]
```

- `--chain and|or` — how multiple `--perm` nodes combine. Default `or`, today's behaviour.
- `--persistent` — enqueue a real `Notification` so the message lands in the inbox and is pushed on the
  recipient's next join. Without it, a broadcast is transient exactly as it is today.
- `--offline` — widen the audience from "online now" to "everyone the permission backend says holds the
  permission". **Requires `--persistent` and at least one `--perm`.**
- `--limit <n>` — refuse to send if the resolved audience is larger than `n`. Absent means **unlimited**,
  on both paths; `n` must be at least 1, so `--limit 0` is an error rather than a way to say unlimited.

`--persistent` decides *whether the message is stored*; `--offline` decides *who it goes to*. They are
independent: an operator who wants an announcement a currently-online player can find again later wants
the first without the second.

## The two prerequisites on `--offline`

**`--offline` requires `--persistent`.** An offline player has no chat. `ChatSink` reports `DELIVERED`
for an absent player — the trap `JoinDeliveryListener` re-checks `isOnline()` to avoid — so a transient
fan-out to offline recipients would report a success nobody saw, for everyone without a linked Discord
account. Rather than make the transient path presence-aware, the combination is **rejected naming the
reason**: it has no honest implementation, and silently upgrading it to persistent would store rows the
operator did not ask for.

**`--offline` requires at least one `--perm`.** With no permission node the offline audience is "every
player the permission backend has ever heard of", which is unbounded, grows forever, and would write a
target row per player for a single mistyped command. There is deliberately no recency window in this
design (see "Rejected: a last-seen window"), so the permission filter is the audience's only *shape*
constraint — `--limit` is its only *size* constraint, and the two do different jobs.

## Architecture

```
BroadcastCommand
  ├── BroadcastArguments.parse                  (+ chain, persistent, offline)
  ├── BroadcastAudience.resolve(perms, chain)   ← now always called off the command thread
  │     ├── OnlineBroadcastAudience             (marshals to the main thread internally)
  │     └── OfflineBroadcastAudience            (new — thin wrapper over PermissionLookup)
  │           └── PermissionLookup              (new — LuckPermsPermissionLookup behind a guard)
  ├── BroadcastRecipients.select(cands, perms, chain)   (+ AND, online path only)
  ├── the --limit check                         (new — one condition, after resolve)
  ├── Broadcaster.broadcast / .suppressed       (+ suppressed)
  └── PersistentBroadcaster                     (new — enqueue, then push the online recipients)
```

### `BroadcastArguments` — the flag grammar

```java
public record BroadcastArguments(@NotNull String content, @NotNull List<String> permissions,
                                 boolean bypass, @NotNull Chain chain, boolean persistent,
                                 boolean offline, @Nullable Integer limit)

public enum Chain { AND, OR }
```

The parse rule is unchanged in shape: everything before the **first** flag token is the content, and
from there the remainder must be recognised flags. The flag-token set grows to `--perm`, `--bypass`,
`--chain`, `--persistent`, `--offline`, `--limit`. Because an unrecognised token is *rejected* today
rather than absorbed, adding these cannot change the meaning of any command that parsed successfully
before — it can only turn text that was a hard error into a flag, and the accepted consequence ("these
literal tokens cannot appear in a broadcast's text") simply covers four more tokens.

New rejection cases on the sealed `Result`:

| Case | Meaning |
|---|---|
| `UnknownChainValue(String value)` | `--chain` given something other than `and`/`or`, case-insensitively |
| `InvalidLimitValue(String value)` | `--limit` given a non-integer or a negative value |
| `OfflineRequiresPersistent()` | `--offline` without `--persistent` |
| `OfflineRequiresPermission()` | `--offline` with no `--perm` |

`--chain` and `--limit` take a value and follow `--perm`'s existing rules: a missing value or a
`--`-prefixed value is `FlagMissingValue`. `--persistent` and `--offline` are bare and idempotent, like
`--bypass`. Repeating `--chain` or `--limit` takes the last occurrence rather than erroring — consistent
with `--bypass`'s idempotence and not worth a case of its own.

`limit` is `@Nullable`, and **absent means unlimited** — on both paths, so an existing `/broadcast` keeps
its exact behaviour and the flag is purely opt-in. **`--limit 0` is rejected**, along with any negative
value: zero reads naturally as "send to nobody", which is not a thing anyone wants a broadcast to do, and
overloading it as "unlimited" would make the most dangerous setting the one that looks like the safest.
There is therefore no magic value at all — the flag is present and bounds the send, or it is absent.

`chain` defaults to `OR`, so every command written against today's grammar keeps its exact meaning. On
the online path an empty `permissions` list matches every candidate under both chains, so `--chain` is
inert without `--perm` rather than an error; on the offline path `--perm` is mandatory, so the question
does not arise.

### `BroadcastRecipients` — the AND match

```java
public static List<UUID> select(@NotNull Collection<Candidate> candidates,
                                @NotNull List<String> permissions,
                                @NotNull BroadcastArguments.Chain chain)
```

`OR` is today's rule: a candidate matches when its predicate accepts at least one listed node. `AND`
matches when it accepts every listed node. An empty `permissions` list matches everything under both.
The two-argument form is kept as a static delegate passing `OR`, so existing callers and tests stand
unchanged.

This is the **online** path's matcher, and it stays pure and separate from the audience that feeds it.
The offline path does not use it — its chaining happens inside `PermissionLookup`, because the backend
answers set-valued queries rather than per-player predicates (see below). The two are not shared, and
that is a deliberate consequence of the offline design rather than an oversight; `Chain` is the type
they have in common.

### `PermissionLookup` — who holds a permission, as a set

```java
public interface PermissionLookup {
    @NotNull String providerName();
    @NotNull Set<UUID> matching(@NotNull List<String> permissions,
                                @NotNull BroadcastArguments.Chain chain);
}
```

**Set-valued, not per-player.** The obvious shape — `Optional<Predicate<String>> forOfflinePlayer(UUID)`
— was designed first and rejected: it forces one permission-backend load per candidate, which is N
storage round trips for a set the backend can compute in a handful of queries. Drawing the seam around
"who matches" rather than "does this player match" is what makes the whole feature affordable.

It **blocks**, and the interface says so — it is called only from the async task.

### `LuckPermsPermissionLookup` — the only implementation

Bukkit cannot answer a permission check for an absent player at all, so this is LuckPerms-specific. The
naive `UserManager#loadUser(uuid)` per candidate is what the set-valued seam exists to avoid. Instead,
per permission node, three stages:

1. **Which groups grant it.** Walk `GroupManager#getLoadedGroups()` and test
   `group.getCachedData().getPermissionData(QueryOptions.defaultContextualOptions()).checkPermission(node)`.
   This resolves group→group inheritance, so a group inheriting from a granting group is caught. Tens of
   groups, all held in memory: **zero I/O**. `GroupManager#loadAllGroups()` is awaited once first so the
   loaded set is complete.
2. **Who is in those groups.** For each granting group, one
   `UserManager#searchAll(NodeMatcher.key(InheritanceNode.builder(group).build()))`. Group membership
   *is* a stored node, which is why this works where a direct search for the permission does not.
3. **Who holds it directly.** One `UserManager#searchAll(NodeMatcher.key(node))`, unioned in — this
   catches a permission assigned to a user rather than through a group. The returned `Node`s are
   inspected: `getValue() == false` subtracts an explicit negation, and `hasExpired()` drops a lapsed
   temporary node.

Total per node: **two to four storage queries**, against N per-user loads for the naive form. `Chain.OR`
unions the per-node sets; `Chain.AND` intersects them.

**The `default` group is special-cased.** If `default` grants the node, membership is implicit and stored
on nobody, so stage 2 finds no one. In that case the answer is everyone the backend knows —
`UserManager#getUniqueUsers()` — which combined with the `--perm` requirement is the one way `--offline`
can still address the whole population. It is reachable only by an operator naming a permission their
default group grants, which is a deliberate act rather than an accident of omission.

**Verified against `net.luckperms:api:5.4`** with `javap`: `getUniqueUsers()`, `searchAll(NodeMatcher)`,
`getLoadedGroups()`, `loadAllGroups()`, `NodeMatcher.key`, `InheritanceNode.builder(String)` and
`CachedPermissionData.checkPermission` all exist with the signatures used above. That `searchAll` covers
*stored* nodes only — the premise stage 1 exists to work around — follows from how LuckPerms persists a
user (its own assigned nodes, group membership included; inherited permissions are not stored on the
user) and should be confirmed against the javadoc before the code is written.

**The binding is class-loader-isolated the way `EssentialsMailBinding` is**, and for the same reason —
this is a `required: false` dependency, and naming its types from a class that always loads would take
the host plugin down on a server without it:

- `LuckPermsBinding.tryCreate(Plugin)` returns `Optional<PermissionLookup>`, checks
  `isPluginEnabled("LuckPerms")` **first**, and names no LuckPerms type in any signature.
- `LuckPermsPermissionLookup` is the only class mentioning `net.luckperms`.
- A `LinkageError` from constructing it is caught and logged at `SEVERE`, and the lookup is absent — the
  same failure mode the converter module's `LinkageError` catch exists for, reached by the same route.
- `paper-plugin.yml` gains a third `dependencies: server:` entry, `LuckPerms`, `required: false`,
  `join-classpath: true`. **This widens the exposure documented under "Discord adapter" in
  `CLAUDE.md`** — an *unloaded* LuckPerms would poison class loading the way an unloaded DiscordSRV
  does. Called out in Known limitations rather than solved here.

Verification that the guard is real, mirroring the converter's:
`javap -c -p …/LuckPermsBinding.class | grep -i luckperms` should match string literals only.

### `OfflineBroadcastAudience`

```java
public OfflineBroadcastAudience(@NotNull Server server, @NotNull PermissionLookup lookup)
public List<UUID> resolve(@NotNull List<String> permissions, @NotNull Chain chain)
```

Delegates to `PermissionLookup#matching` and drops anyone currently online — the online audience's job,
and it keeps the two sets disjoint. That is the whole class: with the lookup answering set-valued
queries there is nothing left for the audience to decide, which is why it needs no tests of its own.

**It blocks and must not touch the main thread** — every stage of the lookup is a permission-backend
query. That inverts today's rule, which is why the threading moves.

### The `--limit` size guard

There is no class for this. With no per-path default to resolve and no magic value, the rule is one
condition — `limit != null && recipients.size() > limit` — and the only part with a decision in it is
the `>= 1` validation, which lives in `BroadcastArguments.parse` and is covered by
`BroadcastArgumentsTest`. An earlier draft had a `BroadcastLimit` class carrying a per-path default;
once absent means unlimited everywhere, it held nothing worth naming.

The check runs in `BroadcastCommand`, inside the async task, **after `audience.resolve` and before
anything is enqueued or delivered**. On exceeding, the reply is `broadcast.limit-exceeded` naming both
the resolved count and the limit that stopped it, and **nothing is sent**.

Naming the real count is what makes this a confirmation gesture rather than merely a guard: the operator
reads "would reach 431 players, limit is 200" and re-runs with `--limit 431` — a deliberate act
acknowledging the size, and one that stays accurate only for as long as the audience does. That is why
the reply quotes the count instead of telling the operator to raise the limit to some round number.

Two properties worth stating because they are load-bearing:

- **The count is the resolved audience, before mute and silence filtering.** Those recipients still get
  a stored row on the persistent path, so they are part of the blast radius even though they will not be
  interrupted. Counting only the deliverable set would understate exactly what the guard exists to
  measure.
- **The lookup still runs.** The count cannot be known without resolving the audience, so `--limit` bounds
  the *writes and the delivery*, not the queries. That is the right trade — the queries are a handful, and
  the rows are what cannot be undone.

### Rejected: a last-seen window

An earlier draft bounded the offline audience with `--since <days>` over `OfflinePlayer#getLastSeen()`,
backed by a `broadcast-offline-window-days` setting. It was dropped, for a reason worth recording
because it is not obvious:

**The window would not have bounded the expensive part.** `Server#getOfflinePlayers()` says in its own
javadoc that it "can be expensive as it loads all the player data files from the disk", and you cannot
filter on `getLastSeen()` without having already loaded every player's data. So `--since 7` and
`--since 3650` cost the same sweep — one gzipped-NBT read per player who has *ever* joined, a cost that
grows with server age and never shrinks.

The two ways out were both worse than dropping it. Keeping our own `PlayerLastSeen` table written on
join and quit would make the query one indexed `SELECT`, but costs a `V4` migration, a listener, and a
ramp-up period during which the answer is quietly incomplete. Reading `getLastSeen()` for only the
*permission-matched* set is affordable, but the LuckPerms stages make that set small already, so the
window would bound something that is no longer the problem.

Recency is therefore **not** a filter this design offers. If it is wanted later, the per-matched-set read
is the cheap version and the table is the complete one; neither is blocked by anything here.

### Threading: `resolve` moves off the command thread

Today `BroadcastCommand` calls `audience.resolve` on the command thread because Bukkit permission state
belongs there. The offline audience needs the opposite. Rather than branch in the command on which
audience it holds, **`resolve` is now always called inside the async task**, and `OnlineBroadcastAudience`
marshals back internally:

```java
// OnlineBroadcastAudience.resolve
if (this.server.isPrimaryThread()) {
    return selectNow(permissions, chain);
}
return this.server.getScheduler().callSyncMethod(this.plugin, () -> selectNow(permissions, chain)).get();
```

The `isPrimaryThread()` branch is not defensive tidiness — without it, a call from the main thread would
deadlock waiting on a task the main thread has to run. `OnlineBroadcastAudience` gains a `Plugin`
constructor parameter and stays a map-and-delegate otherwise.

This keeps one code path in the command and puts each audience's threading requirement inside the class
that has it, which is where the original design said the constraint belonged.

### `PersistentBroadcaster` — the storing path

```java
public PersistentBroadcaster(@NotNull NotificationService service,
                             @NotNull Predicate<UUID> isOnline,
                             @NotNull Consumer<UUID> push,
                             @NotNull Broadcaster broadcaster,
                             @NotNull Logger logger)
public Result broadcast(@NotNull Component content, @NotNull String rawContent,
                        @NotNull Collection<UUID> recipients, boolean bypass)
public record Result(int stored, int pushed, int bypassed) {}
```

**The seams are a `Predicate` and a `Consumer`, not a `Server` and a `NotificationDelivery`** — both of
those need a live server to construct, and neither contributes a decision this class makes. Production
wires `push` as `uuid -> notificationDelivery.deliver(uuid)`, reading the field at call time so
`/notifications reload` replacing that object still reaches the current one. This is a change from the
approved design, made because the original signature put the whole class beyond unit testing.

**The seams are a `Predicate` and a `Consumer`, not a `Server` and a `NotificationDelivery`.** Both of
those need a live server to construct, and neither contributes a decision this class makes. Production
wires `push` as `uuid -> notificationDelivery.deliver(uuid)`, reading the field at call time so that
`/notifications reload` replacing that object still reaches the current one — the property the
`Supplier` in the original design was there to give. **This is a change from the approved design**, made
because the original signature put the whole class beyond unit testing.

Three steps, in order:

1. **Enqueue once.** One `TypedNotification<BroadcastPayload>` with
   `new NotificationTarget(List.copyOf(recipients))` — a single notification row and one target row per
   recipient, which is the shape the schema exists for. `notifKey` is `"broadcast-" + UUID.randomUUID()`,
   `notifScheduledTime` is now, `notifExpiryTime` is `null`, `notifPriority` is `0`. The payload carries
   the **raw MiniMessage string**, not the rendered `Component`, so the stored form is the source text and
   the renderer is the only thing deciding how it reads.
2. **Push the online recipients.** For each recipient with a live `Player`, call
   `NotificationDelivery.deliver(uuid)`. This is the existing push path: it honours the mute gate and the
   per-`dataType` preferences, fans out through the registered sinks, and — the reason it is used rather
   than `Broadcaster` — **stamps `seenTime`**, so a recipient who read it live is not pushed it again on
   their next join. Offline recipients are not pushed; `JoinDeliveryListener` reaches them.
3. **Bypass the suppressed, if asked.** When `bypass` is set, `Broadcaster.suppressed(recipients)` returns
   the recipients step 2 would have delivered nothing to — muted, or silenced down to no usable medium —
   and `Broadcaster.broadcast(content, those, true)` delivers to them transiently with the existing `chat`
   fallback. They keep their **unread** inbox copy, which is correct: they were reached out of band, and
   the stored record is what makes the message recoverable.

Steps 2 and 3 cannot overlap: step 3's set is by construction the set step 2 skipped.

`Broadcaster` gains one method for step 3, reusing the rule it already applies internally so the two
cannot drift:

```java
public List<UUID> suppressed(@NotNull Collection<UUID> recipients)
```

A recipient is suppressed when `preferences.isMuted(uuid)`, or when
`preferredMedia(uuid, BROADCAST_DATA_TYPE)` minus `SILENCED_MEDIUM` is empty.

### `BroadcastPayload` becomes a real payload

The record is unchanged — `BroadcastPayload(String message)`, holding the raw MiniMessage. What changes
is its registration in `PlayerNotificationsPlugin#onEnable`:

```java
service.registerJsonRenderable(Broadcaster.BROADCAST_DATA_TYPE, BroadcastPayload.class,
        new BroadcastRenderer(messages));
```

`registerJsonRenderable` replaces the bare `registerPayloadMapping`, giving the type a reflective JSON
serializer and a renderer in one call. **No processor is registered**, deliberately — an explicit
processor wins dispatch and would bypass preferences and sinks entirely, which is right for `mail` and
wrong here. A persistent broadcast should behave like every other renderable notification.

`paper.broadcast.BroadcastRenderer` renders the payload with MiniMessage under the guard `MailRenderer`
uses: MiniMessage *throws* on a legacy `§` code, so a `RuntimeException` falls back to literal text. It
ignores `target`; a broadcast reads the same to everyone. Its title is
`messages.messageFor(MessageKeys.BROADCAST_TITLE)`, matching the live path.

**This diverges from the rule that renderer titles stay hardcoded** (`MailRenderer`,
`TestNotificationRenderer`), which exists because a renderer renders *stored* notifications and rewording
changes how old ones read. Here the same title is also the live broadcast's title, and having the two
disagree after an operator edits one key would be worse than the rot. Recorded in Known limitations.

### `BroadcastCommand` wiring

Parse and MiniMessage-deserialize on the command thread as today; everything else moves into the async
task, because `resolve` now blocks. The persistent and transient paths differ only in which object does
the fan-out and which reply keys are used.

The async task's order is fixed and matters: `resolve` → **`--limit` check** → empty-audience
check → fan-out. The limit is tested before anything is written, so a refused command is
indistinguishable from one never run, apart from the queries it took to count.

```java
public static LiteralCommandNode<CommandSourceStack> create(
        @NotNull MessageContainer messages, @NotNull Plugin plugin,
        @NotNull Broadcaster broadcaster, @NotNull PersistentBroadcaster persistentBroadcaster,
        @NotNull BroadcastAudience onlineAudience, @Nullable BroadcastAudience offlineAudience)
```

`--offline` selects `offlineAudience`; its absence selects `onlineAudience`. When no `PermissionLookup`
is available (LuckPerms absent), `offlineAudience` is `null` and `--offline` is answered with
`broadcast.offline-unavailable` — the same shape as `/notifications link discord` on a server with no
Discord module: the node exists, and the capability explains its own absence.

The command's permission is unchanged (`playernotifications.command.broadcast`, `default: op`). None of
the three new flags carries a permission of its own, for the reason `--bypass` does not: a second gate on
a flag of an op-only command distinguishes nothing.

## Messages

New keys in `messages.yml` and `MessageKeys` (`MessageKeysTest` walks both directions, so each needs
both):

| Key | Used for |
|---|---|
| `broadcast.unknown-chain` | `--chain` given something other than `and`/`or`, naming the value |
| `broadcast.invalid-limit` | `--limit` given a non-integer or a negative value, naming the value |
| `broadcast.limit-exceeded` | resolved audience larger than the limit, naming both counts |
| `broadcast.offline-requires-persistent` | `--offline` without `--persistent` |
| `broadcast.offline-requires-permission` | `--offline` with no `--perm` |
| `broadcast.offline-unavailable` | `--offline` on a server with no permission lookup |
| `broadcast.stored-one` / `broadcast.stored-many` | persistent send, naming stored and pushed counts |
| `broadcast.bypassed` | appended when `--bypass` reached suppressed recipients, naming the count |

Singular and plural are separate keys, per the standing rule that text varying per call gets a key per
case rather than a ternary. Everything the sender typed (`chain`, `limit`, `token`) goes through
`value()`, never `markup()`.

`broadcast.limit-exceeded` is the one reply here that has to stay actionable through rewording: it must
keep naming the resolved count, because that number is what the operator types back as `--limit`. The
shipped wording is "Would reach `<count>` players, above the limit of `<limit>`. Re-run with `--limit
<count>` to send anyway."

## Configuration

**No new config keys.** The last-seen window that would have needed one was dropped; see "Rejected: a
last-seen window". `settings.yml`, `categories.yml` and `type-names.yml` are untouched.

## Error handling

| Case | Result |
|---|---|
| `--chain` with a value that is not `and`/`or` | red reply naming the value, nothing sent |
| `--chain`/`--limit` with no value or a `--`-prefixed value | existing `FlagMissingValue` reply |
| `--limit` non-integer or negative | red reply naming the value, nothing sent |
| Resolved audience > effective limit | red reply naming both counts, **nothing enqueued and nothing delivered** |
| `--offline` without `--persistent` | red reply explaining the requirement |
| `--offline` with no `--perm` | red reply explaining the requirement |
| `--offline` with no permission lookup available | red reply, nothing sent |
| LuckPerms absent at startup | one `INFO` log line; `--offline` unavailable, the rest works |
| `LinkageError` constructing the binding | `SEVERE` log, lookup absent, host plugin unaffected |
| A lookup stage throws or times out | `WARNING` log, red reply, nothing sent — a partial audience would silently under-deliver |
| Enqueue throws | `WARNING` log, red reply, nothing pushed |
| `deliver(uuid)` throws for one recipient | `WARNING` log, remaining recipients still pushed |
| No candidate matched | existing `broadcast.no-audience` reply |
| Persistent, every recipient suppressed, no `--bypass` | stored reply with `pushed: 0` — **not** `nothing-enabled` |

Two rows there are new rules rather than restatements. `broadcast.nothing-enabled` means "this message
reached nobody and is gone", which is false of a persistent broadcast. And a failed lookup stage fails
the whole command rather than proceeding with what it got: an audience that is silently a subset is the
worst outcome for a command whose entire purpose is reaching people who are not present to notice.

## Testing

Unit, no Docker and no server:

- `BroadcastArgumentsTest` — extended: `--chain and`, `--chain or`, case-insensitivity, an unknown chain
  value, `--chain` as the final token, `--persistent` alone, `--offline` alone rejected, `--offline
  --persistent` without `--perm` rejected, `--offline --persistent --perm x` accepted, `--limit 50`,
  `--limit` non-integer, `--limit 0` rejected, `--limit -1` rejected, `--limit` as the final token, an
  absent `--limit` leaving the field null, flags in any order, the default chain being `OR` with no
  `--chain`, and content containing each new literal token rejected.
- `BroadcastRecipientsTest` — extended: AND across two nodes, AND with one node held and one not, AND with
  an empty permission list matching all, and the existing OR cases unchanged through the delegate.
- `BroadcasterTest` — extended: `suppressed` returns a muted recipient, returns a silenced one, excludes
  one with usable media, and preserves order.
- `PersistentBroadcasterTest` — new, against a fake `NotificationService`, `NotificationDelivery` supplier,
  `Broadcaster` and `Server`: one notification enqueued with every recipient in one target group; the
  payload holding the raw string not the rendered one; only online recipients pushed; `bypass` delivering
  to exactly `Broadcaster.suppressed`'s set; a throwing `deliver` not stopping the remaining recipients;
  the returned counts.
- `BroadcastRendererTest` — new: a MiniMessage body rendered, a legacy `§` body falling back to literal
  text, the title coming from the container.

Against a real MariaDB (`:core:test`, Docker):

- `PersistentBroadcastTest` — enqueue a `broadcast` payload for two players; assert both target rows exist
  with `seenTime` null; assert it appears in `inbox(player, 1, 10)` for both; assert `deliver` reaches a
  recording sink and stamps `seenTime` for one without touching the other's. This is the regression test
  for the central claim that a persistent broadcast is an ordinary renderable notification.

Not covered by automated tests, needing a live server and a real LuckPerms install —
`LuckPermsPermissionLookup`, `LuckPermsBinding`, `OfflineBroadcastAudience` and `BroadcastCommand`. A
manual checklist is in the plan's final task, and those classes are kept thin for that reason: every rule
they apply lives in `BroadcastRecipients`, `BroadcastArguments` or `PersistentBroadcaster`. The one piece
with real logic that cannot be unit tested is the three-stage lookup itself, which is the price of it
being a query against someone else's storage.

## Files

Create:
- `platform/paper-plugin/src/main/java/.../paper/broadcast/PermissionLookup.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/LuckPermsBinding.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/LuckPermsPermissionLookup.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/OfflineBroadcastAudience.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/PersistentBroadcaster.java`
- `platform/paper-plugin/src/main/java/.../paper/broadcast/BroadcastRenderer.java`
- `platform/paper-plugin/src/test/java/.../paper/broadcast/PersistentBroadcasterTest.java`
- `platform/paper-plugin/src/test/java/.../paper/broadcast/BroadcastRendererTest.java`
- `core/src/test/java/.../core/PersistentBroadcastTest.java`

Modify:
- `broadcast/BroadcastArguments.java` — the three flags and three new `Result` cases
- `broadcast/BroadcastRecipients.java` — the `Chain` parameter
- `broadcast/BroadcastAudience.java` — the `Chain` parameter
- `broadcast/OnlineBroadcastAudience.java` — internal main-thread marshalling, `Plugin` parameter
- `broadcast/Broadcaster.java` — `suppressed`
- `broadcast/BroadcastPayload.java` — javadoc, which currently states it is never enqueued
- `command/BroadcastCommand.java` — the two new collaborators and the reply selection
- `PlayerNotificationsPlugin` — `registerJsonRenderable` in place of `registerPayloadMapping`, the binding
  attempt, the two audiences, and `PersistentBroadcaster`
- `paper-plugin.yml` — the `LuckPerms` dependency entry
- `messages.yml` + `MessageKeys` — the seven new keys
- `platform/paper-plugin/build.gradle.kts` — `compileOnly("net.luckperms:api:5.4")`
- `CLAUDE.md` — Broadcast, Player commands, Testing gotchas

No schema change, no migration and no config change: a persistent broadcast is an ordinary
`Notification` row.

## Known limitations

- **A third source of the unloaded-dependency class-loading hazard.** `LuckPerms` joins DiscordSRV and
  Essentials as a `join-classpath: true` server dependency, so an *unloaded* LuckPerms in `plugins/` can
  poison class loading exactly as described under "Discord adapter" in `CLAUDE.md`. The fix is the same —
  remove the jar rather than leave it unloaded — and the two code fixes rejected there are rejected here
  for the same reasons.
- **Only LuckPerms.** A server on another permission plugin gets no `--offline`. Making `PermissionLookup`
  a registry was considered and rejected as speculative: there is one implementation, and a second is a
  registration-shaped change if it ever arrives.
- **The offline audience's *shape* is bounded only by the permission**, since `--since` was dropped. A
  node held by a large group addresses that whole group however long ago its members last played;
  `--limit` bounds how many that may be, but not who. Recency is discussed under "Rejected: a last-seen
  window".
- **`--limit` is a stop, not a preview.** It tells the operator the count and refuses; it cannot show
  *who*. Re-running with `--limit <count>` sends to whoever matches at that moment, which may no longer
  be the same set the refused run measured. A `--dry-run` that lists names would be the honest version and
  is a pure addition, deliberately not built now.
- **`--limit` is opt-in, so the default is still unbounded.** Absent means unlimited on both paths, which
  keeps existing commands working and keeps the flag free of magic values — but it also means the guard
  protects only an operator who remembers to ask for it. Making it mandatory with `--offline` was
  considered and left out: it would be the second required flag on that path, and `--perm` already forces
  the audience to be named deliberately.
- **Context-conditional permissions are answered in the default context.** The lookup uses
  `QueryOptions.defaultContextualOptions()`, so a permission granted only in one world or under a custom
  context is treated as granted. There is no per-player context to evaluate against for an absent player,
  so this is inherent rather than a shortcut.
- **A player with no stored LuckPerms data is never matched.** They cannot hold a non-default permission,
  so this is consistent — except through the `default`-group special case, where the answer falls back to
  `getUniqueUsers()` and a player the backend has no row for is still absent from it.
- **The online and offline paths match by different code.** `BroadcastRecipients` chains per-player
  predicates; `PermissionLookup` chains set-valued queries. They can in principle disagree — most
  plausibly on context-conditional nodes, which Bukkit evaluates for a live player and the lookup does
  not. Sharing one matcher is not possible while one side answers "who" and the other "whether".
- **A persistent broadcast never expires.** `notifExpiryTime` is `null`, matching mail, so the rows live
  until dismissed. An announcement is more perishable than correspondence, so `--expires <days>` is a
  likely follow-up; the field is already nullable, so it is a pure addition.
- **An offline recipient with a linked Discord account waits until their next join.** Step 2 pushes only
  online recipients, because `deliver` stamps `seenTime` and `ChatSink` would report a false `DELIVERED`
  for an absent player. Reaching absent players immediately on out-of-game media requires per-medium
  delivery tracking, which remains deferred.
- **`--bypass` does not override the mute for the *stored* copy**, only for the transient step-3 delivery.
  The mute gate sits above the whole delivery loop in `NotificationDelivery.deliver`, and no
  per-notification flag can reach it. The stored copy is unaffected either way — a mute has never stopped
  inbox arrival.
- **A bypassed recipient can be told twice.** They receive the step-3 transient delivery now and, being
  still unread, the ordinary push on their next join. Both are true statements about a message they have
  not dismissed — the same trade `JoinDeliveryListener`'s mail line already accepts.
- **The broadcast title is now configurable and renders stored notifications**, so rewording
  `broadcast.title` changes how already-stored broadcasts read. Deliberate — the alternative is a live
  broadcast and its stored copy carrying different titles.
- **`--chain` does not compose beyond one operator.** `a AND (b OR c)` is not expressible. A single
  operator across a flat list covers what has been asked for; expression parsing is a different feature.
- **No confirmation and no rate limit**, unchanged from today and now with more consequence: a mistyped
  `--persistent --offline` writes a row per recipient, and the only remedy is a manual `DELETE` or every
  player dismissing it.
