# Notification Inbox Implementation Plan

**Goal:** Notifications targeting a player stay readable until dismissed or expired; delivery marks them seen instead of deleting them.
**Spec:** `docs/superpowers/specs/2026-08-07-notification-inbox-design.md`

Tasks 1–7 are test-first. Task 8 needs a live server and gives manual steps instead.

---

## Task 1: Schema V2 + `seenTime` on the target entity

**Files:** create `core/src/main/resources/sql/migrations/V2__notification_inbox.sql`; modify
`core/…/database/maria/MariaSchemaMigrator.java`,
`core/…/database/entity/NotificationTargetEntity.java`,
`core/…/database/maria/mapper/MariaNotificationTargetMapper.java`; test
`core/src/test/java/…/core/database/SchemaUpgradeTest.java`

```sql
ALTER TABLE NotificationTarget ADD COLUMN IF NOT EXISTS seenTime DATETIME NULL;
CREATE INDEX IF NOT EXISTS idx_target_player_seen ON NotificationTarget (playerUuid, seenTime);
```

- [ ] Add to `SchemaUpgradeTest`:
      ```java
      @Test
      void migratesEmptySchemaToVersionTwoWithSeenTime() throws Exception {
          new MariaSchemaMigrator(dataSource()).migrate(MariaSchemaMigrator.DEFAULT_MIGRATIONS);
          try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
              ResultSet cols = s.executeQuery("""
                      SELECT COUNT(*) FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'NotificationTarget'
                        AND COLUMN_NAME = 'seenTime'
                      """);
              Assertions.assertTrue(cols.next());
              Assertions.assertEquals(1, cols.getInt(1));
          }
          try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
              ResultSet v = s.executeQuery("SELECT MAX(version) FROM schema_version");
              Assertions.assertTrue(v.next());
              Assertions.assertEquals(2, v.getInt(1));
          }
      }
      ```
      Match the existing fixture's accessor names for the datasource — read the class first and use
      whatever it already exposes rather than inventing `dataSource()` if it differs.
- [ ] `./gradlew :core:test --tests "*SchemaUpgradeTest"` — expect FAIL (column absent, version 1)
- [ ] Add the SQL file, and
      `new MigrationStep(2, "notification inbox", "V2__notification_inbox.sql")` to
      `MariaSchemaMigrator.DEFAULT_MIGRATIONS`.
- [ ] Add `@Nullable Instant seenTime` as the last component of `NotificationTargetEntity`; add
      `seenTime` to `selectPlayerUuids`' sibling reads where the entity is constructed. Fix
      construction sites by passing `null`.
- [ ] `./gradlew :core:test --tests "*SchemaUpgradeTest"` — expect PASS
- [ ] `./gradlew build`
- [ ] Commit

## Task 2: `MARK_SEEN` and mute without a sink (api)

**Files:** modify `api/…/processor/NotificationDisposition.java`,
`api/…/render/NotificationPreferences.java`, `api/…/render/RenderingProcessor.java`; delete
`api/…/render/sink/NullSink.java` and `api/src/test/…/render/sink/NullSinkTest.java`; modify
`api/src/test/…/render/RenderingProcessorTest.java`, plus the call sites in
`core/…/DatabaseNotificationPreferences.java`,
`platform/paper-plugin/…/preferences/PreferenceDialogs.java`,
`…/preferences/session/PreferenceEditSession.java`, `…/diagnostic/TestNotificationSender.java`,
`…/PlayerNotificationsPlugin.java`

Rename `NotificationDisposition.DELETE` to `MARK_SEEN`, updating its javadoc to "Mark the
notification seen for this target; it is retained and not offered for delivery again." `combine`
keeps its shape with `MARK_SEEN` winning.

Add to `NotificationPreferences`:

