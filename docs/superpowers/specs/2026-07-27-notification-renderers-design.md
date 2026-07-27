# Notification Renderers & Delivery Media — Design

**Date:** 2026-07-27
**Status:** Approved (design), pending implementation plan

## Problem

Today a payload type maps to exactly one `NotificationProcessor`, which both decides policy
(disposition) and performs delivery. That binding is fixed at registration: `EssentialsMailProcessor`
turns a `String` payload into Essentials mail, and nothing else can happen to it.

We want notifications delivered over several media — **in-game chat**, a **Paper dialog**, Essentials
mail, and (planned) **Discord** — with the medium chosen **per player, from a stored preference**, and
with a player able to select **more than one** medium at once (e.g. chat *and* Discord).

The current design cannot express this. Each payload author would have to look up the player's
preference themselves and hand-roll fan-out inside their processor, duplicating that logic per payload
and forcing every author to understand every medium — including Discord, which does not involve an
online `Player` at all.

## Decision summary

- Introduce a **medium-neutral intermediate form**, `RenderableNotification` (title + body), so payload
  authors convert their payload **once** rather than once per medium.
- Split the two axes into two independently registered concepts:
  - `NotificationRenderer<T>` — payload → `RenderableNotification`. One per payload type, written by
    the payload author.
  - `NotificationSink` — `RenderableNotification` → an actual medium. One per medium, written by the
    medium owner.
  This is **N + M** registrations rather than **N × M**: adding the Discord sink later requires no
  change to any existing payload.
- Bind them with a **single framework-supplied** `RenderingProcessor<T> implements NotificationProcessor<T>`
  that reads the player's preferences, renders once, and fans out. Payload authors do not write
  dispatch logic.
- `NotificationProcessor` is **unchanged** and remains the low-level escape hatch for bespoke policy.
  `EssentialsMailProcessor` keeps working untouched.
- Sinks report a dedicated `DeliveryResult` (`DELIVERED` / `UNREACHABLE` / `UNSUPPORTED`) rather than a
  `NotificationDisposition`, distinguishing transient from permanent failure.
- Preferences are **set-valued** and **persisted** in a new table, defaulting to a configurable medium
  set when a player has no rows.
- Fan-out resolves to **DELETE-wins**: if any sink returned `DELIVERED`, the notification is consumed.

### Rejected alternatives

- **A single `NotificationProcessor` concept (no renderer).** Viable only while the medium is fixed at
  registration. Per-player preference forces every payload author to reimplement preference lookup and
  fan-out; Discord makes that worse, since authors would need to understand a non-Minecraft medium.
- **A 2D `(payloadClass, medium) → Renderer` registry.** Maximum fidelity, but every payload author owes
  a renderer for every medium, and introducing a medium leaves all existing payloads uncovered — with
  per-player preference, a player can select a medium the payload author never heard of. Also requires
  fallback-chain rules in the registry for missing cells.
- **Renderers registered *by* a per-payload dispatching processor.** The original framing. Correct in
  spirit, but there is only ever *one* dispatching processor and it belongs to the framework, not to
  each payload.
- **Per-medium delivery tracking** (a `(notifKey, playerUuid, medium)` delivered-log). The only way to
  guarantee the multi-medium contract under partial failure. Deliberately deferred — see *Known
  limitations*.

## Components

### 1. `RenderableNotification` — `api`, new package `api.render`

```java
public record RenderableNotification(@NotNull Component title, @NotNull Component body) {}
```

Data only. It deliberately holds **no `Player` and no `Audience`**, because Essentials mail and Discord
deliver to players who are absent. `Component` is the lingua franca; sinks that are not Minecraft
clients (Discord) serialize it down to plain text or markdown. For that reason the body must not rely
on in-game-only affordances such as click events — a sink is free to drop them.

Actions/buttons are **out of scope** (see *Out of scope*), so a dialog renders as read-and-dismiss.

### 2. `NotificationRenderer<T>` — `api.render`

```java
@FunctionalInterface
public interface NotificationRenderer<T> {
    @NotNull RenderableNotification render(@NotNull T payload, @NotNull UUID target);
}
```

Takes the target UUID so a payload can personalise per recipient. Delivery is already per-target, so
this costs nothing.

### 3. `NotificationSink` and `DeliveryResult` — `api.render`

```java
public enum DeliveryResult {
    DELIVERED,    // reached the player
    UNREACHABLE,  // transient: offline, API hiccup, rate limited — retry later
    UNSUPPORTED   // permanent: this sink can never serve this player (e.g. no linked Discord account)
}

public interface NotificationSink {
    @NotNull String mediumKey();  // "chat", "dialog", "essentials-mail", "discord"
    @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification, @NotNull UUID target);
}
```

