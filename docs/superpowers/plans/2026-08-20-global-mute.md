# Global mute Implementation Plan

**Goal:** Make mute a player-level do-not-disturb flag that suppresses every push path while
notifications continue to reach the inbox unread.
**Spec:** `docs/superpowers/specs/2026-08-20-global-mute-design.md`

## Task 1: Store the mute flag

**Files:**
- create `core/src/main/resources/sql/migrations/V3__player_mute.sql`
- create `core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/PlayerMuteMapper.java`
- create `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaPlayerMuteMapper.java`
- create `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerMuteTest.java`
- modify `api/src/main/java/io/github/md5sha256/playernotifications/api/render/NotificationPreferences.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/DatabaseNotificationPreferences.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/SqlSessionWrapper.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaSqlSession.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaDatabase.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaSchemaMigrator.java`
- modify `core/src/test/java/io/github/md5sha256/playernotifications/core/database/SchemaUpgradeTest.java`

**Interfaces this task produces:**

```java
// NotificationPreferences
default boolean isMuted(@NotNull UUID player) { return false; }

// PlayerMuteMapper
int countByPlayer(@NotNull UUID playerUuid);
int insertMute(@NotNull UUID playerUuid, @NotNull Instant mutedTime);
int deleteByPlayer(@NotNull UUID playerUuid);

// SqlSessionWrapper
@NotNull PlayerMuteMapper playerMuteMapper();

// DatabaseNotificationPreferences
@Override public boolean isMuted(@NotNull UUID player);
public void mute(@NotNull UUID player);
public void unmute(@NotNull UUID player);
```

- [ ] Write the failing test `PlayerMuteTest`, extending `AbstractDatabaseTest` like
      `PlayerNotificationPreferenceTest` does:
      - `isMutedIsFalseByDefault` — a fresh UUID is not muted.
      - `muteThenIsMuted` — `mute(player)`, `assertTrue(preferences.isMuted(player))`.
      - `muteIsIdempotent` — call `mute(player)` twice, assert no throw and still muted.
      - `unmuteClearsIt` — `mute` then `unmute`, assert not muted.
      - `unmuteOnUnmutedPlayerIsANoOp` — `unmute` on a fresh UUID, assert no throw and not muted.
      - `mutePreservesPreferenceRows` — `applyChanges(player, Map.of("test", Set.of("chat")), Set.of())`,
        then `mute(player)`, then assert `preferredMedia(player, "test")` is still `Set.of("chat")`
        and `isMuted(player)` is true. This is the "unmuting restores what you had" guarantee.
- [ ] Run `./gradlew :core:test --tests "*PlayerMuteTest"` — expect FAIL: `PlayerMuteTest` does not
      compile (`isMuted`, `mute` and `unmute` do not exist).
- [ ] Implement:
      - `V3__player_mute.sql`, one statement (the migrator splits on `;`):
        ```sql
        CREATE TABLE IF NOT EXISTS PlayerNotificationMute
        (
            playerUuid BINARY(16) NOT NULL PRIMARY KEY,
            mutedTime  DATETIME   NOT NULL
        );
        ```
      - `MariaSchemaMigrator.DEFAULT_MIGRATIONS` gains
        `new MigrationStep(3, "player mute", "V3__player_mute.sql")`.
      - `PlayerMuteMapper` with the three methods above; `MariaPlayerMuteMapper` implementing them
        with `@Select("SELECT COUNT(*) FROM PlayerNotificationMute WHERE playerUuid = #{playerUuid}")`,
        `@Insert("INSERT INTO PlayerNotificationMute (playerUuid, mutedTime) VALUES (#{playerUuid},
        #{mutedTime}) ON DUPLICATE KEY UPDATE mutedTime = VALUES(mutedTime)")` and
        `@Delete("DELETE FROM PlayerNotificationMute WHERE playerUuid = #{playerUuid}")`, each
        parameter `@Param`-annotated as the sibling mappers are.
      - `SqlSessionWrapper.playerMuteMapper()` plus the `MariaSqlSession` implementation, and
        `configuration.addMapper(MariaPlayerMuteMapper.class)` in `MariaDatabase.buildSessionFactory`.
      - `NotificationPreferences.isMuted` as the `default` method above, javadoc'd as the spec shows.
      - `DatabaseNotificationPreferences.isMuted` (`countByPlayer(player) > 0` in an open session),
        `mute` (`insertMute(player, Instant.now())` + commit) and `unmute` (`deleteByPlayer` + commit).
