# First-party mail — design

**Date:** 2026-08-10
**Status:** implemented — see *As built* below. Not yet verified on a live server.

Implemented across `e7d5676` (filtered inbox queries), `fb1f515` (payload + renderer), `53ef552`
(sender + the never-deliver rule), `351fb55` (`/mail` tree + arrival notice), `47691c1` (Essentials
adapter deleted), `6f8ff80` (`CLAUDE.md`), `e4ba13c` (inbox Preferences button removed).

## Goal

Retire `platform:essentials-adapter` and replace it with a first-party, player-to-player mail
system: `/mail` to read, `/mail send <player> <message>` to write. Mail must be **non-disruptive**
— the content of a mail is never pushed at a player through any medium. Receiving mail produces
one short *"You have new mail!"* line; the mail itself waits in the inbox until the player asks
for it.

## The shape of the change

The Essentials adapter and first-party mail are not the same kind of thing, and swapping one for the
other is not a like-for-like replacement:

| | Essentials adapter | First-party mail |
|---|---|---|
| Axis | a delivery **medium** (`NotificationSink`, key `essentials-mail`) | a **payload type** (`dataType` `mail`) |
| Owns | no data type, no payload, no renderer | payload record + renderer, no medium |
| Answers | "*where* do my notifications reach me" | "*what* is this notification" |

Mail is therefore registered along the payload axis and inherits storage, targeting, paging,
seen/unread tracking and pruning from the machinery that already exists. **No new table, no new
migration, no new registry.**

The alternative considered and rejected: a standalone `Mail` table with its own service, queries and
commands. It buys mail-specific fields (threading, reply-to, attachments) that nothing has asked
for, at the cost of duplicating paging, seen-tracking and pruning. Chosen against on YAGNI. Nothing
below makes that revisit harder, because the payload is a JSON column.

## Mail is stored, but never delivered

This is the central decision and everything else follows from it.

A mail is persisted as an ordinary notification. It is then **excluded from the delivery path
entirely** — no sink, no medium, no fan-out — by registering an explicit processor for its payload
class that always retains:

```java
service.dataTypeRegistry().registerProcessor(MailPayload.class,
        (payload, target) -> NotificationDisposition.RETAIN);
```

This uses the dispatch-precedence rule already in `NotificationDelivery`: an explicitly registered
`NotificationProcessor` wins over the renderer path and bypasses preferences and sinks. `CLAUDE.md`
records that precedence as a *quirk*, because it lets a processor ignore a player's mute. For mail
it is exactly the required behaviour — there is nothing to mute, because nothing is ever sent.

`RETAIN` (rather than `MARK_SEEN`) means the delivery loop never stamps `seenTime`. Mail becomes
seen only when the player actually opens it in the inbox, so the unread count and the bold-unread
row in `/mail` mean what they say.

Three things this buys by not being built:

- **No "notice vs content" rendering split.** An earlier draft gave `NotificationRenderer` a
  `renderNotice` default so the push path could send a summary while the inbox showed the full text.
  Unnecessary once nothing is pushed: `MailRenderer` has one form, used only on read.
- **No fourth target state.** If a notice were delivered through a sink, the MARK_SEEN-wins fan-out
  would mark the mail seen the moment the notice landed — the player has been told mail exists but
  has read none of it, and the unread count would drop to zero. Avoiding that would have meant a
  `notifiedTime` column, a `MARK_NOTIFIED` disposition and a V3 migration. None are needed.
- **No per-player detail preference.** There is no full-versus-summary choice to store, expose in
  the preference dialogs, or migrate.

### Consequence: mail preferences route the notice, not the mail

A processor bypasses `NotificationPreferences`, so a preference row for `dataType` `mail` cannot
affect the mail itself — nothing about the mail is ever sent. Those rows are not wasted, though:
they are what the **arrival notice** below is routed by. "Where do I want to be told that mail
arrived" is a real question with a real answer per player, and it is exactly the question the
existing per-`dataType` preference rows already ask.

