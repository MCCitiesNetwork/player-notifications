# Notification Inbox — Design

**Date:** 2026-08-07
**Status:** approved, not yet implemented

## Goal

Give players an inbox: every notification targeting them stays readable until they dismiss it or it
passes `notifExpiryTime`. Delivery stops consuming notifications and starts marking them **seen**.

This **supersedes** `docs/superpowers/plans/2026-07-31-sticky-notifications.md` (written, never
implemented, never committed). That plan made retention opt-in per notification via a
`notifSticky` flag. Retention is now unconditional, so the flag has no remaining purpose — a
sender who wants a message gone sets `notifExpiryTime`. Two pieces of that plan survive here: the
`deliveredAt`-style timestamp on `NotificationTarget` (as `seenTime`), and the orphaned-target
cleanup, which is a real pre-existing leak that matters more once rows live longer.

## Background: why the current model blocks this

Delivery is destructive. When a processor — or `RenderingProcessor`'s DELETE-wins fan-out — returns
`NotificationDisposition.DELETE`, `NotificationDelivery.deliver` calls
`NotificationTargetMapper.deleteMembers(targetId, [player])`, and the
`trg_delete_targetless_notification` trigger removes the `Notification` row once its target group
empties.

So what remains in the database is only what *failed* to deliver plus what is not yet due.
`resolveNotifications` therefore is not a mailbox and cannot become one while delivery destroys its
own input.

CLAUDE.md's deferred sketch solved this with an `inbox` **medium** — a `NotificationSink` persisting
the rendered notification into its own table. That is rejected here: it duplicates storage, it
renders eagerly at delivery time (so a later renderer change cannot affect already-stored entries),
and it makes "is this in my inbox" a per-player preference rather than a property of the message.
The user's decision that the inbox always receives everything makes the medium framing wrong on its
own terms.

## Architecture

### The state machine

`NotificationTarget` gains **one nullable column**, `seenTime DATETIME NULL`. Three states:

| State | Storage | In the inbox |
|---|---|---|
| unread | row exists, `seenTime IS NULL` | listed, marked, counted on join |
| seen | row exists, `seenTime` set | listed, not counted, never re-pushed |
| dismissed | target row deleted | absent |

**Dismissal deletes the target row** rather than setting a third timestamp. A dismissed notification
must never reappear, and an absent row enforces that without any query having to know the rule. It
also means dismissal reuses the existing trigger: removing the last member disposes of the
`Notification`. A `dismissedTime` column would have needed every read to filter on it, and would have
grown without bound with nothing to prune it.

Expiry is unchanged: `clearExpiredNotifications` on the existing async prune task already deletes
`Notification` rows past `notifExpiryTime`.

### `NotificationDisposition.DELETE` becomes `MARK_SEEN`

Same two values, same DELETE-wins fold (`combine` keeps its precedence, renamed accordingly),
non-destructive meaning. `NotificationDelivery` branches on it to write `seenTime` instead of calling
`deleteMembers`.

This is a **source-breaking change to the `api` module**, which feature modules compile against
separately. Both in-tree adapters are built from this repo and it is a one-line change in each. It is
taken deliberately rather than redefining `DELETE` in place: an enum constant named `DELETE` that no
longer deletes anything is a trap for the next reader, and the compiler finding every call site once
is cheaper than a silent semantic drift.

### The delivery loop reads unseen

`selectDueByPlayer` gains `AND t.seenTime IS NULL`. Without it every join re-pushes the entire inbox
through chat and Discord.

`RETAIN` is untouched: a notification nothing could deliver stays unread and is retried on the next
trigger, exactly as today.

`selectByPlayer`, behind `NotificationService.resolveNotifications`, is **not** filtered. It is
documented as "every notification currently targeting the player" and that stays true; the inbox has
its own query and does not need to change an existing method's meaning.

### Mute stops being a sink

