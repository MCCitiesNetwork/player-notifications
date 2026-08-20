# Global mute (do not disturb) — design

**Date:** 2026-08-20
**Status:** approved, not yet implemented

## Goal

Mute should mean *"do not interrupt me"*, and nothing more. A muted player still accrues
notifications — they land in the database, stay unread, and are readable in `/notifications` and
`/mail` whenever the player chooses to look. What mute stops is every unsolicited push: sink
delivery, the mail arrival notice, and the join-time announcement lines.

Today mute is a value *inside* the per-`dataType` media set (`NotificationPreferences.MUTED_MEDIUM`,
the reserved key `"none"`). That shape has three consequences this design removes:

1. Only `RenderingProcessor` honours it, because only `RenderingProcessor` reads that set. A payload
   with a bespoke `NotificationProcessor` bypasses preferences entirely (the "known quirk" in
   `CLAUDE.md`), so a muted player still receives it.
2. `/notifications mute` **rewrites** every known `dataType`'s rows to `{none}`, destroying the
   player's real choices. There is no unmute, because there is nothing left to restore.
3. Each new push path has to remember to filter `MUTED_MEDIUM` out by hand — `MailNotifier` does;
   `JoinDeliveryListener` does not.

## Architecture

### A new axis: a player-level mute flag

Mute becomes a single boolean per player, orthogonal to the `dataType` × medium matrix. It is a
*preference*, so it goes on `NotificationPreferences`:

```java
/**
 * Whether the player has muted every notification. A muted player is still enqueued to, and still
 * reads their notifications through the inbox; nothing is pushed to them.
 */
default boolean isMuted(@NotNull UUID player) {
    return false;
}
```

A `default` method: `api` is compiled against by feature modules built separately, and every
existing implementation (including the lambdas in tests) keeps compiling.

Mutation stays off the read interface, exactly as `muteAll`/`resetAll` already do — it lands on
`DatabaseNotificationPreferences` as `mute(UUID)` / `unmute(UUID)`.

**Per-`dataType` mute is unchanged.** `MUTED_MEDIUM` stays, the medium editor can still empty a
single type to `{none}`, and `RenderingProcessor` still filters it. A player can silence one type
without going dark, and unmuting the global flag restores exactly the matrix they had, because the
global flag never touches a preference row.

### Storage: `PlayerNotificationMute`

Migration `V3__player_mute.sql`, layered (not folded into V1 — V2 established that rule):

```sql
CREATE TABLE IF NOT EXISTS PlayerNotificationMute
(
    playerUuid BINARY(16) NOT NULL PRIMARY KEY,
    mutedTime  DATETIME   NOT NULL
);
```

Presence of the row is the mute; `mutedTime` is not read by any query, and exists so an operator
inspecting the table can tell when a mute was set, and so a future timed mute has somewhere to
anchor without another migration.

A separate table rather than reusing a `dataType = '*'`, `medium = 'none'` preference row: that row
already means "blanket fallback for any type not otherwise configured" and participates in
`preferredMedia`'s resolution chain. Overloading it would make "unmute" indistinguishable from
"clear my blanket preference", and would put the mute inside the very set this design is pulling it
out of.

**No data conversion.** The plugin has no deployed data, so V3 creates the table and nothing else.
Rows written by the old `/notifications mute` (a `{none}` row per type plus the `*` blanket) stay
valid per-type mutes and remain clearable through the preference editors.

### Enforcement: one check, three push paths

| Site | Change |
|---|---|
| `NotificationDelivery.deliver(UUID, Instant)` | returns immediately when the target is muted — no dispatch, no `seenTime` write |
| `MailNotifier.notifyArrival(UUID)` | returns early when the recipient is muted |
| `JoinDeliveryListener` announcement lines | neither the unread-count line nor the mail reminder is sent when the player is muted |

Placing the delivery check in `NotificationDelivery.deliver` rather than in `RenderingProcessor` is
the substance of this design. `deliver` is the single funnel every stored notification passes
through, so the check applies uniformly to the renderer path *and* to bespoke processors, which is
the one thing the current shape cannot do. It also means a muted player's notifications are never
decoded or rendered at all, which is cheaper as well as more correct.