So `categories.yml` does gain a `mail` category, and its Chat / Discord DM checkboxes mean
something concrete:

```yaml
categories:
  mail:
    label: "Mail"
    description: "Where you are told that new mail has arrived. The mail itself is always read with /mail."
    types:
      - mail
```

Muting `mail` therefore means "don't tell me when mail arrives" — the mail still lands in the
inbox, unread, waiting.

## The arrival notice

Receiving mail produces exactly one notice, verbatim:

> **You have new mail!**
> Use `/mail` to read it.

No sender, no count, no preview. The point of the notice is to send the player to `/mail`, and any
detail it carries is detail that has leaked out of the inbox — which is the thing this design exists
to prevent.

The second line exists because `RenderableNotification` is a title *and* a body, so the notice
cannot be a single string, and an empty body risks a JDA embed builder rejecting the message
outright. It is an affordance — how to read the mail — not a detail about the mail, and
`MailNotifierTest` asserts that no sender name, count or message text appears in either component.

The notice is **not** a notification: it is never enqueued and never stored. A stored notice would
sit in the inbox as a second row announcing the first. But it *is* delivered through the ordinary
sink machinery, so it reaches chat, Discord DM, or both, exactly as the player prefers.

`paper.mail.MailNotifier` does this — the whole of it:

```java
public final class MailNotifier {
    public MailNotifier(@NotNull NotificationSinkRegistry sinks,
                        @NotNull NotificationPreferences preferences,
                        @NotNull Logger logger);

    /** Delivers the "You have new mail!" notice to each medium the player prefers for mail. */
    public void notifyArrival(@NotNull UUID recipient);
}
```

It resolves `preferences.preferredMedia(recipient, MailPayload.DATA_TYPE)`, drops
`NotificationPreferences.MUTED_MEDIUM`, and delivers a fixed
`RenderableNotification` to each medium's sink, catching a throwing sink and logging it so one
broken sink cannot suppress the others — deliberately the same handling `RenderingProcessor` gives
a sink, for the same reason.

It does **not** reuse `RenderingProcessor` itself: that processor exists to render a stored
notification's payload and to report a `NotificationDisposition` back to the delivery loop, and the
notice has neither a payload nor a disposition. Sharing the class would mean inventing a fake
notification to carry a constant string.

The notice fires in two places:

- **On send.** `MailCommand` calls `notifyArrival` after the enqueue succeeds. Chat reaches an
  online recipient; Discord DM reaches them whether they are online or not, which is much of the
  reason for making the notice routable at all.
- **On join, if unread mail exists.** `JoinDeliveryListener` gains a mail line driven by the
  filtered `unreadCount(playerId, "mail")` from §4, sending nothing when the count is zero. This is
  a reminder rather than the notice — a player who read the Discord DM last week and never logged in
  should still be told on arrival that mail is waiting. It sits alongside the listener's existing
  unread-count line, **outside** the `deliver-on-join` gate and its delay, as that line already does.

A player who was offline when mail was sent and has Discord DM selected therefore hears about it
twice: once by DM at send time, once on join. That is a reminder about something genuinely still
unread, not a duplicate, and suppressing it would mean tracking notice delivery per player — a
column and a state machine for a line of text.

## Architecture

### 1. The payload (`api`)

```java
package io.github.md5sha256.playernotifications.api.mail;

public record MailPayload(@NotNull UUID sender,
                          @NotNull String senderName,
                          @NotNull String message) {

    /** The registry data type mail is stored under. */
    public static final String DATA_TYPE = "mail";

    /** Longest message accepted by /mail send. */
    public static final int MAX_MESSAGE_LENGTH = 256;
}
```

It lives in `api` because a feature module may want to send mail; it is a record rather than a
`String` for the reason `CLAUDE.md` already documents (a `String` payload is JSON-encoded on write
and arrives at its handler still quoted).