`NullSink` returns `DELIVERED`, which under mark-instead-of-consume would mark a muted notification
seen and hide it from the unread list — precisely backwards for a player who muted a type in order to
read it later at their leisure.

- `NullSink` and `NullSinkTest` are **deleted**.
- The mute encoding survives as a constant: `NotificationPreferences.MUTED_MEDIUM = "none"`. It stays
  a stored medium value rather than becoming zero rows, for the reason `NullSink`'s javadoc already
  gave — zero rows already means "has expressed no preference", and the two must stay distinguishable.
- `RenderingProcessor` filters `MUTED_MEDIUM` out of the resolved media set. If nothing remains it
  logs at `fine` and returns `RETAIN`, leaving the notification unread in the inbox.

Mute now means **don't interrupt me**, not **don't tell me**. The inbox is the always-on backstop and
needs no registration at all, because the inbox *is* the persisted row.

### Read API

Two new records in `api`:

```java
public record InboxEntry(@NotNull String notifKey,
                         @NotNull Instant notifScheduledTime,
                         @Nullable Instant notifExpiryTime,
                         @NotNull String notifPayloadType,
                         @NotNull String notifPayload,
                         int notifPriority,
                         @Nullable Instant seenTime) {
    public boolean unread() { return this.seenTime == null; }
}

public record InboxPage(@NotNull List<InboxEntry> entries,
                        int page, int pageSize, int totalEntries, int unreadCount) {
    public int totalPages() { … }   // at least 1, so an empty inbox is "page 1 of 1"
}
```

`InboxPage` carries `totalEntries` and `unreadCount` because the list screen renders "page 2 of 5"
and the join line renders a count; deriving either from `entries` is impossible on a paged read, and
a second round trip per screen is not worth avoiding a five-field record.

Five new methods on `NotificationService`:

```java
@NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize);
int unreadCount(@NotNull UUID playerId);
void markSeen(@NotNull String notificationKey, @NotNull UUID playerId);
void markAllSeen(@NotNull UUID playerId);
void dismissSeen(@NotNull UUID playerId);
```

**Dismissing one notification needs no new method** — `deleteNotificationTarget(key, playerId)`
already removes exactly one player's copy and already lets the trigger clean up. The inbox's Dismiss
button calls it.

`page` is 1-based and clamped into range by the implementation, so a stale dialog button that asks
for page 9 of a now-4-page inbox returns page 4 rather than an empty screen or a throw.

### Rendering stored entries

The inbox renders on read, not on write, through the renderer already in
`NotificationDataTypeRegistry`. `paper.inbox.InboxEntryRenderer` resolves the payload class, decodes
via the registered `PayloadSerializer`, and calls the registered `NotificationRenderer` — the same
three lookups `NotificationDelivery.dispatch` does, extracted so both paths cannot drift and so this
one is unit-testable without a server.

An entry whose data type has no renderer (or fails to decode) renders as a placeholder row naming the
data type, rather than being hidden. Hiding it would make a notification invisible but still counted
in `totalEntries`, which reads as a bug from the player's side.

**`essentials-mail` gains a renderer.** `EssentialsMailModule` calls
`dataTypeRegistry().registerRenderer(EssentialsMailPayload.class, …)` alongside its existing
`registerJsonPayload`. Dispatch precedence is unaffected — an explicitly registered processor still
wins, so Essentials mail is still delivered by `EssentialsMailProcessor` and still bypasses
preferences. The renderer exists purely so the entry has a title and body to display. This is the
first case of a payload registering both, and it is the correct shape: the two answer different
questions.

### Dialog pagination utilities (new, library-shaped)

**No dialog in this plugin paginates today**, so the inbox introduces the first one. Rather than
writing paging inline in `InboxDialog`, it goes into a new package `paper.ui` written to be lifted
into `plugin-infrastructure` later, once a second consumer proves the shape.

The constraint that makes that lift a package rename rather than a rewrite: **`paper.ui` imports
nothing from `io.github.md5sha256.playernotifications`.** Only Paper, Bukkit and Adventure. Any type
that needs to know about a notification stays in `paper.inbox` and passes data in.