`UNSUPPORTED` exists so a persistent misconfiguration can be logged once as a warning instead of being
retried silently on every delivery pass.

### 4. `NotificationPreferences` — `api.render`

```java
public interface NotificationPreferences {
    @NotNull Set<String> preferredMedia(@NotNull UUID player);
}
```

Set-valued: multi-medium delivery (`chat` + `discord`) is the first-class case, not an edge case.

### 5. `NotificationSinkRegistry` — `api`, new

Sinks are keyed by medium, a different axis from data types, so they get their own registry rather than
being folded into `NotificationDataTypeRegistry`:

```java
public class NotificationSinkRegistry {
    void registerSink(@NotNull NotificationSink sink);   // keyed by sink.mediumKey()
    void unregisterSink(@NotNull String mediumKey);
    @NotNull Optional<NotificationSink> getSink(@NotNull String mediumKey);
    @NotNull Set<String> registeredMedia();
}
```

Thread-safety follows the existing `NotificationDataTypeRegistry` convention (synchronised maps).

### 6. `NotificationDataTypeRegistry` additions

Gains a renderer map alongside the existing processor and serializer maps:

```java
<T> void registerRenderer(@NotNull Class<T> payloadClass, @NotNull NotificationRenderer<T> renderer);
void unregisterRenderer(@NotNull Class<?> payloadClass);
<T> Optional<NotificationRenderer<T>> getRenderer(@NotNull Class<T> payloadClass);
Optional<? extends NotificationRenderer<?>> getRenderer(@NotNull String dataType);
```

`unregisterPayloadMapping` also drops the renderer, matching how it already drops the processor and
serializer.

### 7. `RenderingProcessor<T>` — `api.render`

```java
public final class RenderingProcessor<T> implements NotificationProcessor<T> {
    public RenderingProcessor(NotificationRenderer<T> renderer,
                              NotificationSinkRegistry sinks,
                              NotificationPreferences preferences,
                              Logger logger) { ... }
}
```

`receiveNotification(payload, target)`:

1. `preferences.preferredMedia(target)`.
2. Render **once** into a `RenderableNotification`.
3. For each preferred medium, resolve the sink and call `deliver`. A medium with no registered sink is
   logged at `fine` and skipped (the Discord sink may simply not be installed yet).
4. Fold: return `DELETE` if any sink returned `DELIVERED`, otherwise `RETAIN`.
5. If at least one sink returned `DELIVERED` and another did not, log which media were dropped, so the
   partial-delivery limitation is observable: `UNREACHABLE` at `fine` (expected and transient),
   `UNSUPPORTED` at `warning` (a standing misconfiguration an operator should fix).

A sink throwing a `RuntimeException` is caught, logged, and treated as `UNREACHABLE`, so one broken
sink cannot abort delivery to the others or crash the delivery loop — matching how `decodePayload`
already contains poison payloads.

### 8. Dispatch precedence in `NotificationDelivery`

`NotificationDelivery.dispatch` currently resolves a payload class, then a processor. It gains one rule:

1. An explicitly registered `NotificationProcessor` **wins** (preserves `EssentialsMailProcessor`).
2. Otherwise, if a `NotificationRenderer` is registered for the payload class, dispatch through a
   `RenderingProcessor`.
3. Otherwise, log and `RETAIN` as today.

No silent overwriting in either direction. `NotificationDelivery` is constructed with the sink registry
and preferences so it can build the rendering path; the data-type registry stays free of those
dependencies.

### 9. `ChatSink` and `DialogSink` — `api.render.sink`

Both ship in `api`, which already declares `compileOnlyApi("io.papermc.paper:paper-api")`, so Bukkit and
Adventure types are available. Note this makes `api` explicitly **Paper-coupled**, not platform-neutral;
that is consistent with what is already there.

- **`ChatSink`** (`mediumKey() == "chat"`) — resolves `Bukkit.getPlayer(target)`; returns `UNREACHABLE`
  if offline, otherwise sends title and body as chat components and returns `DELIVERED`.
- **`DialogSink`** (`mediumKey() == "dialog"`) — builds a `Dialog` from `DialogBase` (title) plus a
  `PlainMessageDialogBody` (body) with a single dismiss `ActionButton` and
  `DialogBase.DialogAfterAction.CLOSE`, then shows it to the player. Returns `UNREACHABLE` if offline.

