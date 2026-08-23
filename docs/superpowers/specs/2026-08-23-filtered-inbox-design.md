# Filtered inbox — design

**Date:** 2026-08-23
**Status:** proposed

## Goal

Let a player narrow their inbox to one **category** of notification — "show me only my mail",
"show me only broadcasts" — from the inbox screen they already open, without a second command tree
and without a second copy of the inbox machinery.

`/mail` already proves the shape: it is an `InboxRouter` pinned to the `mail` data type, and every
`NotificationService` inbox call it makes already carries that filter. What is missing is a filter a
player can *choose*, over the grouping they already see in `/notifications preferences types`.

Non-goal: filtering by anything other than a category (no sender filter, no date filter, no search).

## Architecture

Three layers change, each for its own reason.

### 1. The service filter becomes a set

A category claims many data types, so a single `@Nullable String dataType` cannot express it. The
four filtered methods on `NotificationService` take a collection instead, and today's single-type
forms become `default` delegates:

```java
@NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize,
                         @Nullable Collection<String> dataTypes);
int unreadCount(@NotNull UUID playerId, @Nullable Collection<String> dataTypes);
void markAllSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes);
void dismissSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes);

default @NotNull InboxPage inbox(UUID playerId, int page, int pageSize, @Nullable String dataType) {
    return inbox(playerId, page, pageSize, dataType == null ? null : List.of(dataType));
}
// … and the same for the other three
```

**`null` means unfiltered; an empty collection means "match nothing".** These are deliberately
different, and the distinction is reachable rather than theoretical: a category in `categories.yml`
may claim only data types that nothing has registered, and
`NotificationCategories#dataTypesForCategory` then returns an empty set. That screen must show an
empty inbox — collapsing empty onto `null` would show the player *every* notification under a
category heading claiming to contain none of them.

The `@Nullable String` forms are kept rather than deleted so that `platform:discord-adapter` and
`platform:essentials-mail-converter`, which compile separately against `api`, need no change.

**This does move which method is abstract**, so a third-party *implementor* of `NotificationService`
would fail to compile. There are none: the module contract is to call the service resolved from the
`ServicesManager`, and the in-tree implementations are `DefaultNotificationService` plus test fakes.
Carrying a parallel abstract method set forever to avoid that was judged the worse trade.

### 2. The mapper filter becomes an `IN` list

`MariaNotificationMapper.selectInboxPage`/`countInbox`/`countUnread` and
`MariaNotificationTargetMapper.markAllSeen`/`selectSeenKeys` each carry one
`<if test="dataType != null">AND n.notifPayloadType = #{dataType}</if>` today. Each becomes:

```xml
<choose>
  <when test="dataTypes == null"/>
  <when test="dataTypes.isEmpty()">AND 1 = 0</when>
  <otherwise>
    AND n.notifPayloadType IN
    <foreach item="t" collection="dataTypes" open="(" separator="," close=")">#{t}</foreach>
  </otherwise>
</choose>
```

The empty branch is not defensive padding: `<foreach>` over an empty collection emits `IN ()`, which
MariaDB rejects as a syntax error, so the "match nothing" case above would throw rather than return
an empty page.

Filtered `dismissSeen` stays select-then-delete (`selectSeenKeys` followed by one
`deleteNotificationTarget` per key). Its subquery reads `Notification` while
`trg_delete_targetless_notification` writes it, and MariaDB refuses that — the same constraint
`pruneOrphanedTargets` already works around. Widening the filter to a set does not change it.

`markAllSeen`'s filter remains an `EXISTS` against `Notification` rather than a join, because
`NotificationTarget` has no `notifPayloadType` column.

### 3. The filter is dialog-only state, passed per call

**The commands do not change at all.** `/notifications list|read|delete|clear` behave exactly as they
do today, on the whole inbox, and there is no `/notifications filter` subcommand. Filtering is a
property of the dialog screens and nothing else.

