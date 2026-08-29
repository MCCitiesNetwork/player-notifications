# Join-Delivery Trigger — Design

**Date:** 2026-07-30
**Status:** proposed

## Goal

Deliver a player's due notifications when they join the server, and let an operator turn that
behaviour off from `settings.yml` without a restart.

Before this change nothing in `platform/` triggered delivery except `/notifications test`, so a
notification enqueued for an offline player was never delivered at all — it sat until the prune task
reaped it at expiry. This closes that gap, which `CLAUDE.md`'s "Current state" names as the open
bootstrap step.

## Why this is not a registry extension

The plugin's three registries (`NotificationDataTypeRegistry`, `NotificationSinkRegistry`,
`NotificationCategoryRegistry`) exist so that adding a *payload*, a *medium*, or a *grouping* is a
registration rather than a branch. A join trigger is none of those: it is a *cause* for running the
existing delivery loop, the same kind of thing as the async prune task. Causes are host-plugin
bootstrap concerns and live in `platform:paper-plugin`.

Two placements were rejected:

- **A toggle inside `NotificationDelivery` (`core`).** `core` has no Bukkit event surface and no
  notion of "join", and a flag there would also gate `/notifications test`, which must keep working
  regardless — the whole point of the test command is to exercise delivery on a server whose join
  trigger may be off.
- **Registering the listener conditionally.** Then `/notifications reload` flipping the toggle would
  have to unregister via `HandlerList`, or the flip would need a restart. An always-registered
  listener that returns early costs one field read per join and makes reload trivial.

## Architecture

### Config

`settings.yml` (`PluginSettings`, in `platform:paper-plugin`) gains two keys:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `deliver-on-join` | `boolean` | `true` | Whether joining triggers delivery of that player's due notifications. |
| `join-delivery-delay-seconds` | `long` | `3` | How long after the join event delivery runs. |