Two pieces:

```java
/** Page arithmetic. No Bukkit, no Adventure — pure, exhaustively testable. */
public record PageBounds(int page, int pageSize, int totalEntries) {
    public static final int MAX_PAGE_SIZE = 20;
    public PageBounds { /* clamps: pageSize 1..MAX, totalEntries >= 0, page 1..totalPages */ }
    public int totalPages();     // at least 1, so an empty list is "page 1 of 1"
    public int offset();         // (page - 1) * pageSize
    public boolean hasPrevious();
    public boolean hasNext();
    public PageBounds withPage(int page);
    public PageBounds previous();
    public PageBounds next();
}
```

```java
/** Dialog-side paging: the indicator line and the Previous/Next buttons. */
public final class PagedDialogs {
    public static Component pageIndicator(PageBounds bounds);   // "Page 2 of 5"
    public static void addNavigationButtons(List<ActionButton> buttons, PageBounds bounds,
                                            ClickCallback.Options options, IntConsumer openPage);
}
```

`addNavigationButtons` omits Previous on the first page and Next on the last, rather than showing
them disabled — a dialog button that does nothing is worse than an absent one, since the player
cannot tell it apart from a broken callback.

The same package absorbs three helpers that are already generic but currently trapped in the
package-private `PreferenceDialogs`: `callbackOptions()`, `onMainThread(...)` and `message(...)`.
They become `paper.ui.DialogSupport`; `PreferenceDialogs` delegates to it rather than keeping copies.
This is extraction of existing shared code, not speculation — the inbox needs all three on day one.

**`PageBounds`'s clamping is deliberately duplicated** by `DefaultNotificationService.inbox`, which
clamps its own arguments. That is not an oversight: the service is a public API trust boundary and
must not assume a well-behaved caller, while `PageBounds` is a UI convenience. The service cannot use
`PageBounds` in any case — it lives in `core`, and `paper.ui` must not be depended on by anything if
it is to move out cleanly.

### Paper UI

`/notifications` (bare) stops printing its placeholder notice and opens the inbox. The name was
already reserved for exactly this.

**List screen** (`paper.inbox.InboxDialog`) — paged, newest first, `inbox-page-size` rows per page.
Each row: unread marker and bold weight, title, relative age, category label. Buttons: *Mark all
read*, *Dismiss all read*, *Preferences*, and previous/next paging. Clicking a row opens the detail
screen.

**Detail screen** (`paper.inbox.InboxDetailDialog`) — title, body, and two buttons: *Dismiss*
(returns to the list) and *Back* (does not dismiss). Opening a row marks it seen. Back-doesn't-commit
mirrors the rule players already learned in the preference editors.

**Chat fallback** — `/notifications list [page]`, `/notifications read <n>`,
`/notifications dismiss <n>`, where `<n>` indexes the page most recently listed for that player, held
in the router. Dialogs are the primary path; the text route costs little and covers clients where the
dialog does not render.

**On join** — `JoinDeliveryListener` keeps pushing unseen due notifications through preferred media,
and additionally sends one line naming the unread count and `/notifications`. A player who muted
everything receives only that line, which is the whole point of separating mute from the inbox.

`paper.inbox.InboxRouter` owns both dialogs, the per-player page cursor, and the async marshalling —
the same shape as `PreferenceDialogRouter`, for the same reason: `NotificationService` does blocking
JDBC and `Player#showDialog` must run on the main thread.

### Configuration

`settings.yml` gains one key:

```yaml
# How many inbox entries are shown per page in /notifications.
# Clamped to 1..20; the dialog cannot usefully show more.
inbox-page-size: 7
```

`PluginSettings` gains `@Setting("inbox-page-size") int inboxPageSize` — a primitive, so deliberately
**not** `@Required`, matching `deliver-on-join`. Clamped in the compact constructor, matching
`join-delivery-delay-seconds`. Picked up by `/notifications reload` through the existing settings
reload.