That rules out holding the filter on the page cursor. `InboxRouter.withPage` is shared: the dialog
and the chat listing both go through it, and both write the single `cursors` and `lastListed` maps. A
filter carried there would reach `/notifications read <entry>` by construction — the exact drift this
decision exists to avoid.

**So the cursor splits in two, one per surface.** This is a change to existing behaviour, made
deliberately: today a dialog page-turn moves the same cursor a chat `read <entry>` resolves against,
so the two surfaces already interfere — the filter would only make it visible. After the split each
surface indexes its own last listing and neither can move the other's.

The split costs nothing structurally, because the two sides already read disjoint state:

| Field | Written by | Read by |
|---|---|---|
| `dialogCursor` (`PageBounds`) | dialog opens | `currentPage`, for the reopen after *Mark all read* / *Delete all read* |
| `dialogListed` (`List<InboxEntry>`) | dialog opens | `findEntry`, resolving a clicked row's key |
| `chatListed` (`List<InboxEntry>`) | `listInChat` | `indexed`, resolving `read <entry>` / `delete <entry>` |

There is no chat cursor: `/notifications list [page]` carries its page explicitly, which is why
`currentPage` has only dialog callers today.

`dataTypeFilter` likewise stops being a field read implicitly and becomes a **parameter at every call
site**, with the dialog's own scope beside it:

```java
private final @Nullable Set<String> pinnedFilter;              // /mail pins; /notifications: null
private final Map<UUID, String> dialogCategory = new ConcurrentHashMap<>();   // dialog scope only

private void withPage(Player player, int page, @Nullable Set<String> filter,
                      Surface surface, Consumer<InboxPage> consumer);   // Surface: DIALOG | CHAT
```

| Caller | Filter passed | Surface |
|---|---|---|
| `openInbox`, dialog paging, the dialog's *Mark all read* / *Delete all read* | the player's `dialogCategory`, resolved, else `pinnedFilter` | `DIALOG` |
| `listInChat` | `pinnedFilter` — unconditionally | `CHAT` |
| `readInChat`, `dismissInChat`, `clearInChat` | `pinnedFilter` — unconditionally | n/a |

A **pinned** router (`/mail`) ignores `dialogCategory` entirely and renders no Filter button, so it is
exactly what it is today. `drop(playerId)` clears all four maps, so `InboxQuitListener` is unchanged.
`clearInChat` keeps dropping the state that could leave a stale index — now `chatListed` only, since
that is the one `indexed` reads.

**Rejected: the filter on the cursor.** It reads better — one piece of state, one lookup — but it is
precisely what makes a command's meaning depend on a dialog the player closed.

**Rejected: a router instance lazily created per (command, category).** It spreads per-player maps
across an open-ended set of instances and forces `InboxQuitListener`'s fixed `List<InboxRouter>` to
become dynamic, buying nothing the map above does not.

### 4. Filter resolution is a plain class

`paper.inbox.InboxFilters` — no Bukkit types, so it is unit-testable without a server:

```java
final class InboxFilters {
    InboxFilters(NotificationCategories categories, NotificationDataTypeRegistry registry);
    @Nullable Set<String> resolve(@Nullable String categoryKey);   // null key → null (unfiltered)
    List<String> categoryKeys();                                   // sorted, UNCATEGORIZED last
    Component label(@Nullable String categoryKey);
}
```

`resolve` delegates to `NotificationCategories#dataTypesForCategory(key, registry.dataTypes())`. It
reads both live on every call rather than snapshotting, matching `TypeNames` — a module registering
late, or a `/notifications reload`, is picked up by the next screen open with no listener. The
`NotificationCategories` reference is therefore mutable and swapped by `reloadCategories`, the same
way `PreferenceDialogRouter` already takes it.

## UI flow

### Dialogs

`InboxDialog` gains one `ActionButton`, first in the grid, reading **`Filter: All`** or
**`Filter: <category label>`**. It opens `InboxFilterDialog`, a new `multiAction` picker:

| Row | Reads |
|---|---|
| All notifications | `All notifications — 3 unread of 12` |
| one per category | `Mail — 2 unread of 5` |
| … | `Broadcasts — 0 unread of 0` (grey) |