```java
/**
 * The reserved medium encoding an explicit mute. Stored as a row rather than as zero rows,
 * because zero rows already means "has expressed no preference" and the two must stay
 * distinguishable. It is not a registered sink: {@link RenderingProcessor} filters it out and
 * delivers nothing, leaving the notification unread in the player's inbox.
 */
String MUTED_MEDIUM = "none";
```

In `RenderingProcessor.receiveNotification`, after resolving `media`, drop `MUTED_MEDIUM` and
return `RETAIN` if nothing remains:

```java
Set<String> media = new LinkedHashSet<>(this.preferences.preferredMedia(target, this.dataType));
media.remove(NotificationPreferences.MUTED_MEDIUM);
if (media.isEmpty()) {
    this.logger.fine(() -> "No deliverable media for " + target + "; leaving notification unread");
    return NotificationDisposition.RETAIN;
}
```

- [ ] Add to `RenderingProcessorTest`: a case where `preferredMedia` returns
      `Set.of(NotificationPreferences.MUTED_MEDIUM)` asserting the result is `RETAIN` and that a
      registered spy sink was never called; and a case where it returns
      `Set.of(NotificationPreferences.MUTED_MEDIUM, "chat")` asserting the chat sink *is* called and
      the result is `MARK_SEEN`.
- [ ] `./gradlew :api:test --tests "*RenderingProcessorTest"` — expect FAIL (compile error: no
      `MUTED_MEDIUM`, no `MARK_SEEN`)
- [ ] Implement the rename, the constant, and the filter. Delete `NullSink` and `NullSinkTest`.
      Replace every `NullSink.MEDIUM_KEY` reference with `NotificationPreferences.MUTED_MEDIUM`, and
      remove `this.sinkRegistry.registerSink(new NullSink());` from
      `PlayerNotificationsPlugin.onEnable`. In `PreferenceDialogs.availableMedia` the
      `sorted.remove(NullSink.MEDIUM_KEY)` line stays, against the new constant — the muted medium
      must still not appear as a checkbox.
- [ ] `./gradlew :api:test --tests "*RenderingProcessorTest"` — expect PASS
- [ ] `./gradlew build` — expect both adapters to compile; fix the `DELETE` → `MARK_SEEN` reference in
      `platform/essentials-adapter/…/EssentialsMailProcessor.java`
- [ ] `./gradlew test` — expect the full suite green; `PlayerNotificationPreferenceTest`'s
      `Set.of("none")` assertions still hold, since the stored value is unchanged
- [ ] Commit

## Task 3: Mark-seen on the delivery path

**Files:** modify `core/…/database/mapper/NotificationTargetMapper.java`,
`core/…/database/maria/mapper/MariaNotificationTargetMapper.java`,
`core/…/database/maria/mapper/MariaNotificationMapper.java`, `core/…/NotificationDelivery.java`;
test create `core/src/test/java/…/core/database/InboxDeliveryTest.java`

New mapper method:

```java
int markSeen(@Param("notifTargetId") int notifTargetId,
             @Param("playerUuid") @NotNull UUID playerUuid,
             @Param("seenTime") @NotNull Instant seenTime);
```

```sql
UPDATE NotificationTarget SET seenTime = #{seenTime}
WHERE notifTargetId = #{notifTargetId} AND playerUuid = #{playerUuid} AND seenTime IS NULL
```

The `AND seenTime IS NULL` keeps the first mark authoritative, so a re-delivery race cannot move the
timestamp forward.

`MariaNotificationMapper.selectDueByPlayer` gains `AND t.seenTime IS NULL` to its `WHERE` clause. In
`NotificationDelivery.deliver`, the `toPrune` list becomes `toMark`, and the second session body
calls `targetMapper.markSeen(notification.notifTargetId(), target, now)` instead of
`deleteMembers(...)`. Rename the local and update the class javadoc, which currently describes
pruning.