### Migration

`V2__notification_inbox.sql`, layered rather than collapsed into V1 — there is deployed data:

```sql
ALTER TABLE NotificationTarget ADD COLUMN IF NOT EXISTS seenTime DATETIME NULL;
CREATE INDEX IF NOT EXISTS idx_target_player_seen ON NotificationTarget (playerUuid, seenTime);
```

Existing rows backfill as `NULL` = unread, which is correct for anything already queued. `IF NOT
EXISTS` on both so a re-run is inert; no `BEGIN…END`, matching the migrator's split-on-`;` handling.

CLAUDE.md's "earlier migrations were collapsed into V1 … no data to preserve" note becomes stale and
is corrected in the documentation task.

### Orphaned target rows (pre-existing leak, fixed here)

`deleteExpired`, `deleteByKey`, `deleteByPayloadType` and `deleteByPlayer` all delete `Notification`
rows without touching `NotificationTarget`, orphaning member rows permanently — the trigger only
fires the other way round. This leaks today; inbox rows live far longer, so it is fixed here with a
`deleteOrphanedTargets()` mapper called from the existing prune task.

An `AFTER DELETE ON Notification` trigger was rejected: it cascades into the existing trigger and
needs more verification than one explicit statement on a task that already runs hourly.

## Files

**Create**
- `core/src/main/resources/sql/migrations/V2__notification_inbox.sql`
- `api/…/api/InboxEntry.java`, `api/…/api/InboxPage.java`
- `core/…/core/database/entity/InboxNotificationEntity.java`
- `platform/paper-plugin/…/paper/inbox/InboxEntryRenderer.java`, `InboxRouter.java`,
  `InboxDialog.java`, `InboxDetailDialog.java`
- `platform/paper-plugin/…/paper/ui/PageBounds.java`, `PagedDialogs.java`, `DialogSupport.java`
- `platform/paper-plugin/src/test/java/…/paper/ui/PageBoundsTest.java`
- `core/src/test/java/…/core/database/InboxDeliveryTest.java`, `InboxReadTest.java`
- `platform/paper-plugin/src/test/java/…/paper/inbox/InboxEntryRendererTest.java`

**Modify**
- `api/…/processor/NotificationDisposition.java` — `DELETE` → `MARK_SEEN`
- `api/…/render/NotificationPreferences.java` — `MUTED_MEDIUM` constant
- `api/…/render/RenderingProcessor.java` — filter the muted medium, fold to `MARK_SEEN`
- `api/…/NotificationService.java` — five new methods
- `core/…/database/entity/NotificationTargetEntity.java` — add `seenTime`
- `core/…/database/mapper/NotificationMapper.java`, `NotificationTargetMapper.java` and their
  `maria/mapper/Maria*` implementations
- `core/…/NotificationDelivery.java`, `core/…/DefaultNotificationService.java`
- `core/…/database/maria/MariaSchemaMigrator.java` — one `DEFAULT_MIGRATIONS` entry
- `platform/paper-plugin/…/PlayerNotificationsPlugin.java` — drop the `NullSink` registration, wire
  the inbox router, extend the prune task
- `platform/paper-plugin/…/command/NotificationsCommand.java` — bare command, `list`/`read`/`dismiss`
- `platform/paper-plugin/…/JoinDeliveryListener.java` — unread-count line
- `platform/paper-plugin/…/preferences/PreferenceDialogs.java`,
  `preferences/session/PreferenceEditSession.java`, `diagnostic/TestNotificationSender.java` —
  `NullSink.MEDIUM_KEY` → `NotificationPreferences.MUTED_MEDIUM`
- `platform/paper-plugin/…/PluginSettings.java`, `src/main/resources/settings.yml`
- `platform/essentials-adapter/…/EssentialsMailBinding.java` — register a renderer
- `CLAUDE.md`