`senderName` is **stored, not looked up at render time.** A renderer runs on read, potentially long
after the send, and `Bukkit.getOfflinePlayer(uuid).getName()` is a blocking lookup that returns
`null` for a player the server has never seen. Storing the name the sender had at send time is both
cheaper and more truthful — the mail says who wrote it, not who owns that UUID today. The `sender`
UUID is kept alongside for a future reply command and for admin tooling.

### 2. The renderer (`paper`)

`paper.mail.MailRenderer implements NotificationRenderer<MailPayload>`:

```java
@Override
public @NotNull RenderableNotification render(@NotNull MailPayload payload, @NotNull UUID target);
```

- title: `Mail from <senderName>`
- body: the message text, as a plain `Component`

Note the two-argument signature — `NotificationRenderer.render` already takes the recipient so a
payload can personalise per player. `MailRenderer` ignores `target`; every mail reads the same to
everyone it targets.

**Only the inbox calls this.** `InboxEntryRenderer` resolves payload class → serializer → renderer
on read; the delivery path never reaches it, because the processor above short-circuits first. A
renderer is registered all the same, since without one the inbox would show
`InboxEntryRenderer`'s "unrenderable payload" placeholder instead of the mail.

Both components are built with `Component.text(...)` — never MiniMessage or a legacy serializer,
because the message is player-supplied and must not be interpretable as formatting.

### 3. Sending (`paper`)

`paper.mail.MailSender` — the unit-testable half:

```java
public final class MailSender {
    public MailSender(@NotNull NotificationService service);

    /** Enqueues one mail. Returns the notification key. */
    public @NotNull String send(@NotNull UUID sender, @NotNull String senderName,
                                @NotNull UUID recipient, @NotNull String message);
}
```

It builds a `TypedNotification<MailPayload>` with:

- `notifKey` — `"mail-" + UUID.randomUUID()`. Keys are the primary key and must not collide; a
  per-send random UUID is the only scheme that holds under concurrent sends.
- `notifScheduledTime` — `Instant.now()`.
- `notifExpiryTime` — **`null`**. Every other in-tree notification is transient; mail is
  correspondence, and correspondence that evaporates on a prune tick is a bug report waiting to
  happen. The cost is an unbounded inbox — see *Known limitations*.
- `notifTarget` — a single-element `NotificationTarget`.
- `notifPriority` — `0`, as every other in-tree notification.

then calls `enqueueNotification(notification, false)`.

Validation lives in the command, not here: `send` is the programmatic entry point, and a module
calling it with a 400-character message should get the mail rather than an exception thrown across a
module boundary.

### 4. The inbox filter (`api`, `core`)

`/mail` must show mail and only mail, and the "page 2 of 5" count has to agree with that. Filtering
a page client-side cannot do it, so the filter goes into the query.

`NotificationService` gains data-type-filtered overloads; the existing unfiltered forms become
`default` methods delegating with `null`, so no existing caller changes:

```java
@NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize, @Nullable String dataType);
int unreadCount(@NotNull UUID playerId, @Nullable String dataType);
void markAllSeen(@NotNull UUID playerId, @Nullable String dataType);
void dismissSeen(@NotNull UUID playerId, @Nullable String dataType);
```

`null` means "no filter" rather than an `Optional` or a parallel method set: each is one query with
one extra predicate, which MyBatis already expresses as an `<if>`.

The affected mapper methods (`selectInboxPage`, `countInbox`, `countUnread` on `NotificationMapper`;
`markAllSeen` and the seen-key lookup on `NotificationTargetMapper`) take a nullable
`@Param("dataType")` and gain, inside a `<script>`:

```xml
<if test="dataType != null">AND n.notifPayloadType = #{dataType}</if>
```