- [ ] Write `InboxDeliveryTest` (extends `AbstractDatabaseTest`) with four cases:
      (a) a notification whose processor returns `MARK_SEEN` survives `deliver`, with `seenTime`
      non-null and the `Notification` row still present;
      (b) a second `deliver` for the same player does not invoke the processor again;
      (c) a processor returning `RETAIN` leaves `seenTime` null and *is* re-offered on a second
      `deliver`;
      (d) marking seen for one player leaves a second player in the same target group unread.
- [ ] `./gradlew :core:test --tests "*InboxDeliveryTest"` — expect FAIL (row deleted, no `markSeen`)
- [ ] Implement the mapper method, the due-filter change, and the branch.
- [ ] `./gradlew :core:test --tests "*InboxDeliveryTest"` — expect PASS
- [ ] `./gradlew :core:test` — the existing suite must stay green; any test asserting a delivered
      notification is *deleted* is asserting the old contract and is updated to assert `seenTime`
      instead, with a comment naming this plan
- [ ] `./gradlew build`
- [ ] Commit

## Task 4: Inbox read API

**Files:** create `api/…/api/InboxEntry.java`, `api/…/api/InboxPage.java`,
`core/…/database/entity/InboxNotificationEntity.java`; modify
`core/…/database/mapper/NotificationMapper.java`,
`core/…/database/maria/mapper/MariaNotificationMapper.java`, `api/…/NotificationService.java`,
`core/…/DefaultNotificationService.java`; test create
`core/src/test/java/…/core/database/InboxReadTest.java`

```java
public record InboxEntry(@NotNull String notifKey, @NotNull Instant notifScheduledTime,
                         @Nullable Instant notifExpiryTime, @NotNull String notifPayloadType,
                         @NotNull String notifPayload, int notifPriority,
                         @Nullable Instant seenTime) {
    public boolean unread() { return this.seenTime == null; }
}

public record InboxPage(@NotNull List<InboxEntry> entries, int page, int pageSize,
                        int totalEntries, int unreadCount) {
    public int totalPages() {
        return Math.max(1, (this.totalEntries + this.pageSize - 1) / this.pageSize);
    }
}
```

`InboxNotificationEntity` mirrors `InboxEntry` in `core`, matching the entity-per-read convention;
`DefaultNotificationService` maps entity → API record as it already does for `ResolvedNotification`.

Service methods:

```java
@NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize);
int unreadCount(@NotNull UUID playerId);
```

`DefaultNotificationService.inbox` clamps `pageSize` to `1..20`, reads `countInbox` first, clamps
`page` to `1..totalPages`, then reads the page with `offset = (page - 1) * pageSize`.

```sql
SELECT n.notifKey, n.notifScheduledTime, n.notifExpiryTime, n.notifPayloadType,
       n.notifPayload, n.notifPriority, t.seenTime
FROM Notification n
INNER JOIN NotificationTarget t ON t.notifTargetId = n.notifTargetId
WHERE t.playerUuid = #{playerId}
  AND n.notifScheduledTime <= #{now}
  AND (n.notifExpiryTime IS NULL OR n.notifExpiryTime > #{now})
ORDER BY n.notifScheduledTime DESC, n.notifPriority DESC, n.notifKey DESC
LIMIT #{limit} OFFSET #{offset}
```

`notifKey DESC` is the tiebreak: without a total order two notifications sharing a timestamp can
swap between page reads and appear twice or not at all. `countInbox` and `countUnread` use the same
`WHERE` clause, the latter with `AND t.seenTime IS NULL`.

- [ ] Write `InboxReadTest` with cases: newest-first ordering; `LIMIT`/`OFFSET` paging across three
      pages with no duplicates or gaps; `page = 0` and `page = 99` both clamp into range;
      `pageSize = 0` and `pageSize = 500` clamp to 1 and 20; an expired notification is excluded; a
      not-yet-scheduled notification is excluded; `unreadCount` counts only `seenTime IS NULL`;
      another player's rows are absent; an empty inbox returns `totalPages() == 1`.
- [ ] `./gradlew :core:test --tests "*InboxReadTest"` — expect FAIL (no such method). **Check the
      result count, not the exit status**, and glob `*.xml` not `TEST-*.xml`.