plus Back. Choosing a row reopens `InboxDialog` at page 1 with that filter; choosing "All
notifications" clears it. The title becomes the category label, so the screen names its own scope in
two places.

**Every category is listed, including empty ones, greyed rather than hidden.** A picker whose rows
appear and disappear as mail arrives makes the row a player is reaching for move under their cursor,
and "nothing here yet" is itself the answer to "where is my mail". It also keeps the row set stable
between opens, which is what makes the screen learnable.

The picker can never be empty — the "All notifications" row is unconditional — so it does not hit
vanilla's `MultiActionDialog` empty-`actions` codec failure that `InboxRouter.openInbox` guards
against.

*Mark all read* and *Delete all read* on a filtered screen act **on the filter only**. That is the
feature, and it falls out of the service calls already carrying the filter rather than needing a
case.

`InboxDetailDialog` is untouched: its Back reopens the list through the router, which re-reads
`dialogCategory`, so the player returns to the filtered screen they came from.

### Chat

**Nothing changes.** `/notifications list|read|delete|clear` keep their present wiring and their
present meaning: they act on the whole inbox, `clear` still empties it entirely, and there is no
subcommand for setting a filter.

This is a deliberate asymmetry, not an oversight or a phase one. Filtering is a browsing affordance —
it is worth having where a player can see the scope on screen and change it in one click, and it is a
trap in a command surface, where a filter set ten minutes ago is invisible and makes `/notifications
list` quietly stop showing things. The chat path exists as the fallback for clients where dialogs do
not render, and its job is to be predictable.

`/mail` gains nothing either: its router is pinned, so it shows no Filter button.

## Files touched

**Create**
- `platform/paper-plugin/…/paper/inbox/InboxFilters.java`
- `platform/paper-plugin/…/paper/inbox/InboxFilterDialog.java`
- `platform/paper-plugin/src/test/…/paper/inbox/InboxFiltersTest.java`
- `core/src/test/…/core/FilteredInboxSetTest.java`

**Modify**
- `api/…/api/NotificationService.java` — the four set-taking methods plus the `String` delegates
- `core/…/core/DefaultNotificationService.java` — implement the set forms; clamping unchanged
- `core/…/core/database/mapper/NotificationMapper.java`, `NotificationTargetMapper.java` — signatures
- `core/…/core/database/maria/mapper/MariaNotificationMapper.java`,
  `MariaNotificationTargetMapper.java` — the `<choose>` above
- `platform/paper-plugin/…/paper/inbox/InboxRouter.java` — the cursor split (`dialogCursor`,
  `dialogListed`, `chatListed`), `withPage` taking a filter and a `Surface`, `dialogCategory`,
  `pinnedFilter`, and the dialog-side filter setter
- `platform/paper-plugin/…/paper/inbox/InboxDialog.java` — the Filter button
- `platform/paper-plugin/…/paper/PlayerNotificationsPlugin.java` — build `InboxFilters`, pass
  `pinnedFilter` (`null` for the inbox router, `Set.of(MailPayload.DATA_TYPE)` for mail), and swap the
  reloaded `NotificationCategories` into `InboxFilters` alongside the existing `reloadCategories` call
- `CLAUDE.md` — "Notification inbox"

**Untouched, deliberately:** `paper/command/NotificationsCommand.java`, `MessageKeys.java` and
`messages.yml`. No command changes, and the new strings are dialog labels — which
`CLAUDE.md`'s "Messages" section already keeps hardcoded, because `paper.ui` may import nothing from
this plugin and dialog strings arrive as `Component` parameters rather than from the container.

## Error handling

- A category that resolves to an empty set opens an empty inbox — which, per `InboxRouter.openInbox`,
  replies `INBOX_EMPTY` in chat rather than opening a dialog with no rows.
- A filter naming a data type whose payload no longer renders is unaffected: `InboxEntryRenderer`
  already yields a placeholder naming the data type rather than hiding the entry.
