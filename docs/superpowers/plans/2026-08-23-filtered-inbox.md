# Filtered Inbox Implementation Plan

**Goal:** Let a player narrow the inbox dialog to one notification category, without changing any command.
**Spec:** `docs/superpowers/specs/2026-08-23-filtered-inbox-design.md`

Tasks 1 and 2 touch disjoint files and were run concurrently. Task 3 consumes both, so it follows
them; Tasks 4–6 are sequential after that.

## Task 1: Widen the service filter to a set

**Files:** modify `api/…/api/NotificationService.java`,
`core/…/core/DefaultNotificationService.java`,
`core/…/core/database/mapper/NotificationMapper.java`, `NotificationTargetMapper.java`,
`core/…/core/database/maria/mapper/MariaNotificationMapper.java`,
`MariaNotificationTargetMapper.java`; create `core/src/test/…/core/FilteredInboxSetTest.java`

**Interfaces:**
```java
@NotNull InboxPage inbox(UUID playerId, int page, int pageSize, @Nullable Collection<String> dataTypes);
int unreadCount(UUID playerId, @Nullable Collection<String> dataTypes);
void markAllSeen(UUID playerId, @Nullable Collection<String> dataTypes);
void dismissSeen(UUID playerId, @Nullable Collection<String> dataTypes);
```
`null` = unfiltered, empty = matches nothing. The `@Nullable String` forms become `default`
delegates via `List.of(dataType)`.

- [ ] Write the failing test `FilteredInboxSetTest`: a two-type set returns both and excludes a
      third; `null` is unfiltered; an **empty** set returns zero entries with `totalEntries() == 0`
      and does not throw; filtered `markAllSeen`/`dismissSeen` touch only matching rows; paging
      clamps against the filtered total
- [ ] Run `./gradlew :core:test --tests "*FilteredInboxSetTest*"` — expect FAIL: the set-taking
      methods do not exist
- [ ] Implement the four `api` methods, `DefaultNotificationService`, both mapper interfaces, and
      the `<choose>/<when>/<foreach>` blocks with the `AND 1 = 0` empty branch
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew :api:test :core:test`
- [ ] Commit

## Task 2: `InboxFilters` — category key to data-type set

**Files:** create `platform/paper-plugin/…/paper/inbox/InboxFilters.java`,
`platform/paper-plugin/src/test/…/paper/inbox/InboxFiltersTest.java`

**Interfaces:**
```java
public InboxFilters(NotificationCategories categories, NotificationDataTypeRegistry registry);
public @Nullable Set<String> resolve(@Nullable String categoryKey);
public List<String> categoryKeys();          // sorted, UNCATEGORIZED last
public Component label(@Nullable String categoryKey);
public void reloadCategories(NotificationCategories categories);
```

- [ ] Write the failing test `InboxFiltersTest`: two claimed types resolve to both; `UNCATEGORIZED`
      resolves to the complement; an unknown key resolves to empty; a `null` key resolves to `null`;
      a data type registered after construction is visible on the next call; `categoryKeys()` puts
      `UNCATEGORIZED` last
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*InboxFiltersTest*"` — expect FAIL: class
      does not exist
- [ ] Implement, reading `categories` and `registry.dataTypes()` live on every call rather than
      snapshotting, so a late module registration and `/notifications reload` are both picked up
- [ ] Run the same command — expect PASS, confirming the count rather than the exit status
- [ ] Commit

## Task 3: Split the cursor per surface

**Files:** modify `platform/paper-plugin/…/paper/inbox/InboxRouter.java`

**Interfaces:**
```java
private void withPage(Player player, int page, @Nullable Set<String> filter,
                      Surface surface, Consumer<InboxPage> consumer);
enum Surface { DIALOG, CHAT }
```
`dialogCursor` / `dialogListed` / `chatListed` replace the shared `cursors` / `lastListed`.