- [ ] Implement the records, the entity, the three mapper queries, and both service methods.
- [ ] `./gradlew :core:test --tests "*InboxReadTest"` — expect PASS
- [ ] `./gradlew build`
- [ ] Commit

## Task 5: Seen/dismiss writes and orphan cleanup

**Files:** modify `core/…/database/mapper/NotificationTargetMapper.java` +
`MariaNotificationTargetMapper.java`, `api/…/NotificationService.java`,
`core/…/DefaultNotificationService.java`,
`platform/paper-plugin/…/PlayerNotificationsPlugin.java`; test extend `InboxReadTest`

Three mapper methods:

```sql
-- markAllSeenForPlayer
UPDATE NotificationTarget SET seenTime = #{seenTime}
WHERE playerUuid = #{playerUuid} AND seenTime IS NULL
```

```sql
-- deleteSeenForPlayer
DELETE FROM NotificationTarget
WHERE playerUuid = #{playerUuid} AND seenTime IS NOT NULL
```

```sql
-- deleteOrphanedTargets
DELETE t FROM NotificationTarget t
LEFT JOIN Notification n ON n.notifTargetId = t.notifTargetId
WHERE n.notifTargetId IS NULL
```

Three service methods:

```java
void markSeen(@NotNull String notificationKey, @NotNull UUID playerId);   // resolves targetId by key
void markAllSeen(@NotNull UUID playerId);
void dismissSeen(@NotNull UUID playerId);
void pruneOrphanedTargets();
```

`markSeen(key, player)` reads `selectByKey(key)` for the target id, then calls the existing
`markSeen(targetId, player, Instant.now())`; a missing key is a no-op. `dismissSeen` deletes target
rows, so the existing trigger removes the now-targetless `Notification` — no second cleanup path.

`PlayerNotificationsPlugin.schedulePruneTask`'s body calls `pruneOrphanedTargets()` after
`clearExpiredNotifications()`.

- [ ] Extend `InboxReadTest` with: `markAllSeen` sets every unread row for that player and leaves
      another player's untouched; `markAllSeen` does not move an already-set `seenTime`;
      `dismissSeen` removes seen rows, leaves unread ones, and the trigger deletes the orphaned
      `Notification`; `markSeen(key, player)` on an unknown key is a no-op; after
      `clearExpiredNotifications` orphans target rows, `pruneOrphanedTargets` removes exactly those
      and leaves live ones.
- [ ] `./gradlew :core:test --tests "*InboxReadTest"` — expect FAIL (no such methods)
- [ ] Implement the mappers, the service methods, and the prune-task call.
- [ ] `./gradlew :core:test --tests "*InboxReadTest"` — expect PASS
- [ ] `./gradlew build`
- [ ] Commit

## Task 6: Rendering stored entries, and a renderer for Essentials mail

**Files:** create `platform/paper-plugin/…/paper/inbox/InboxEntryRenderer.java`; modify
`platform/essentials-adapter/…/EssentialsMailBinding.java`; test create
`platform/paper-plugin/src/test/java/…/paper/inbox/InboxEntryRendererTest.java`

```java
public final class InboxEntryRenderer {
    public InboxEntryRenderer(@NotNull NotificationDataTypeRegistry registry, @NotNull Logger logger);
    public @NotNull RenderableNotification render(@NotNull InboxEntry entry, @NotNull UUID viewer);
}
```

Resolve payload class → serializer → renderer, exactly as `NotificationDelivery.dispatch` does. Any
step missing or throwing returns a placeholder:

```java
RenderableNotification.of(
        Component.text("Unreadable notification"),
        Component.text("This notification's type (" + entry.notifPayloadType()
                + ") cannot be displayed. It may come from a module that is no longer installed."));
```

Use whatever factory `RenderableNotification` actually exposes — read the record first.

`EssentialsMailBinding.register` gains, alongside its existing `registerJsonPayload`:

```java
registry.registerRenderer(EssentialsMailPayload.class,
        (payload, target) -> RenderableNotification.of(
                Component.text("Mail"), Component.text(payload.message())));
```

Dispatch precedence is unaffected — the explicit `EssentialsMailProcessor` still wins, so delivery
behaviour does not change. The renderer exists so the inbox has a title and body to show.

- [ ] Write `InboxEntryRendererTest` with three cases: a data type with a registered renderer and
      serializer renders that renderer's title and body; a data type with no registered payload
      mapping returns the placeholder naming the type; a payload whose serializer throws returns the
      placeholder. Build the registry with `new DefaultNotificationDataTypeRegistry()` — check the
      concrete class name in `api` before writing this.
- [ ] `./gradlew :platform:paper-plugin:test --tests "*InboxEntryRendererTest"` — expect FAIL
- [ ] Implement `InboxEntryRenderer` and the Essentials renderer registration.
- [ ] `./gradlew :platform:paper-plugin:test --tests "*InboxEntryRendererTest"` — expect PASS
- [ ] `./gradlew build`
- [ ] Commit

## Task 7: Dialog pagination utilities (`paper.ui`)

**Files:** create `platform/paper-plugin/…/paper/ui/PageBounds.java`, `PagedDialogs.java`,
`DialogSupport.java`; modify `platform/paper-plugin/…/preferences/PreferenceDialogs.java`; test
create `platform/paper-plugin/src/test/java/…/paper/ui/PageBoundsTest.java`

No dialog in this plugin paginates today. This task builds that once, in a package written to be
lifted into `plugin-infrastructure` later.

**The constraint that makes the lift a package rename:** `paper.ui` imports nothing from
`io.github.md5sha256.playernotifications`. Only Paper, Bukkit and Adventure. Check this after
writing — a single notification-shaped import defeats the whole task.

```java
public record PageBounds(int page, int pageSize, int totalEntries) {

    public static final int MAX_PAGE_SIZE = 20;

    public PageBounds {
        pageSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        totalEntries = Math.max(0, totalEntries);
        int totalPages = Math.max(1, (totalEntries + pageSize - 1) / pageSize);
        page = Math.clamp(page, 1, totalPages);
    }

    public int totalPages() { return Math.max(1, (this.totalEntries + this.pageSize - 1) / this.pageSize); }
    public int offset() { return (this.page - 1) * this.pageSize; }
    public boolean hasPrevious() { return this.page > 1; }
    public boolean hasNext() { return this.page < totalPages(); }
    public PageBounds withPage(int page) { return new PageBounds(page, this.pageSize, this.totalEntries); }
    public PageBounds previous() { return withPage(this.page - 1); }
    public PageBounds next() { return withPage(this.page + 1); }
}
```

The compact constructor clamps `pageSize` before deriving `totalPages`, so the page clamp uses the
clamped size — reversing those two lines gives a wrong page bound for an out-of-range `pageSize`.

```java
public final class PagedDialogs {
    public static @NotNull Component pageIndicator(@NotNull PageBounds bounds);
    public static void addNavigationButtons(@NotNull List<ActionButton> buttons,
                                            @NotNull PageBounds bounds,
                                            @NotNull ClickCallback.Options options,
                                            @NotNull IntConsumer openPage);
}
```

`addNavigationButtons` omits Previous on the first page and Next on the last rather than showing them
disabled: a button that does nothing is indistinguishable from a broken callback.

`DialogSupport` absorbs `callbackOptions()`, `onMainThread(...)` and `message(...)` verbatim from
`PreferenceDialogs`, which then delegates to it. `CALLBACK_LIFETIME` moves with them. This is
extraction of code that is already generic and about to have a second caller, not speculation.