`markAllSeen` and `dismissSeen` operate on `NotificationTarget`, which has no `notifPayloadType`
column, so the filter is an `EXISTS` against `Notification` rather than a join. For `dismissSeen`
that subquery reads `Notification` while `trg_delete_targetless_notification` writes it, which
MariaDB refuses; the filtered delete is therefore issued as select-then-delete-by-key, matching the
shape `pruneOrphanedTargets` already uses for the same reason.

The clamping contract on `inbox` is unchanged: `page` into `1..totalPages`, `pageSize` into `1..20`,
applied against the *filtered* total.

### 5. The commands (`paper`)

`InboxRouter` already owns the page cursor, the last-listed index map, both dialogs and the async
marshalling. It gains two constructor arguments — a `@Nullable String dataTypeFilter` and a
`Component title` — and passes the filter to every service call it makes. `/mail` is then a **second
`InboxRouter` instance** with the filter set to `"mail"`.

Two instances rather than one shared router with a per-call filter: the cursor and last-listed maps
are per-screen state, and a player who runs `/mail list 2` then `/notifications read 1` must not
have the second resolve against the first's page. Separate instances give that for free. Both are
dropped on quit by `InboxQuitListener`, which takes a list of routers.

`paper.command.MailCommand` builds the Brigadier tree, mirroring `NotificationsCommand`:

| Command | Behaviour |
|---|---|
| `/mail` | opens the mail inbox dialog (filtered `InboxDialog`, titled "Mail") |
| `/mail send <player> <message>` | sends mail; `<message>` is a greedy string |
| `/mail list [page]` | chat fallback list |
| `/mail read <n>` | reads entry `n` of the last listed page; marks it seen |
| `/mail dismiss <n>` | dismisses entry `n` |
| `/mail clear` | `markAllSeen` + `dismissSeen`, both filtered to `mail` |

Permissions, declared in `paper-plugin.yml`:

- `playernotifications.command.mail` (`default: true`) — the root, covering read/list/dismiss/clear.
- `playernotifications.command.mail.send` (`default: true`) — an additional requirement on `send`,
  so a server can make mail read-only for a rank without hiding the inbox. Brigadier `requires`
  nest, so revoking the root still hides `send`.

All subcommands are player-only and dispatch off the main thread, since each does blocking JDBC.
`send`'s recipient resolves on the async thread:

1. An online player by name — the common case.
2. Otherwise `Bukkit.getOfflinePlayer(name)`, accepted only when `hasPlayedBefore()` is true.
3. Otherwise the sender is told the player is unknown and nothing is enqueued.

Rejecting never-joined names matters because `getOfflinePlayer(String)` fabricates a UUID for any
string: without the check, a typo silently mails a player who cannot exist. Tab completion suggests
online players only.

Message validation, in the command: non-blank, and at most `MailPayload.MAX_MESSAGE_LENGTH`
characters — over-length is **rejected with a message, not truncated**, since silently dropping the
end of someone's sentence is worse than making them shorten it. Sending to oneself is allowed; it
costs nothing and is the easiest way to test the feature on a single-player test server.

### 6. Retiring the Essentials adapter

Deleted outright — the project is in prototyping and has no deployed data to preserve:

- `platform/essentials-adapter/` (the whole directory).
- its `include(...)` line in `settings.gradle.kts`.
- its `featureModules(...)` line in `platform/paper-plugin/build.gradle.kts`.
- the soft `Essentials` entry in `paper-plugin.yml`'s `dependencies: server:` block, which exists
  only for the adapter and carries the same `join-classpath: true` classloader exposure `CLAUDE.md`
  documents for DiscordSRV.

Stored `essentials-mail` preference rows are left in place and become inert: the host renders an
unregistered medium by its raw key and `RenderingProcessor` skips it. That is already the documented
behaviour for a removed module and needs no cleanup pass.

## Error handling