The check is `this.preferences != null && this.preferences.isMuted(target)`. The three-argument
`NotificationDelivery` constructor supplies no preferences (it is the no-rendering-path form used by
tests and by a host that registered no sinks); mute does not apply there, and cannot, because there
is nothing to ask.

`JoinDeliveryListener` gains a `NotificationPreferences` constructor argument — it has none today.
The announcement lines move behind a package-private `announcements(UUID) : List<Component>` seam so
the mute gate is unit-testable without a live `Player`, the same device `mailReminder(UUID)` already
uses.

`RenderingProcessor` is **not** changed. Its `MUTED_MEDIUM` filter still serves the per-type mute,
which still exists.

### Commands and UI

- `/notifications mute` sets the flag. It no longer rewrites any preference row.
- `/notifications unmute` clears it — new, and mirrored at `/notifications preferences unmute`.
  Same permission (`playernotifications.command.preferences`), player-only, dispatched off the main
  thread, like every sibling.
- `PreferenceRootDialog`'s button reads **Mute everything** or **Unmute everything** from the
  session's current state, and routes to `MuteConfirmDialog` either way; that screen's intro text and
  Apply direction follow the same state.
- `MuteConfirmDialog`'s Apply stages the flag rather than writing `{none}` across every type. Back
  still changes nothing.
- `DatabaseNotificationPreferences.muteAll` loses its last caller and is deleted, along with its
  test. `resetAll` and `explicitlyConfiguredDataTypes` stay as they are — already uncalled, already
  documented as kept for a future admin command.

`PreferenceEditSession` carries the mute as staged state alongside the matrix: a `boolean muted`
seeded at load, `setMuted(boolean, Instant)` marking a separate dirty flag, and
`stagedMuteChange() : @Nullable Boolean` returning `null` when untouched. `isDirty()`/`dirtyCount()`
count it, so the staged-summary line and the pickers' dirty-gated Apply behave for a pending mute
exactly as they do for a pending matrix edit.

`DatabaseNotificationPreferences.applyChanges` gains a four-argument overload taking that
`@Nullable Boolean`, so a staged mute and staged matrix edits commit in one transaction. The
three-argument form delegates with `null`, so no existing caller changes.

### `/notifications test`

`deliver` now no-ops for a muted player, so `TestNotificationSender.report` checks `isMuted` first
and replies that notifications are muted and nothing was sent, pointing at `/notifications unmute`.
Without this the reply would list the media it "attempted" and be simply wrong. The existing
per-type `MUTED_MEDIUM` branch stays underneath it — the two are different states and each deserves
its own sentence.

The notification is still enqueued before the muted `deliver` returns, so it remains in the inbox.
That is correct: `/notifications test` exercises the whole path, and "it reached my inbox but was
not pushed" is exactly the thing being tested.

## Files touched

**Create**
- `core/src/main/resources/sql/migrations/V3__player_mute.sql`
- `core/src/main/java/.../core/database/mapper/PlayerMuteMapper.java`
- `core/src/main/java/.../core/database/maria/mapper/MariaPlayerMuteMapper.java`
- `core/src/test/java/.../core/database/PlayerMuteTest.java`
- `core/src/test/java/.../core/database/MutedDeliveryTest.java`

**Modify**
- `api/.../api/render/NotificationPreferences.java` — `isMuted` default method
- `core/.../core/DatabaseNotificationPreferences.java` — `isMuted`, `mute`, `unmute`, the
  four-argument `applyChanges`; delete `muteAll`
- `core/.../core/NotificationDelivery.java` — the mute gate in `deliver(UUID, Instant)`
- `core/.../core/database/SqlSessionWrapper.java`, `.../maria/MariaSqlSession.java`,
  `.../maria/MariaDatabase.java`, `.../maria/MariaSchemaMigrator.java` — the new mapper and step
- `platform/paper-plugin/.../paper/JoinDeliveryListener.java` — preferences argument, `announcements`
- `platform/paper-plugin/.../paper/mail/MailNotifier.java` — mute gate
- `platform/paper-plugin/.../paper/diagnostic/TestNotificationSender.java` — muted reply
- `platform/paper-plugin/.../paper/command/NotificationsCommand.java` — `unmute` nodes
- `platform/paper-plugin/.../paper/preferences/PreferenceDialogRouter.java` — `muteImmediately`
  rewritten, `unmuteImmediately` added