- [ ] Write `PageBoundsTest` — no Bukkit types, so it needs no server and no `paper-api` runtime:
      an empty list gives `totalPages() == 1`, `page == 1`, `hasPrevious()` and `hasNext()` both
      false; 7 entries at size 3 gives 3 pages with offsets 0/3/6; `page = 0` and `page = 99` both
      clamp into range; `pageSize = 0` clamps to 1 and `pageSize = 500` to `MAX_PAGE_SIZE`;
      `pageSize = 500` with 10 entries still lands on page 1 of 1 (the ordering trap above);
      a negative `totalEntries` clamps to 0; `next()` on the last page is a no-op and `previous()`
      on the first likewise; `offset()` never exceeds `totalEntries`.
- [ ] `./gradlew :platform:paper-plugin:test --tests "*PageBoundsTest"` — expect FAIL (no such class)
- [ ] Implement `PageBounds`, `PagedDialogs` and `DialogSupport`; repoint `PreferenceDialogs` at
      `DialogSupport` and delete its now-duplicated bodies.
- [ ] `./gradlew :platform:paper-plugin:test --tests "*PageBoundsTest"` — expect PASS
- [ ] Verify the lift constraint: `grep -rn "playernotifications" platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/ui/` should match only the `package` and any
      `paper.ui` self-imports — nothing else.
- [ ] `./gradlew :platform:paper-plugin:test` — the preference dialogs' existing tests must stay green
      after the delegation change
- [ ] `./gradlew build`
- [ ] Commit

## Task 8: Paper UI — dialogs, commands, join line

**Files:** create `platform/paper-plugin/…/paper/inbox/InboxRouter.java`, `InboxDialog.java`,
`InboxDetailDialog.java`; modify `…/command/NotificationsCommand.java`,
`…/JoinDeliveryListener.java`, `…/PlayerNotificationsPlugin.java`, `…/PluginSettings.java`,
`platform/paper-plugin/src/main/resources/settings.yml`

**No automated test.** Dialogs, Brigadier registration and `PlayerJoinEvent` all need a live server —
the same exception the five preference dialog screens already sit under. Every piece of logic worth
testing was deliberately pulled out first: rendering into `InboxEntryRenderer` (Task 6), the paging
arithmetic into `PageBounds` (Task 7), and the query clamping into `DefaultNotificationService.inbox`
(Task 4). What is left here is wiring.

`InboxRouter` mirrors `PreferenceDialogRouter`: it owns both dialogs, a per-player page cursor (a
`Map<UUID, PageBounds>`, dropped on quit by the existing `PreferenceQuitListener` — extend it, or add
a sibling listener), and the async marshalling. It builds each screen's paging from `PageBounds` and
`PagedDialogs.addNavigationButtons`, and takes its callback options from `DialogSupport` — the inbox
writes no paging logic of its own.

`inbox-page-size` is clamped twice on the way in, by `PageBounds` and by
`DefaultNotificationService.inbox`. Leave both: one is a UI helper, the other a public API trust
boundary, and neither should assume the other ran.

`settings.yml` gains:

```yaml
# How many inbox entries are shown per page in /notifications.
# Clamped to 1..20; the dialog cannot usefully show more.
inbox-page-size: 7
```

`PluginSettings` gains `@Setting("inbox-page-size") int inboxPageSize`, clamped in the compact
constructor. A primitive, so deliberately **not** `@Required`, matching `deliver-on-join`.

Command changes in `NotificationsCommand`:
- bare `/notifications` — replace the placeholder notice with `router.openInbox(player, 1)`
- `/notifications list [page]` — chat listing of the given page, numbering rows `1..pageSize`
- `/notifications read <n>` — show entry `n` of the last listed page in chat and mark it seen
- `/notifications dismiss <n>` — `deleteNotificationTarget` for entry `n` of the last listed page

All four are player-only and under the existing `playernotifications.command.preferences`
permission; all dispatch off the main thread.

`JoinDeliveryListener.deliver` additionally reads `unreadCount(player)` and, when non-zero, sends one
line: `You have N unread notifications. Use /notifications to read them.` Send it whether or not
`deliver-on-join` is enabled — a player who turned off push still needs to know something arrived, so
the count must sit outside the existing gate.