Both are primitives, so neither takes `@Required` — the repo rule ("every non-null, reference-typed
`@Setting` must be `@Required`") exists to stop a missing key deserializing to `null`, which a
primitive cannot do. A missing `deliver-on-join` therefore reads `false`, and a missing
`join-delivery-delay-seconds` reads `0`. That is acceptable because both keys are written into every
data folder by the existing copy-defaults-then-merge path, including on upgrade — an operator never
sees an install without them. `pruneIntervalSeconds` already relies on the same property.

The delay is `long` seconds, not a `java.time.Duration`: Configurate 4.2.0 ships no `Duration`
serializer (`node.get(Duration.class)` returns `null`). Negative values are clamped to `0` in the
record's compact constructor, alongside the existing prune-interval clamp. `0` is a legitimate
setting meaning "immediately, on the next async tick" — unlike `prune-interval-seconds`, where `0`
would mean an unbounded task loop and so falls back to a default.

Default `true`: with no join trigger there is no delivery path at all outside the admin test command,
so shipping the toggle defaulted off would leave the plugin in the state this change exists to fix.

### `paper.JoinDeliveryListener`

```java
public final class JoinDeliveryListener implements Listener {
    public JoinDeliveryListener(Plugin plugin,
                               Supplier<NotificationDelivery> delivery,
                               boolean enabled,
                               long delaySeconds);
    public void reloadSettings(boolean enabled, long delaySeconds);
    @EventHandler public void onJoin(PlayerJoinEvent event);
}
```

> **Correction, 2026-08-29.** The claim below that `ChatSink` reports `DELIVERED` for an offline
> player is **false**, and was false when this document was written: `ChatSink` looks the `Player` up
> and returns `UNREACHABLE` when it is null. It appears to date from a version that sent to an
> Adventure `Audience`. Left in place as the record of what was believed at the time — but it caused a
> real bug in the persistent-broadcast work, so do not build on it. See
> `2026-08-29-persistent-offline-broadcast-design.md`.

- `delivery` is a `Supplier`, not an instance, for the same reason `TestNotificationSender` takes one:
  `PlayerNotificationsPlugin.reload()` replaces the `NotificationDelivery` object, so a captured
  reference goes stale after a reload.
- `enabled` and `delaySeconds` are `volatile` fields refreshed by `reloadSettings`, mirroring
  `DatabaseNotificationPreferences.reloadDefaultMedia` and `PreferenceDialogRouter.reloadCategories` —
  the established way this codebase applies a reload to an object other code already holds.
- `onJoin` returns immediately when `!enabled`. Otherwise it schedules an **async** task: delivery does
  blocking JDBC and `DiscordDmSink` refuses to run on the main thread outright. `delaySeconds == 0`
  uses `runTaskAsynchronously`; anything larger uses `runTaskLaterAsynchronously` with
  `delaySeconds * 20` ticks.
- The scheduled task re-checks `player.isOnline()` before delivering, and skips if the player left
  during the delay. Delivering to a player who has gone would consume chat notifications into nothing —
  `ChatSink` reports `DELIVERED` against an offline `Audience`, and the DELETE-wins fan-out would then
  drop the notification permanently.
- The task re-reads `enabled` too, so a reload that turns the trigger off during a pending delay
  cancels that delivery rather than letting it land.

### Wiring in `PlayerNotificationsPlugin`

- A `joinDeliveryListener` field, constructed and registered in `registerCommands()`'s neighbourhood —
  specifically in `onEnable()` after `notificationDelivery` is built, next to
  `schedulePruneTask(...)`, since both are "start the things that cause work to happen".
- `reload()` calls `this.joinDeliveryListener.reloadSettings(newSettings.deliverOnJoin(),
  newSettings.joinDeliveryDelaySeconds())`, alongside the existing `reloadDefaultMedia` and
  `reschedulePruneTask` calls.

## Error handling

- A `RuntimeException` from `deliver(UUID)` is caught and logged at `WARNING` naming the player's
  UUID, then swallowed. An uncaught throw inside a scheduled task is logged by Bukkit as a plugin
  error with no useful attribution, and one player's failed delivery must not affect the next join.
- Nothing is sent to the joining player on failure. Unlike `/notifications test`, this is not a
  diagnostic the player asked for; a stack trace in the console is the operator's concern.
- No behaviour change on disable: `onDisable` already nulls `notificationDelivery`, and Bukkit
  unregisters listeners for a disabled plugin, so a pending delayed task that survives sees
  `isOnline()` false or a cancelled scheduler.

## Testing strategy

`PlayerJoinEvent` and the Bukkit scheduler need a live server, which puts `JoinDeliveryListener`
itself in the same untestable bucket as `ChatSink`, `DialogSink` and the dialog screens. What *is*
tested:

- **`PluginSettingsTest`** (new, `:platform:paper-plugin:test`) — deserializes YAML through
  Configurate and asserts: both new keys read correctly; a negative `join-delivery-delay-seconds`
  clamps to `0`; `0` is preserved rather than defaulted; the existing `prune-interval-seconds` clamp
  still holds. This is the first test in the tree covering `PluginSettings` at all.
- **`JoinDeliveryListenerTest`** (new) — `reloadSettings` is a plain mutator on a plain object, so the
  gate decision is testable without Bukkit by asserting no scheduling attempt is made when disabled.
  Achieved by injecting the `Supplier<NotificationDelivery>` and asserting it is never queried; the
  `Plugin`/scheduler path is not exercised.

Manual verification with `./gradlew :platform:paper-plugin:runServer`:

1. Enqueue a notification for an offline player (join, `/notifications test`, then quit before the
   delay elapses so the notification is retained — or enqueue via another player's target).
2. Rejoin. Confirm the notification arrives roughly `join-delivery-delay-seconds` after join.
3. Set `deliver-on-join: false`, run `/notifications reload`, rejoin, confirm nothing is delivered and
   the notification is still pending.
4. Set it back to `true`, `/notifications reload`, rejoin, confirm delivery resumes without a restart.
5. Set `join-delivery-delay-seconds: 30`, rejoin, quit within the window, and confirm the console logs
   no error and the notification survives to the next join.

## Known limitations

- **Join is the only trigger.** A notification enqueued for an already-online player still waits until
  their next join. A push path (delivering at enqueue time when the target is online) is a separate
  design; it needs the enqueue call to reach the Paper layer, which `NotificationService` in `core`
  deliberately does not do.
- **The delay is not cancellable.** A player who joins, quits and rejoins inside the delay window
  schedules two tasks; the first sees `isOnline()` true again and delivers, the second finds nothing
  due and no-ops. Harmless, but it is two queries rather than one. Tracking pending tasks per player
  was judged not worth the map for a window measured in seconds.
- **No per-player opt-out of the join trigger.** A player who does not want notifications on join
  mutes the `dataType` instead (`/notifications mute`), which is the existing, medium-aware answer.
  A separate "when" axis alongside the existing "which media" axis was considered and rejected as
  scope.
- **Delivery on join is subject to the existing silent-partial-delivery limitation** of the DELETE-wins
  fan-out (see the renderers design doc). Nothing here changes that, but joining makes it reachable in
  normal operation for the first time rather than only via the admin test command.