- `platform/paper-plugin/.../paper/preferences/PreferenceRootDialog.java`,
  `MuteConfirmDialog.java`, `PreferenceDialogs.java` — state-dependent labels, staged mute
- `platform/paper-plugin/.../paper/preferences/session/PreferenceEditSession.java` — staged mute
- `platform/paper-plugin/.../paper/PlayerNotificationsPlugin.java` — wiring
- `CLAUDE.md`

`paper-plugin.yml` is untouched: `unmute` reuses the existing
`playernotifications.command.preferences` permission.

## Error handling

- A database failure while reading `isMuted` propagates as a `RuntimeException` out of `deliver`,
  where `JoinDeliveryListener` already logs and swallows it and `TestNotificationSender` already
  reports it to the player. No new handling.
- A failure while writing a mute is caught in `PreferenceDialogRouter` exactly as `muteAll`'s was,
  leaving the player a "please try again" message and the session intact.
- `mute(UUID)` is idempotent (`INSERT ... ON DUPLICATE KEY UPDATE mutedTime = VALUES(mutedTime)`), so
  muting twice refreshes the timestamp rather than throwing. `unmute` on an unmuted player deletes
  zero rows and reports success — a player pressing unmute wants to end up unmuted, not to be told
  they already were.

## Testing strategy

Against a real MariaDB (Testcontainers, as `core` already does):

- `PlayerMuteTest` — `isMuted` false by default, true after `mute`, false after `unmute`; `mute`
  twice does not throw; `mute` leaves the player's `PlayerNotificationPreference` rows untouched and
  `preferredMedia` unchanged, which is the "unmuting restores what you had" guarantee.
- `MutedDeliveryTest` — the central claim. A renderable notification is enqueued for a muted player,
  `deliver` runs, and: the recording sink received nothing, the target's `seenTime` is still null,
  and the notification is still present in `inbox(player, 1, 10)`. Unmute, deliver again, and the
  sink receives it.
- `SchemaUpgradeTest` — extended to assert `MAX(version) = 3` and that `PlayerNotificationMute`
  exists.

Hermetic:

- `MailNotifierTest` — a muted recipient gets no notice, with the sink asserting zero deliveries.
- `JoinDeliveryListenerTest` — `announcements(UUID)` is empty for a muted player and non-empty for an
  unmuted one with unread entries.
- `PreferenceEditSessionTest` — staging a mute marks the session dirty, `stagedMuteChange()` is
  `null` until touched, and staging does not disturb the matrix.

Unverifiable without a live server, and therefore on a manual checklist: the Brigadier `unmute`
nodes, the root dialog's state-dependent label, and the `MuteConfirmDialog` Apply path.

## Known limitations

- **A muted player is told nothing at all.** No arrival notice, no join-time unread count. This is
  the explicitly chosen meaning of mute here — silence means silence — but it means the only way a
  muted player learns anything arrived is by opening `/notifications` or `/mail` themselves. If that
  proves too silent in practice, the join-time count line is the one to reinstate first: it is a
  single line at a moment the player is not mid-flow. Revisit if players report missing mail.
- **Mute is not timed.** It stays until the player unmutes. `mutedTime` gives a timed mute somewhere
  to land later without a migration, but no expiry is read today.
- **Mute is all-or-nothing across `dataType`s.** A player who wants silence from one type only uses
  the per-type mute, which is a different control in a different screen. If players conflate the two,
  the fix is UI wording, not a third state.
- **No admin surface.** Nothing lets an operator see or clear another player's mute; a stuck mute
  needs a manual `DELETE` against `PlayerNotificationMute`. Same posture as the Discord link table.
- **Muted delivery is skipped, not deferred.** Unmuting does not push everything that arrived while
  muted; those notifications simply remain unread in the inbox, and only a future join (or
  `/notifications test`) drives delivery again. Deliberate: a burst of back-delivery on unmute would
  be precisely the interruption the player was avoiding.