| Case | Behaviour |
|---|---|
| Recipient never joined the server | `send` rejected, sender told the name is unknown |
| Message blank or over 256 chars | rejected with the limit named; nothing enqueued |
| Recipient offline at send time | mail is enqueued; the notice still goes to any offline-capable medium they prefer (Discord DM), and the join line covers the rest |
| A sink throws or fails while delivering the notice | caught and logged; other sinks still receive it, the mail is unaffected, and the join line is the backstop |
| `read`/`dismiss` with a stale or out-of-range index | "no such entry, run /mail list again" |
| `read`/`dismiss` before any `list` | the same reply — an absent cursor is not distinguished from a bad index |
| Payload fails to decode or the renderer throws | `InboxEntryRenderer`'s existing placeholder, naming the data type |
| Any blocking call throws on the async thread | logged at `WARNING`, sender told the command failed |

## Testing

Unit-testable, and tested:

- `MailSender` — key prefix and uniqueness, null expiry, single-element target, payload contents,
  `overwriteAllowed = false`. Against a fake `NotificationService`, as `TestNotificationSender`'s
  tests already do.
- `MailNotifier` — against a fake sink registry and fake preferences: a player preferring
  `chat + discord-dm` gets the notice at both sinks; a muted player gets it at none; a preferred
  medium with no registered sink is skipped without throwing; a sink that throws does not stop the
  other sink receiving it; and the delivered `RenderableNotification` carries the verbatim notice
  text and nothing derived from the mail.
- `MailRenderer` — title names the sender, body is the message, and section signs or `&` codes in a
  player-supplied message render literally.
- Recipient resolution and message validation — extracted into `MailRecipients`, taking a
  `Function<String, UUID>` resolver so the rules are testable without a server, the same device
  `TestNotificationRenderer.usingServerNames()` uses.

Against a real MariaDB (`:core:test`, Testcontainers):

- The filtered inbox: enqueue mail and non-mail for one player; assert `inbox(p, 1, 10, "mail")`
  returns only mail, that `totalEntries`/`unreadCount` reflect the filter, that the unfiltered call
  still returns both, and that paging respects the filter.
- Filtered `markAllSeen` — a non-mail notification stays unread.
- Filtered `dismissSeen` — a seen non-mail notification survives; a seen mail is gone and its
  notification row is disposed of by the trigger.
- **Mail is never delivered:** enqueue mail plus a renderable non-mail notification for one player,
  run `NotificationDelivery.deliver`, and assert against a recording sink that the sink received the
  non-mail notification and *nothing* for the mail, and that the mail's `seenTime` is still null
  afterwards — i.e. it is still unread and still in the inbox. This is the design's central claim
  and the one regression that would be easiest to reintroduce by "tidying up" the RETAIN processor.