- [ ] Replace the two shared maps with the three above; `currentPage` and `findEntry` read the
      dialog pair, `indexed` reads `chatListed`
- [ ] Thread `Surface` and the filter through `withPage`; chat entry points pass `pinnedFilter`
      unconditionally
- [ ] `drop(playerId)` clears all four maps; `clearInChat` drops `chatListed`
- [ ] Rename the `dataTypeFilter` constructor argument to `pinnedFilter` and widen it to
      `@Nullable Set<String>`; update both construction sites in `PlayerNotificationsPlugin`
      (`null` for the inbox router, `Set.of(MailPayload.DATA_TYPE)` for mail)
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect PASS, no behavioural test covers the
      router (it needs a live server), so this is a compile-and-regression gate
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 4: The filter picker dialog

**Files:** create `platform/paper-plugin/…/paper/inbox/InboxFilterDialog.java`; modify
`InboxDialog.java`, `InboxRouter.java`, `PlayerNotificationsPlugin.java`

**Live-server task.** `InboxDialog` and `InboxFilterDialog` cannot be unit tested — dialogs need a
running server, the exception every dialog in this tree sits under. There is no test step; the
verification is Task 6's checklist.

- [ ] `InboxRouter` gains `dialogCategory` (`Map<UUID, String>`), a `setDialogFilter` used by the
      picker, and `openFilterPicker(Player)`
- [ ] `InboxFilterDialog` renders one row per `InboxFilters#categoryKeys` plus an unconditional
      "All notifications" row and a Back button, each row labelled with the category name only — no
      counts, so the picker issues no query at all
- [ ] `InboxDialog` gains a `Filter: <label>` button, first in the grid, omitted entirely when the
      router is pinned
- [ ] `PlayerNotificationsPlugin` builds `InboxFilters` and swaps the reloaded
      `NotificationCategories` into it beside the existing `PreferenceDialogRouter.reloadCategories`
      call in `reload()`
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 5: Update CLAUDE.md

**Files:** modify `CLAUDE.md`

- [ ] "Notification inbox": the set-valued filter and its null-vs-empty rule; the per-surface cursor
      split and that `read <entry>` no longer follows the dialog; the Filter button and picker; that
      the commands are deliberately unfiltered
- [ ] "Testing gotchas": refresh the per-module test counts from a full `./gradlew test` run rather
      than arithmetic on the old figures
- [ ] "Current state": add the unrun manual checklist below to the list of unverified surfaces
- [ ] Commit

## Task 6: Manual verification checklist

**Live server only:** `./gradlew :platform:paper-plugin:runServer`. Needs a reachable MariaDB per
`database.yml`. Not yet run.

- [ ] `/notifications` opens the inbox with a `Filter: All` button
- [ ] The button opens the picker; every category is listed, "All notifications" first, the active
      row marked, and **no unread numbers anywhere on the screen**
- [ ] Back returns to the list and Close only closes — one of each, not two Backs
- [ ] Choosing a category reopens the list filtered, titled with the category label, button reading
      `Filter: <label>`
- [ ] Paging within a filtered screen stays filtered
- [ ] *Mark all read* on a filtered screen marks only that category
- [ ] *Delete all read* on a filtered screen deletes only that category
- [ ] Opening an entry and pressing Back returns to the **filtered** list
- [ ] A category claiming only unregistered data types opens the empty-inbox chat reply **and then the
      picker**, so the filter can still be cleared (the trap case)
- [ ] **With a dialog filter set, `/notifications list` shows the whole inbox**
- [ ] **With a dialog filter set, `/notifications clear` empties the whole inbox**
- [ ] **Page the dialog, then `/notifications read 1` — it resolves against the last chat listing,
      not the dialog page** (and replies "list first" if there has been no chat listing)
- [ ] `/mail` shows no Filter button and is otherwise unchanged
- [ ] `/notifications reload` after removing the filtered category leaves the player with an empty
      screen and no exception in the console