- A `/notifications reload` that removes the category a player is currently filtered to leaves a stale
  key in `dialogCategory`. The next dialog open re-resolves it, gets an empty set, and shows the empty
  reply; the player clears it from the picker. No exception, no special case.

## Testing strategy

**Against a real MariaDB** (`core`, Testcontainers), extending the existing `FilteredInboxTest`
pattern:

- a set of two data types returns entries of both and excludes a third
- `null` is unfiltered — the existing single-type cases still pass through the `default` delegate
- an **empty** set returns zero entries, `totalEntries() == 0`, and does **not** throw (the `IN ()`
  regression)
- filtered `markAllSeen` and `dismissSeen` over a set touch only matching rows
- paging clamps against the *filtered* total

**Plain unit tests** (`paper-plugin`, no Docker, no server) for `InboxFilters`: a category claiming
two types resolves to both; `UNCATEGORIZED` resolves to the complement; an unknown key resolves to
empty; a `null` key resolves to `null`; a late registry addition is visible on the next call.

**Live server only** (`:platform:paper-plugin:runServer`), with a manual checklist in the plan:
`InboxFilterDialog` rendering, the Filter button's label tracking the active filter, a filtered
*Delete all read* leaving other categories alone, `/mail` showing no Filter button, **paging the
dialog and then running `/notifications read 1` to confirm it resolves against the last chat listing
rather than the dialog page**, and — the
regression that matters most here — **setting a dialog filter and then running `/notifications list`
and `/notifications clear`, both of which must still act on the whole inbox.**

## Known limitations

- **A stored notification whose data type is no longer registered is unreachable from any filtered
  screen.** `dataTypesForCategory` computes `UNCATEGORIZED` as the complement over the *registry's*
  known types, so a payload type left behind by an uninstalled module belongs to no category's set. It
  is still visible under "All notifications", which is why this is a wart rather than data loss. Fixing
  it means resolving the complement against the data types actually present in the player's inbox — a
  second query per screen open, for a case that only arises after a module is removed.
- **The picker runs one count pair per category.** Categories are few and the read is already async,
  but this is the part that would hurt first if a server defined dozens. The fallback, if it ever
  bites, is a single grouped `COUNT(*) … GROUP BY notifPayloadType` folded into categories in Java.
- **The filter does not survive a quit.** It is dropped with the page cursor by `InboxQuitListener`,
  because it is view state rather than a preference: a filter that persisted would need storage, and
  more importantly would need an escape hatch for a player who set one, forgot, and later concludes
  their mail has stopped arriving.
- **`read <entry>` no longer follows the dialog, which is a behaviour change.** Before this, opening
  the dialog and paging to page 2 moved the cursor that `/notifications read 1` resolved against;
  now the two surfaces have separate cursors, so `read 1` always means entry 1 of the last **chat**
  listing. A player who has only ever used the dialog and then types `read 1` gets the
  `INBOX_LIST_FIRST` reply telling them to list first, where previously they might have got an entry.
  That is the intended trade: a command's meaning should not depend on a screen that is closed. The
  dialog is unaffected — its rows carry their own `notifKey` and never index.
- **Nothing dismisses `dialogListed` when the dialog closes**, because Paper reports no such event.
  It is bounded by one page per player and dropped on quit, and a click on a stale row falls through
  to the existing `INBOX_GONE` reply.
- **Chat has no way to filter, permanently by design** (see UI flow → Chat). If that turns out to be
  wrong, the cheapest correction is a `/notifications list <category> [page]` that filters *that one
  listing* without storing anything — not a stateful `filter` subcommand.
- **Only one category at a time.** Multi-select is expressible in the set-taking API — that is what
  the set is — but there is no UI for it, and no reported want.
- **Empty categories are shown greyed rather than hidden**, deliberately (see UI flow). If operators
  define many categories that a given server never populates, this becomes noise, and the fix is a
  config toggle rather than a redesign.
- **No filtered unread count on join.** `JoinDeliveryListener`'s lines stay whole-inbox plus mail, as
  now. A per-category join summary is a different feature and would compete with the mail line for the
  same screen space.