Needs a live server, so a manual checklist instead (this repo's standing exception):

- `/mail send`, the dialog, the chat fallback, tab completion, the permission split, the send-time
  notice, and the join notice.

**That checklist has not been run.** It is the 14-item list in Task 4 of
`docs/superpowers/plans/2026-08-10-first-party-mail.md`, driven by
`./gradlew :platform:paper-plugin:runServer`. Nothing in this feature's player-facing surface —
`/mail` and its subcommands, either dialog, tab completion, the permission gates, or a notice
actually landing in chat or a Discord DM — has been observed working. Everything underneath it is
covered by the automated tests above.

## As built

The design above is what was built. Six places where the implementation departed from it, or where
a decision was settled after it was written:

1. **`FilteredInboxTest` lives in package `core.database`, not `core`.** `AbstractDatabaseTest`,
   which it must extend, is package-private, and a package-private class cannot be extended from
   another package. It sits beside its sibling `InboxReadTest`.
2. **`JoinDeliveryListener` gained a new package-private `mailReminder(UUID)` seam.** The plan
   assumed the existing unread-count line was already testable through such a seam; it was not —
   `announceUnread` needs a live `Player` and was untested. `mailReminder` follows the pattern
   `deliver(UUID)` had already established, so the reminder's gate is verified even though the
   handler around it is not.
3. **The notice body was pinned to "Use `/mail` to read it."** after the fact — see *The arrival
   notice*. An empty body was the alternative and was rejected as a JDA embed risk.
4. **The inbox lost its *Preferences* button** (`e4ba13c`), on both the notification and mail
   screens, along with the `openPreferences` consumer threaded from `PlayerNotificationsPlugin`
   through `InboxRouter` to reach it. Not part of this design, but it is shared inbox UI that this
   work put a second caller on, so it is recorded here: the inbox is for reading, and a jump into
   the preference screens left the player with no way back to what they were reading.
5. **Deleting the adapter also removed the EssentialsX `github(…)` entry** from `runServer`'s
   `downloadPlugins` block in `platform/paper-plugin/build.gradle.kts`, which the design did not
   name. It existed only to feed the test server a plugin the adapter needed. The block itself
   stays — its DiscordSRV entry is still required by the Discord adapter.
6. **A pre-existing `CLAUDE.md` drift was corrected on the way past:** the infrastructure coordinate
   is `com.minecraftcitiesnetwork:plugin-infrastructure`, not `net.democracrycraft:…`. The Maven
   host remains `maven.democracycraft.net`, so group id and host genuinely disagree upstream.

Test baseline after the work: **328 tests, 0 failures** — `core` 100, `api` 30,
`platform:paper-plugin` 76, `platform:discord-adapter` 122.

### Open question, deliberately not settled here

**Whether mail should be a feature module rather than host code.** It is a self-contained feature
and `platform/` exists for exactly that, so the instinct is right. The obstacle is that a feature
module cannot register a command: `registerCommands()` runs on `LifecycleEvents.COMMANDS`, which
fires before `startModules()`, so the Brigadier tree is frozen before any module exists. That is why
the Discord adapter's `/discordlink` was retired in favour of a host-owned `/notifications link`
node reading a live `AccountLinkRegistry`, and `/mail` would hit the same wall.

The shape that would work is the same bridge: a `platform/mail-adapter` owning the renderer, the
RETAIN processor, `MailSender`, `MailNotifier` and `MailRecipients`, with the host declaring the
`/mail` node and mail `InboxRouter` and resolving a mail service from a registry at execution time.
Worth knowing before committing to it: the payoff is smaller than for the Essentials and Discord
adapters, because those own a *medium* and nothing user-facing, whereas mail needs a command tree,
permissions and an inbox screen that must stay in the host either way.

## Known limitations

Deliberately not solved:

- **The notice is not tracked, so it can repeat.** Mail sent to an offline player with Discord DM
  selected produces a DM at send time and a reminder line on their next join. Suppressing the second
  would mean recording per-player notice delivery — a column and a state machine for one line of
  text — and the reminder is about mail that genuinely is still unread.
- **A failed notice is not retried.** If the only preferred medium is Discord and the DM fails, the
  player learns about the mail on their next join and not before. `MailNotifier` returns no result
  and `MailCommand` does not inspect one: the mail is safely stored either way, which is the whole
  point of not delivering content through a medium.
- **The notice is per mail, not per batch.** Ten mails in a minute produce ten notices. A debounce
  needs a per-player timer and a policy for the window; not worth choosing before anyone has hit it.
- **Mail never expires and the inbox is unbounded.** A player who never dismisses accrues rows
  forever. Bounding it means either an expiry (which loses mail) or a per-player cap (which needs a
  policy for what to drop); neither is worth choosing before anyone has hit it.
- **No reply, no threading, no subject line.** `MailPayload` keeps the sender UUID so `/mail reply`
  can be added without a migration; the JSON column means added fields need no DDL either.
- **No rate limiting on `/mail send`.** A player can spam another's inbox. Same conclusion as the
  Discord link codes: add a limit when abuse is seen, since its shape depends on the abuse.
- **No admin surface.** No way to read, delete or audit another player's mail, matching the existing
  absence of admin commands for the inbox and preferences.