**Delete**
- `api/…/render/sink/NullSink.java`, `api/src/test/…/render/sink/NullSinkTest.java`
- `docs/superpowers/plans/2026-07-31-sticky-notifications.md` (superseded, never implemented)

## Error handling

- A payload that fails to decode is logged at `warning` and rendered as a placeholder row, not
  hidden; delivery already retains such notifications and that is unchanged.
- A data type with no registered renderer renders as a placeholder naming the type.
- `inbox(player, page, pageSize)` clamps `page` into `1..totalPages()` and `pageSize` into `1..20`
  rather than throwing, so a stale dialog button cannot produce an error screen.
- `markSeen` on a notification the player is not targeted by affects zero rows and is a silent no-op;
  the same is already true of `deleteNotificationTarget`.
- Dialog and command handlers marshal onto the async scheduler for the database call and back onto
  the main thread to show the dialog, as `PreferenceDialogs.withSession` already does.

## Testing strategy

- `core` (Testcontainers, `mariadb:11.7`): `InboxDeliveryTest` covers the mark-seen branch, the
  unseen filter on re-delivery, and that `RETAIN` leaves `seenTime` null. `InboxReadTest` covers
  paging boundaries, newest-first ordering, unread counts, expiry exclusion, per-player isolation,
  `markAllSeen`, `dismissSeen`, and orphan cleanup.
- `api`: `RenderingProcessorTest` gains cases for a muted media set (`RETAIN`, nothing delivered) and
  for a set mixing `none` with a real medium.
- `platform:paper-plugin`: `InboxEntryRendererTest` covers a rendered entry, an unregistered data
  type, and a payload that fails to decode. `PageBoundsTest` covers the paging arithmetic
  exhaustively — it needs no Bukkit types at all, which is precisely why the arithmetic was split
  away from the dialog.
- `SchemaUpgradeTest` gains a case asserting `NotificationTarget.seenTime` exists and
  `MAX(version) = 2` after migrating an empty schema.
- The dialogs, the Brigadier wiring and the join line need a live server and are verified by hand via
  `:platform:paper-plugin:runServer` — the same exception the preference dialogs already sit under.

## Known limitations

- **Partial delivery is still silent, but no longer lossy.** Chat succeeding while Discord fails
  still marks the notification seen and does not retry Discord. It now remains readable in the inbox,
  which is a real improvement over losing it, but per-medium delivery tracking is still not built.
- **Inbox size is unbounded.** Pagination covers display; the table grows until expiry or dismissal,
  and a notification with a null `notifExpiryTime` is kept indefinitely by design. Both were
  explicitly accepted. A per-player cap needs a policy for what falls off the end, which is a
  decision rather than a default, so it is deferred rather than guessed at.
- **Seen is per player, not per medium.** Reading a notification in the inbox marks it seen for that
  player everywhere; there is no "read on Discord but not in game".
- **An explicitly registered `NotificationProcessor` still bypasses preferences.** Unchanged by this
  work. Essentials mail still reaches a muted player — the mute change above alters what `none` means
  for the renderer path only.
- **No admin view.** No way to inspect or clear another player's inbox; that stays with the deferred
  admin-commands work.
- **`paper.ui` is not in `plugin-infrastructure` yet.** It is written to move there — no imports from
  this plugin, one obvious package rename — but it stays in-tree until a second consumer exists.
  Promoting a single-consumer abstraction to a shared library is how libraries acquire APIs shaped
  around one caller's accident. The rule to hold: if a change to `paper.ui` would only ever make
  sense for the inbox, it belongs in `paper.inbox` instead.
- **Marking seen is not transactional with delivery.** The processor runs outside the transaction (as
  today), so a crash between a sink delivering and the `seenTime` write leaves the notification unread
  and it will be pushed again. Chat is not idempotent, so this can duplicate a message. Accepted: the
  window is milliseconds and the alternative holds a database transaction across a Discord round trip.