- [ ] Implement all of the above.
- [ ] `./gradlew build`
- [ ] Manual verification with `./gradlew :platform:paper-plugin:runServer` against a MariaDB **with
      the V1 schema already applied**, to confirm V2 applies as an upgrade rather than a fresh
      install:
  - [ ] `/notifications test` three times, then `/notifications` — three unread entries, newest first
  - [ ] Click one — detail opens, Back returns, that row now shows as seen
  - [ ] `/notifications` again — the entry is still listed, still seen (it was not consumed)
  - [ ] `SELECT seenTime FROM NotificationTarget` — one row stamped, two null
  - [ ] Set `inbox-page-size: 2`, `/notifications reload`, `/notifications` — two pages, Next and
        Previous both work and show no duplicate entry
  - [ ] Dismiss an entry — it disappears; confirm its `Notification` row is gone
  - [ ] *Mark all read* then *Dismiss all read* — inbox empties, page reads "1 of 1"
  - [ ] `/notifications preferences mute`, `/notifications test`, rejoin — no chat message, but the
        join line reports 1 unread and the entry is in the inbox
  - [ ] Rejoin twice with an unread entry — it is pushed to chat only once
  - [ ] `/notifications list`, `/notifications read 1`, `/notifications delete 1` — the chat
        fallback matches what the dialog shows
- [ ] Commit

## Task 9: Documentation

**Files:** modify `CLAUDE.md`

The superseded `2026-07-31-sticky-notifications.md` plan was already deleted when this plan was
written — it was never implemented and never committed.

- [ ] Update `CLAUDE.md`:
  - "Persistence layer" — the `seenTime` column, V2 in the migration chain, and **replace** the
    "earlier migrations were collapsed into V1 … no data to preserve" note, which is no longer true
  - "Rendering & delivery media" — `MARK_SEEN` replacing `DELETE`, the unseen filter on
    `selectDueByPlayer`, and that fan-out is now "any DELIVERED marks seen" rather than consuming;
    soften the partial-delivery limitation, which is no longer lossy
  - "Player commands" — the bare `/notifications` now opens the inbox rather than reserving the name;
    the new `list`/`read`/`dismiss` subcommands; the three-preference-state table's mute row, since
    `NullSink` is gone and a muted notification is now retained unread rather than consumed
  - "Configuration" — `inbox-page-size`
  - "Player commands" implementation notes — the new `paper.ui` package, what it holds, the rule that
    it imports nothing from this plugin, and that it is a candidate for `plugin-infrastructure` once
    a second consumer exists
  - "Join delivery" — the unread-count line and that it sits outside the `deliver-on-join` gate
  - "Current state" — remove the player-facing inbox from the deferred list and the "delivery is
    destructive under DELETE-wins fan-out, so `resolveNotifications` is not a mailbox" note; mark the
    orphaned-target leak fixed; record what remains unverified (the dialogs, the commands, the join
    line); update the test baseline counts
- [ ] `./gradlew build && ./gradlew test` — confirm the counts written into `CLAUDE.md`, globbing
      `*.xml` not `TEST-*.xml`
- [ ] Commit

---

## Verification

- `./gradlew test` — `:core:test` and `:platform:discord-adapter:test` need a running Docker daemon
  (Testcontainers, `mariadb:11.7`). Count results by globbing `*.xml` under
  `<module>/build/test-results/test/`, not `TEST-*.xml`.
- `./gradlew build` — all modules, including both adapters, which must compile against the renamed
  `NotificationDisposition` constant and the deleted `NullSink`.
- Task 8's manual checklist, run against a database already at V1.
- `grep -rn "playernotifications" platform/paper-plugin/src/main/java/.../paper/ui/` — the `paper.ui`
  package must stay free of this plugin's types if it is to move to `plugin-infrastructure` later.