- [ ] Run `./gradlew :core:test --tests "*PlayerMuteTest"` — expect PASS (needs Docker).
- [ ] Extend `SchemaUpgradeTest` to assert `MAX(version) = 3` and that `PlayerNotificationMute`
      exists, then run `./gradlew :core:test --tests "*SchemaUpgradeTest"` — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: Gate delivery on the mute flag

**Files:**
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/NotificationDelivery.java`
- create `core/src/test/java/io/github/md5sha256/playernotifications/core/database/MutedDeliveryTest.java`

**Interfaces:** none new — `deliver(UUID, Instant)` keeps its signature.

- [ ] Write the failing test `MutedDeliveryTest`, modelled on `RenderedDeliveryTest` (same recording
      sink, same `registerJsonRenderable` wiring, same `NotificationDelivery` 5-arg constructor over
      `DatabaseNotificationPreferences`):
      - `mutedPlayerReceivesNothingButKeepsTheNotification` — enqueue a renderable notification for a
        muted player, run `deliver(player, now)`, then assert the recording sink recorded zero
        deliveries, `service.unreadCount(player)` is 1, and `service.inbox(player, 1, 10)` still lists
        the entry with `unread()` true.
      - `unmutingRestoresDelivery` — the same setup, then `preferences.unmute(player)` and
        `deliver(player, now)` again; assert the sink recorded exactly one delivery and the entry is
        no longer unread.
      - `mutedPlayerAlsoBypassesAnExplicitProcessor` — register a processor for a second data type
        that records every call and returns `MARK_SEEN`; enqueue one of those for the muted player,
        deliver, assert the processor was never invoked and the target's `seenTime` is still null.
        This is the case the old `RenderingProcessor`-level filter could not cover.
- [ ] Run `./gradlew :core:test --tests "*MutedDeliveryTest"` — expect FAIL: all three fail, because
      `deliver` currently dispatches regardless of the mute.
- [ ] Implement: at the top of `NotificationDelivery.deliver(UUID target, Instant now)`, before the
      due query:
      ```java
      if (this.preferences != null && this.preferences.isMuted(target)) {
          this.logger.fine(() -> "Player " + target + " is muted; skipping delivery");
          return;
      }
      ```
      and extend the class javadoc's dispatch-precedence paragraph to say the mute gate precedes it.
- [ ] Run `./gradlew :core:test --tests "*MutedDeliveryTest"` — expect PASS.
- [ ] Run `./gradlew :core:test` — expect PASS, 100 existing tests plus the new ones.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: Gate the Paper push paths

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailNotifier.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/JoinDeliveryListener.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/diagnostic/TestNotificationSender.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailNotifierTest.java`
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/JoinDeliveryListenerTest.java`

**Interfaces this task produces:**

```java
// JoinDeliveryListener — new constructor parameter, and a testable announcement seam
public JoinDeliveryListener(@NotNull Plugin plugin,
                            @NotNull Supplier<NotificationDelivery> delivery,
                            @NotNull NotificationService service,
                            @NotNull NotificationPreferences preferences,
                            boolean enabled,
                            long delaySeconds);
@NotNull List<Component> announcements(@NotNull UUID playerId);

// MailNotifier — unchanged signature; it already holds the NotificationPreferences it needs
```

- [ ] Write the failing tests:
      - `MailNotifierTest.mutedRecipientGetsNoNotice` — a `NotificationPreferences` stub whose
        `isMuted` returns true and whose `preferredMedia` returns `Set.of("chat")`; call
        `notifyArrival(recipient)`; assert the recording sink recorded zero deliveries.
      - `JoinDeliveryListenerTest.announcementsAreEmptyWhenMuted` — a service stub reporting
        `unreadCount(player) == 3` and `unreadCount(player, "mail") == 1`, preferences stub muted;
        assert `announcements(player)` is empty.
      - `JoinDeliveryListenerTest.announcementsCarryBothLinesWhenUnmuted` — the same stubs unmuted;
        assert `announcements(player)` has two components.
      - `JoinDeliveryListenerTest.announcementsAreEmptyWithNothingUnread` — unmuted, both counts zero;
        assert empty.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailNotifierTest" --tests "*JoinDeliveryListenerTest"`
      — expect FAIL: `announcements` does not exist, the `JoinDeliveryListener` constructor takes no
      preferences, and `MailNotifier` ignores the mute.