Paper's Dialog API is confirmed present in `paper-api:1.21.8-R0.1-SNAPSHOT`
(`io.papermc.paper.dialog.Dialog`, `io.papermc.paper.registry.data.dialog.*`).

Both marshal onto the main thread when invoked off it, as `EssentialsMailProcessor` already does —
delivery runs on the async prune/join path.

### 10. Preference persistence — `core`

`V2__player_notification_preferences.sql`, following the existing `BINARY(16)` UUID convention and the
multi-row-per-key shape already used by `NotificationTarget`:

```sql
PlayerNotificationPreference(
    playerUuid BINARY(16) NOT NULL,
    medium     VARCHAR(64) NOT NULL,
    PRIMARY KEY (playerUuid, medium)
)
```

Single-statement DDL only — `MariaSchemaMigrator` splits scripts on `;`.

Supporting types, mirroring the existing persistence layout:

- `database.entity.PlayerNotificationPreferenceEntity`
- `database.mapper.PlayerNotificationPreferenceMapper` (vendor-neutral) with
  `selectByPlayer(UUID)`, `insertPreferences(UUID, Collection<String>)`, `deleteByPlayer(UUID)`
- `database.maria.mapper.MariaPlayerNotificationPreferenceMapper`
- registration in `MariaDatabase`, exposure on `SqlSessionWrapper`
- `core.DatabaseNotificationPreferences implements NotificationPreferences`

A player with **no rows** falls back to a configurable default medium set, so existing players are not
silently cut off from all notifications.

### 11. Configuration

`settings.yml` / `PluginSettings` gains:

```yaml
default-media: [chat]
```

A `List<String>` field annotated `@Setting` **and `@Required`**, per the project convention that every
non-null reference-typed setting is `@Required` so a missing key fails loudly.

### 12. Plugin wiring — `platform:paper-plugin`

`PlayerNotificationsPlugin.onEnable` additionally: constructs the `NotificationSinkRegistry`, registers
`ChatSink` and `DialogSink`, builds `DatabaseNotificationPreferences` from the configured default media,
and passes both into `NotificationDelivery`. The sink registry is exposed via an accessor alongside
`database()` / `notificationService()` so feature modules (the future Discord adapter) can register
their own sinks.

## Testing

- **`core` (Testcontainers, MariaDB 11.7)** — preference mapper round-trip; replace-preferences
  behaviour; the empty-rows default fallback; V2 migration applies cleanly on top of V1.
- **`api` (plain JUnit, no container)** — `RenderingProcessor` fan-out: multi-medium delivery reaches
  every registered sink; DELETE-wins folding across mixed `DeliveryResult`s; unknown medium is skipped;
  a throwing sink is contained and does not prevent delivery to the remaining sinks.
- **`core`** — `NotificationDelivery` precedence: an explicit processor wins over a registered renderer;
  a renderer-only payload dispatches through the rendering path; a payload with neither is retained.

Sinks are tested through fakes; `ChatSink` and `DialogSink` need a live server and are verified manually
via `:platform:paper-plugin:runServer`.

## Known limitations

- **Partial delivery is silent and unrecoverable.** Fan-out is DELETE-wins: if a player prefers
  `chat + discord`, chat succeeds and Discord transiently fails, the notification is consumed and
  Discord never receives it. In the normal path this does not bite — delivery fires on join, so chat is
  reachable and the Discord bot is reachable independently of player state, and both succeed in the same
  pass. Fixing the transient case requires per-medium delivery tracking, deliberately deferred. Retaining
  instead is *not* a workaround: chat is not idempotent, so the player would be messaged twice.
- **A player whose only preferred medium is permanently `UNSUPPORTED`** (prefers Discord alone, never
  links an account) accumulates notifications until `notifExpiryTime` prunes them.
- **`RenderableNotification` is a lossy common denominator.** A three-button dialog and a chat line are
  genuinely different media; the neutral form serves the intersection.

## Out of scope

Each of the following gets its own spec:

- **Actions/buttons** in `RenderableNotification`. A dialog button running a server command and a Discord
  button have no shared execution model. Deferring keeps the intermediate form additive — actions can be
  introduced later without breaking existing sinks.
- **The Discord sink and account linking.** This design exists to make that adapter cheap to add; it is
  not built here.
- **A player-facing command or GUI to change preferences.** Preferences are API- and admin-set for now.
- **Per-medium delivery tracking.** See *Known limitations*.
- **Converting `EssentialsMailProcessor` into a sink.** It keeps working under the precedence rule; the
  conversion is optional cleanup.
