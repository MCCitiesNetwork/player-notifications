# Player-Operable Commands — Design

**Date:** 2026-07-28
**Status:** Approved and implemented

## Problem

The renderer/sink architecture (`2026-07-27-notification-renderers-design.md`) made a player's delivery
media a stored, set-valued preference — and then explicitly deferred any way for a player to change it:

> **A player-facing command or GUI to change preferences.** Preferences are API- and admin-set for now.

So `PlayerNotificationPreference` rows exist, `DatabaseNotificationPreferences` can read and write them,
and nothing a player can type or click reaches either. This design closes exactly that gap.

It also resolves a semantic hole the preferences table has carried since it was introduced: **zero rows
means "unconfigured"**, so there has been no way to express "I want to be notified in zero ways". Once
players can edit their own preferences, unchecking every box is an obvious thing to do, and it needs a
defined meaning.

## Decision summary

- **Scope is preferences only.** No inbox listing, no player-initiated clear, no admin commands.
- **One command, `/notifications`** (alias `/notifs`), which opens a **Paper Dialog** rather than
  exposing text subcommands. Registered through Paper's **Brigadier** API — the only path available,
  since this plugin ships `paper-plugin.yml`, which has no `commands:` block.
- **Three preference states**, all expressible from the dialog: unconfigured, explicit selection, and
  explicit mute.
- **An explicit mute is a real mute.** Saving with nothing checked stores the single medium `"none"`,
  and a registered `NullSink` returns `DELIVERED` for it, so muted notifications are **consumed**.
- **Media label themselves.** `NotificationSink` gains `default` `displayName()` / `description()`
  methods, keeping naming with the medium owner and preserving source compatibility.

### Rejected alternatives

- **Text subcommands** (`/notifications add chat`, `/notifications remove dialog`, `/notifications list`).
  Cheaper to build and trivially testable without a server, but it makes a *set* editable only one
  element at a time, and players must already know the medium keys. A checkbox list shows the available
  media, the current selection, and the result of a change in one screen.
- **Storing a mute as zero rows.** The natural reading of "unchecked everything", but zero rows already
  means "unconfigured → fall back to `default-media`", so the two states would be indistinguishable and
  a mute would silently un-mute itself.
- **Special-casing the mute inside `RenderingProcessor`** (e.g. a `preferredMedia().isEmpty()` branch
  that returns `DELETE`). Requires a schema change to distinguish the two zero-row cases *and* puts a
  policy branch in the delivery hot path. The `"none"` sink gets the same behaviour by reusing the
  existing fan-out unchanged, with no migration.
- **A `userSelectable()` flag on `NotificationSink`** to hide `"none"` from the dialog. More general
  than needed for one sink; the dialog filters on `NullSink.MEDIUM_KEY` instead. Worth revisiting if a
  second internal-only medium ever appears.
- **Per-medium `/notifications toggle <medium>`** as a shorthand alongside the dialog. Two ways to do
  one thing, with the toggle unable to express the mute/default distinction. Dropped by YAGNI.

## The three preference states

| State | Storage | `preferredMedia()` returns | Reached by |
|---|---|---|---|
| Unconfigured | zero rows | `default-media` from `settings.yml` | never having saved; **Use server default** |
| Explicit selection | one row per medium | that set | **Save** with ≥1 box checked |
| Explicit mute | a single `medium = 'none'` row | `{"none"}` | **Save** with nothing checked |

The third button matters: without **Use server default**, a player who saved anything once could never
return to tracking the server's configured default, because every subsequent save writes an explicit
set. All three states are reachable and re-reachable from the dialog.

`DatabaseNotificationPreferences.setPreferredMedia` already implements all three — an empty set deletes
the player's rows, a non-empty set replaces them — so **no change to the persistence layer was needed**,
and no migration.

## Components

### 1. `NullSink` — `api.render.sink`, new

```java
public final class NullSink implements NotificationSink {
    public static final String MEDIUM_KEY = "none";

    @Override public @NotNull DeliveryResult deliver(RenderableNotification n, UUID target) {
        return DeliveryResult.DELIVERED;
    }
}
```

Registered by `PlayerNotificationsPlugin` alongside `ChatSink` and `DialogSink`.

Returning `DELIVERED` is the whole design. It makes the fan-out fold to `DELETE`, so a muted player's
notifications are discarded at delivery time rather than accumulating in the database until
`notifExpiryTime`. **A mute means "do not tell me", not "queue this for later"** — this is deliberately
the opposite of the renderer design's *Known limitations* case, where a player whose only medium is
permanently `UNSUPPORTED` silently accrues notifications.

Excluded from the dialog's checkbox list: checking nothing already says the same thing, and offering
both would be two ways to express one state.

### 2. `NotificationSink.displayName()` / `description()` — `api.render`, additive

```java
default @NotNull Component displayName() {
    return Component.text(titleCase(mediumKey()));   // "essentials-mail" → "Essentials Mail"
}

default @NotNull Component description() {
    return Component.empty();                        // no tooltip
}
```

`default` methods, so `ChatSink`, `DialogSink`, and any third-party sink compile unchanged. Naming stays
with the medium owner, consistent with the N + M ownership model: whoever writes the sink names it. The
title-cased fallback means a sink author who does not care still gets a presentable label.

### 3. `NotificationsCommand` — `platform:paper-plugin`, package `paper.command`

```java
Commands.literal("notifications")
        .requires(source -> source.getSender().hasPermission(PERMISSION))
        .executes(context -> openPreferences(context, dialog))
        .then(Commands.literal("preferences")
                .executes(context -> openPreferences(context, dialog)))
        .build();
```

Registered from `PlayerNotificationsPlugin.registerCommands()` via
`getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, …)` with the alias `notifs`.