- [ ] Implement:
      - `MailNotifier.notifyArrival` returns early, logging at `fine`, when
        `this.preferences.isMuted(recipient)`.
      - `JoinDeliveryListener` takes `NotificationPreferences`; `announcements(UUID)` returns the
        unread-count line and the mail reminder line, or an empty list when
        `preferences.isMuted(playerId)`; `mailReminder` folds into it; `announceUnread(Player)` sends
        each component from `announcements`, re-checking `isOnline()` as it does today.
      - `TestNotificationSender.report` gains a first branch: when `this.preferences.isMuted(target)`,
        reply `"Your notifications are muted, so nothing was sent."` followed by a line naming
        `/notifications unmute`.
      - `PlayerNotificationsPlugin` passes the `DatabaseNotificationPreferences` into the
        `JoinDeliveryListener` constructor.
- [ ] Run the same test command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 4: Command and dialog surface

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSession.java`
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/preferences/session/PreferenceEditSessionTest.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/DatabaseNotificationPreferences.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogRouter.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceDialogs.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/PreferenceRootDialog.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/preferences/MuteConfirmDialog.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/NotificationsCommand.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`
- modify `core/src/test/java/io/github/md5sha256/playernotifications/core/database/PlayerNotificationPreferenceTest.java` (delete the `muteAll` cases)

**Interfaces this task produces:**

```java
// PreferenceEditSession
public PreferenceEditSession(@NotNull UUID player,
                             @NotNull Map<String, Set<String>> initialEffectiveMedia,
                             boolean initiallyMuted,
                             @NotNull Instant now);
public boolean muted();
public void setMuted(boolean muted, @NotNull Instant now);
public @Nullable Boolean stagedMuteChange();

// DatabaseNotificationPreferences
public void applyChanges(@NotNull UUID player,
                         @NotNull Map<String, Set<String>> explicitMedia,
                         @NotNull Set<String> dataTypesToReset,
                         @Nullable Boolean muted);

// PreferenceDialogRouter
public void muteImmediately(@NotNull Player player);
public void unmuteImmediately(@NotNull Player player);
```

- [ ] Write the failing tests in `PreferenceEditSessionTest`:
      - `mutedSeedsFromTheConstructor` — a session built with `initiallyMuted = true` reports
        `muted()` true, `stagedMuteChange()` null, and `isDirty()` false.
      - `stagingAMuteMarksTheSessionDirty` — `setMuted(true, now)` on a session seeded unmuted gives
        `muted()` true, `stagedMuteChange()` `Boolean.TRUE`, `isDirty()` true and `dirtyCount()` 1.
      - `stagingAMuteLeavesTheMatrixAlone` — after `setMuted(true, now)`, `explicitChanges()` is empty
        and `mediaFor("test")` still returns the seeded set.
      - `stagingAnUnmuteIsStagedToo` — a session seeded muted, `setMuted(false, now)`, gives
        `stagedMuteChange()` `Boolean.FALSE`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*PreferenceEditSessionTest"` — expect FAIL:
      the four-argument constructor and the three mute methods do not exist.
- [ ] Implement:
      - `PreferenceEditSession` as above: a `boolean muted` field, a `Boolean stagedMute` field
        (`null` until touched), `dirtyCount()` returning `dirtyDataTypes.size() + (stagedMute == null
        ? 0 : 1)` and `isDirty()` returning `dirtyCount() > 0`.
      - `DatabaseNotificationPreferences.applyChanges(player, explicitMedia, dataTypesToReset, muted)`
        doing the existing work plus, when `muted != null`, an `insertMute`/`deleteByPlayer` on the
        same session before the commit. The three-argument form delegates with `null`.
      - `DatabaseNotificationPreferences.muteAll` deleted, and its cases removed from
        `PlayerNotificationPreferenceTest`.
      - `PreferenceDialogRouter.muteImmediately` calls `preferences.mute(uuid)` instead of `muteAll`,
        keeping its existing session-drop, error handling and "Any unsaved preference changes were
        discarded" message. `unmuteImmediately` is its mirror over `preferences.unmute(uuid)`, with
        the message "Notifications unmuted."
      - `PreferenceDialogRouter.apply` passes `session.stagedMuteChange()` as the fourth argument.
      - `PreferenceDialogs.withSession` (the one place a session is constructed, at
        `PreferenceDialogs.java:271`) seeds `initiallyMuted` from `preferences.isMuted(uuid)` on the
        same async hop as `effectiveMediaByDataType`. The existing three-argument constructor is kept,
        delegating with `false`, so `PreferenceDialogsTest` and `PreferenceSessionManagerTest` do not
        change.
      - `PreferenceRootDialog`'s button label and `MuteConfirmDialog`'s title, intro and Apply
        direction read `session.muted()`: muted shows "Unmute everything" and stages
        `setMuted(false, now)`, unmuted shows "Mute everything" and stages `setMuted(true, now)`.
      - `NotificationsCommand` adds `Commands.literal("unmute").executes(context -> run(context,
        router::unmuteImmediately))` both under `preferences` and at the top level, mirroring the two
        existing `mute` registrations, and its class javadoc gains a sentence naming the pair.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*PreferenceEditSessionTest"` — expect PASS.
- [ ] Run `./gradlew test` — expect PASS across all four modules.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 5: Documentation and manual verification

**Files:** modify `CLAUDE.md`; modify this plan (tick the checklist as it is run).

- [ ] Update `CLAUDE.md`:
      - "Player commands" — the three-preference-states table gains the global mute as a separate,
        orthogonal switch; `/notifications unmute` is added to the command list; the paragraph
        claiming a muted notification is retained by `RenderingProcessor` is corrected to say the gate
        now lives in `NotificationDelivery.deliver`; the "known quirk" bullet notes that a bespoke
        processor no longer escapes a global mute (it still escapes per-type preferences).
      - "Rendering & delivery media" — the `MUTED_MEDIUM` bullet is scoped to the per-type mute.
      - "Join delivery" — the announcement lines are now suppressed by a global mute, and the listener
        takes a `NotificationPreferences`.
      - "Mail" — `MailNotifier` drops the notice for a muted recipient.
      - "Persistence layer" — the `PlayerNotificationMute` table and the V3 migration step.
      - "Testing gotchas" — the new baseline test counts, taken from the actual run.
- [ ] Run `./gradlew build` and record the real per-module test counts (glob `*.xml`, not `TEST-*.xml`).
- [ ] Run `./gradlew :platform:paper-plugin:runServer` and work the manual checklist below.
- [ ] Commit

### Manual checklist (needs a live server — not yet run)

1. `/notifications mute` replies "All notifications muted."
2. `/notifications test hello` replies that notifications are muted and nothing was sent, and names
   `/notifications unmute`. No chat message and no dialog appears.
3. `/notifications` still opens the inbox, and the test notification from step 2 is listed **unread**.
4. From a second account, `/mail send <you> hi` — no "You have new mail!" line arrives.
5. `/mail` lists the mail, unread.
6. Quit and rejoin — no unread-count line and no mail reminder line appear.
7. `/notifications unmute` replies "Notifications unmuted."
8. Quit and rejoin — the unread-count line and the mail reminder both appear, and the notification
   from step 2 is delivered to chat.
9. `/notifications preferences` — the root screen's button reads "Mute everything"; open it, press
   Apply, confirm the reply and that `/notifications preferences` now shows "Unmute everything".
10. Open the mute screen again and press **Back** — nothing changes; the mute state is as it was.
11. In the medium editor, untick every medium for one type and Apply; confirm that type alone stops
    being delivered while others still are (the per-type mute is untouched by this change).
12. `/notifications preferences unmute` and `/notifications unmute` both clear the flag.