Permission `playernotifications.command.preferences`, declared in `paper-plugin.yml` with
`default: true`. Player-only; console gets a message rather than a stack trace.

The bare root and the `preferences` literal do the same thing. The literal exists so a future `inbox`
or `history` subcommand can be added without changing what players already type.

### 4. `NotificationPreferencesDialog` — `platform:paper-plugin`, package `paper.preferences`

A `DialogType.multiAction` with one `BooleanDialogInput` per user-selectable medium:

```
Notification Preferences
Choose how you want to receive notifications. Saving with nothing selected mutes them.

  [x] Chat
  [ ] Dialog
  [ ] Essentials Mail

  [ Save ]   [ Use server default ]        (exit) [ Cancel ]
```

- Checkbox rows come from `sinkRegistry.registeredMedia()` minus `NullSink.MEDIUM_KEY`, sorted so row
  order is stable between openings.
- Initial checked state comes from `preferences.preferredMedia(uuid)`. A player who has never configured
  anything therefore sees `default-media` pre-checked, which is honest: that *is* what they currently
  get.
- **Save** and **Use server default** are `DialogAction.customClick` server callbacks; **Cancel** is the
  `exitAction` with no action attached, since closing without writing is exactly what cancelling means.
- `afterAction` is `CLOSE`.

## Data flow

**Opening** (`/notifications` → dialog):

1. Command executor, main thread. If no selectable media are registered, message the player and stop.
2. `runTaskAsynchronously` → `preferences.preferredMedia(uuid)` (blocking JDBC).
3. `runTask` → re-check `player.isOnline()`, build the dialog, `player.showDialog(…)`.

**Saving** (click → row change):

1. `DialogActionCallback` fires on the main thread with a `DialogResponseView`.
2. Read each checkbox by input key; `getBoolean` returning `null` counts as unchecked.
3. Re-validate against `sinkRegistry.registeredMedia()` **as it stands now**.
4. Empty selection → `{"none"}`; otherwise the selection.
5. `runTaskAsynchronously` → `setPreferredMedia`, then message the player back on the main thread.

Neither path blocks the main thread on JDBC, and neither touches Bukkit state off it.

## Error handling

- **Blocking I/O on the main thread** — the failure this design most needs to avoid. Both DB calls are
  pushed to the async scheduler; only `showDialog` and `sendMessage` run on the main thread.
- **Player disconnects mid-flow** — `isOnline()` is re-checked before both `showDialog` and the feedback
  message. A save already in flight still completes; it is keyed by UUID, not by the `Player` object.
- **Stale dialog.** The dialog is a snapshot of the registry. On save the selection is re-validated
  against the current `registeredMedia()`, so a medium unregistered while the dialog sat open is
  dropped rather than persisted as an unroutable preference.
- **Input-key collisions.** Input keys are **positional** (`medium_0`, `medium_1`, …) with a
  key → medium map, not derived from the medium key. Medium keys are arbitrary strings, and any
  sanitizing transform (`-` → `_`) could collapse two distinct media onto one input key and silently
  drop a checkbox.
- **Write failure.** `setPreferredMedia` throwing is caught, logged at `warning`, and reported to the
  player as a failure. Silently swallowing it would leave the player believing a mute took effect.
- **Callback expiry.** Buttons use `ClickCallback.Options` with `uses(1)` and a one-hour lifetime. A
  dialog left open past that has inert buttons; the player reopens it. Accepted rather than engineered
  around.
- **No media registered at all** — messaged, not an empty dialog.

## Testing

- `NullSinkTest` (`:api:test`): `deliver` returns `DELIVERED`; a target preferring only `"none"` folds
  to `DELETE` through a real `RenderingProcessor`; `displayName()` title-casing for `chat`,
  `essentials-mail`, and `web_push_alert`; an overriding sink keeps its own name.
- The dialog and command are **not** automatically tested. They need a live server, the same standing
  limitation as `ChatSink` and `DialogSink`. Verify by hand with `:platform:paper-plugin:runServer`:
  open the dialog, save a selection, reopen and confirm it round-trips, save with nothing checked and
  confirm notifications stop arriving, then **Use server default** and confirm the default returns.
- No `core` change, so the `:core:test` baseline is untouched.

Baseline after this change: **12 tests in `:api:test`** (was 8), 50 in `:core:test`.

## Known limitations

- **Bespoke processors bypass preferences entirely.** Under the dispatch-precedence rule, an explicitly
  registered `NotificationProcessor` wins over the rendering path, so a muted player **still receives
  `EssentialsMailProcessor` mail**. This is pre-existing, not introduced here; the fix is converting
  those processors into sinks, which the renderer design already lists as deferred.
- **The mute is all-or-nothing.** There is no per-payload-type or per-category muting, and no
  "do not disturb until <time>". A player either receives notifications over their chosen media or
  receives none.
- **No confirmation on mute.** Saving with nothing checked mutes immediately, with a warning-coloured
  message after the fact. The dialog body states the consequence up front; a confirmation step was
  judged not worth the extra click for a reversible setting.
- **Preference changes do not replay.** Notifications consumed by `NullSink` while muted are gone;
  un-muting does not bring them back.

## Out of scope

- **An inbox** — listing, reading, or clearing stored notifications from in-game.
- **Admin commands** — inspecting or setting another player's preferences, or enqueuing a notification
  by hand. Those remain API-only.
- **A join listener.** `NotificationDelivery.deliver(UUID)` still has no caller; wiring delivery to a
  trigger is a separate piece of bootstrap work.
- **Per-medium delivery tracking**, the **Discord sink and account linking**, and **actions/buttons** in
  `RenderableNotification` — all still deferred to their own designs.
