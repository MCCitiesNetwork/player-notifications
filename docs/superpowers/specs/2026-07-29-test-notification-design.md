# Built-in test notification type — design

**Date:** 2026-07-29
**Status:** proposed

## Goal

Give an operator a way to send a notification to themselves in-game and watch it travel the full
renderer/sink path, so the Discord DM sink (and any future sink) can be exercised end to end on a live
server without writing a throwaway plugin.

Concretely: a built-in `test` data type with a `NotificationRenderer`, and an admin-only
`/notifications test [message]` that enqueues one targeting the invoking player and delivers it
immediately.

## Motivation

As of today nothing in the tree can produce a notification that reaches a `NotificationSink`:

- The only registered `dataType` is `essentials-mail`, registered by `EssentialsMailBinding` with a
  **`NotificationProcessor`**. Explicit processors win the dispatch-precedence rule in
  `NotificationDelivery.dispatch`, so that payload bypasses preferences and sinks entirely — it can
  never reach `DiscordDmSink` no matter how preferences are set.
- **No `NotificationRenderer` is registered anywhere.** The renderer/sink architecture has zero
  in-tree consumers; `RenderingProcessor` is exercised only by unit tests.
- **Nothing calls `NotificationDelivery.deliver(UUID)`.** There is no join listener, command, or task
  that triggers delivery.

So this change is not only a debug aid — it is the first real consumer of the renderer/sink path, and
it closes the "nothing calls `deliver()`" bootstrap gap for at least one trigger.

## Architecture

### Every payload gets its own class

`NotificationDataTypeRegistry` keys processors, serializers, and renderers by **payload class**, and
resolves by data type through the class:

```java
public Optional<? extends NotificationProcessor<?>> getProcessor(@NotNull String dataType) {
    return resolvePayloadClass(dataType).flatMap(this::getProcessor);
}
```

So **two data types sharing one payload class silently share one processor, serializer, and renderer.**
`essentials-mail` currently maps to `String.class` — the obvious class for a "just a message" payload,
and therefore the one any second such payload would also reach for. Had the test type mapped to
`String.class` too, `getProcessor("test")` would have returned **`EssentialsMailProcessor`**, which wins
dispatch precedence, and the test notification would have gone to Essentials mail instead of through the
renderer to Discord.

This change therefore does two things:

1. `TestNotificationPayload` is its own record.
2. **`essentials-mail` moves off `String.class`** onto its own `EssentialsMailPayload` record, so no
   registered data type maps to a JDK type that another author would naturally pick.

Point 2 is not strictly required for point 1 to work, but leaving `essentials-mail` on `String.class`
leaves a live tripwire for the next payload author. It also fixes a wart already documented in
`CLAUDE.md`: because `notifPayload` is a `JSON` column, a `String` payload is JSON-encoded on write and
`EssentialsMailProcessor` receives the **quoted** form. A record with a `message` component serializes as
`{"message":"..."}` and deserializes back to the bare text, so mail bodies stop carrying stray quotes.

Moving off `String.class` also fixes a latent unload bug. `EssentialsMailModule.shutdown` calls
`unregisterPayloadMapping(MAIL_DATA_TYPE)`, and that method cascades:

```java
Class<?> payloadClass = this.payloadMapping.remove(dataType);
if (payloadClass != null) {
    unregisterProcessor(payloadClass);
    unregisterSerializer(payloadClass);   // <-- removes the *shared* String serializer
    unregisterRenderer(payloadClass);
}
```

While `essentials-mail` maps to `String.class`, unloading the Essentials module tears out the built-in
`String` serializer that `DefaultNotificationService` registered for everyone, breaking any other
`String` payload host-wide. Owning its payload class makes the cascade correctly scoped to the module.

No registration guard is added — `DefaultNotificationService`'s built-in `String` serializer stays, and
`String.class` remains a legal payload class for anyone who wants it. Rejecting it at registration (and
the more general "this payload class is already mapped to another data type" check) was considered and
deliberately deferred: it is a separate decision about tightening a public API, and after this change
nothing in the tree relies on the ambiguity. If a future collision does bite, that guard is the fix.

### New API: a renderer-path registration helper

`NotificationService` has `registerJsonPayload(dataType, type, processor)` — a one-call registration for
the **processor** path — but no counterpart for the **renderer** path, and
`DefaultNotificationService.jsonSerializer` is private. Being the first renderer consumer surfaces the
gap, so we add the mirror:

```java
<T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type,
                                @NotNull NotificationRenderer<T> renderer);
```

registering the payload mapping, a reflective JSON serializer, and the renderer. Implemented in
`DefaultNotificationService` next to `registerJsonPayload`, reusing the same private `jsonSerializer`.

This adds a method to a public `api` interface. `DefaultNotificationService` is the only implementation
in the tree, and feature modules consume `NotificationService` rather than implement it, so the break is
contained. It is called out here because the `api` module is compiled against separately by modules.

### Registration and delivery

Registration happens in `PlayerNotificationsPlugin.onEnable`, immediately after the sink registry is
built and **before** `registerCommands()` — the dialogs enumerate
`dataTypeRegistry().dataTypes()`, so `test` must be present for it to appear as a configurable type.

The command enqueues through `NotificationService` and then calls `deliver`, rather than invoking a sink
directly. Persisting first exercises serialization, the `selectDueByPlayer` query, dispatch precedence,
the renderer, preference resolution, sink fan-out, and target pruning; a direct sink call would prove
only that JDA can send a DM. The extra cost is one round trip to a database the plugin already requires.

Both the enqueue and the delivery run on the async scheduler: `DatabaseNotificationPreferences` and the
mappers do blocking JDBC, and `DiscordDmSink` explicitly refuses to run on the main thread (it warns and
returns `UNREACHABLE`). Feedback to the sender is sent from the async thread, which is safe for
`CommandSender#sendMessage`.

The notification key is `test:<uuid>` with a fresh random `UUID`, so repeated invocations never collide
and `overwriteAllowed` can be `false`. Expiry is set to 10 minutes out, so a test notification that
somehow fails to deliver (e.g. every preferred medium unreachable) is reaped by the existing prune task
instead of accumulating forever.

### Rendering

`TestNotificationRenderer` produces a deliberately plain `RenderableNotification`:

- **title:** `PlayerNotifications test` in `NamedTextColor.GOLD`
- **body:** the message, followed by a newline and the target UUID in grey

The body carries one styled child (bold on the message) so that a sink's style handling is actually
exercised — `DiscordMarkdownSerializer` converting bold to `**` is otherwise untested against real
input. It uses no click events, matching the `RenderableNotification` contract that sinks may drop them.

### Files

**Create**
- `platform/paper-plugin/src/main/java/.../paper/diagnostic/TestNotificationPayload.java` — record
  `(String message)`.
- `platform/paper-plugin/src/main/java/.../paper/diagnostic/TestNotificationRenderer.java` — implements
  `NotificationRenderer<TestNotificationPayload>`.
- `platform/paper-plugin/src/main/java/.../paper/diagnostic/TestNotificationSender.java` — holds the
  `NotificationService` + `NotificationDelivery` + plugin, exposes `send(Player, String message)`.
- `platform/paper-plugin/src/test/java/.../paper/diagnostic/TestNotificationRendererTest.java`
- `core/src/test/java/.../core/database/TestNotificationDeliveryTest.java` — end-to-end through a
  recording sink.
- `platform/essentials-adapter/src/main/java/.../essentials/EssentialsMailPayload.java` — record
  `(String message)`, public (it crosses the module boundary as a registered payload type).

**Modify**
- `api/.../NotificationService.java` — add `registerJsonRenderable`.
- `core/.../DefaultNotificationService.java` — implement it.
- `platform/essentials-adapter/.../EssentialsMailProcessor.java` — becomes
  `NotificationProcessor<EssentialsMailPayload>`; `receiveNotification` reads `payload.message()`.
- `platform/essentials-adapter/.../EssentialsMailBinding.java` — replaces the two-call
  `registerPayloadMapping` + `registerProcessor` pair with the single
  `service.registerJsonPayload(MAIL_DATA_TYPE, EssentialsMailPayload.class, processor)`, which registers
  the mapping, a reflective JSON serializer, and the processor together. The two-call form only worked
  because `DefaultNotificationService`'s constructor pre-registers a `String` serializer; a custom
  record has none, so registering the mapping without a serializer would leave delivery failing at
  `decodePayload` with "No serializer registered". `registerJsonPayload` exists for exactly this.
- `platform/paper-plugin/.../PlayerNotificationsPlugin.java` — register the test type; construct the
  sender; pass it to `registerCommands`.
- `platform/paper-plugin/.../command/NotificationsCommand.java` — add the `test` subcommand.
- `platform/paper-plugin/src/main/resources/paper-plugin.yml` — add the permission.
- `platform/paper-plugin/src/main/resources/categories.yml` — add a `diagnostics` category claiming
  `test`.

### Command surface

```
/notifications test              -> default message
/notifications test <message>    -> greedy string argument
```

Permission `playernotifications.command.test`, `default: op`. Player-only (it targets the sender), so it
goes through the existing `run()` helper in `NotificationsCommand` rather than the console-capable path
`reload` uses.

The sender replies with the outcome it can actually observe: how many preferred media were resolved for
`test`, so an operator who sees "delivered to: chat" immediately knows their Discord preference was never
set. Per-sink success is **not** reported — see Known limitations.

## Error handling

- **Enqueue or delivery throws** — caught in the async task, logged at `WARNING` with the stack trace,
  and reported to the sender in red. A failed test command must never propagate into the scheduler.
- **No serializer / no renderer registered** — cannot happen at runtime, since registration is
  unconditional in `onEnable`; if it somehow does, `NotificationDelivery` already logs and retains.
- **Player logs out between command and delivery** — `ChatSink` returns `UNREACHABLE`, `DiscordDmSink`
  proceeds normally. This is by design and is in fact a useful thing to test.
- **`test` in `categories.yml` with the type unregistered** — impossible, since the host registers it;
  the existing `warnAboutUnmappedCategoryTypes` check covers the inverse.

## Testing strategy

Unit-testable:
- `TestNotificationRendererTest` (`:platform:paper-plugin:test`) — asserts title, that the body contains
  the message, and that the message child is bold. Pure, no server.
- `DefaultNotificationServiceTest` (`:core:test`) — a case asserting `registerJsonRenderable` populates
  the payload mapping, serializer, and renderer, and leaves the processor map untouched.
- `TestNotificationDeliveryTest` (`:core:test`, Testcontainers) — registers `TestNotificationPayload`
  via `registerJsonRenderable`, enqueues a `TypedNotification`, delivers with a recording sink and fixed
  preferences, and asserts the sink saw the rendered body and the target row was pruned. Modelled on
  the existing `TypedPayloadDeliveryTest`.

- `:core:test` — a case asserting an `essentials-mail`-shaped record payload round-trips through
  `registerJsonPayload` → enqueue → `deliver` and reaches its processor as a decoded record with the
  bare message (no surrounding JSON quotes). `platform:essentials-adapter` has **no test source set**
  and its processor needs a live EssentialsX, so the record/serializer contract is verified in `core`
  against an equivalent payload rather than by instantiating `EssentialsMailProcessor`.

Requires a live server (manual, via `:platform:paper-plugin:runServer`):
- Essentials mail still arriving, with no stray quotes around the body.
- The command itself, the Brigadier registration, and the permission gate.
- `test` appearing in the `/notifications` dialogs and being configurable per medium.
- An actual Discord DM landing — which is the whole point, and which also discharges most of Task 8 of
  `docs/superpowers/plans/2026-07-29-discord-adapter.md`.

## Known limitations

- **The reply cannot report per-sink success.** `RenderingProcessor` folds every sink's `DeliveryResult`
  into a single `NotificationDisposition` and returns nothing per medium, so the command can say which
  media were *attempted*, not which *succeeded*. Reporting real per-sink outcomes needs the per-medium
  delivery tracking already deferred in the renderer design doc. If that lands, this reply should be
  upgraded — it is the natural first consumer.
- **A test notification is subject to the same silent partial delivery as any other.** If chat succeeds
  and Discord fails, the notification is consumed and the operator sees success in chat while no DM
  arrives. This is the documented DELETE-wins trade-off, not a bug in this feature — but it is precisely
  the confusing case for someone debugging Discord, so the reply text names the media attempted to make
  the discrepancy visible.
- **The type ships on every server.** It is admin-gated (`default: op`) and produces no traffic unless
  invoked, which was judged cheaper than a config flag or a separate Gradle module. If `test` ever
  becomes noise in the preference dialogs on production servers, the fix is a `settings.yml` flag around
  the registration — the registration is a single call site precisely so that stays easy.
- **No scheduled or multi-target test.** `/notifications test` always targets the sender, now. Testing
  offline delivery or a multi-player target group needs a richer admin command, deliberately out of
  scope.
- **Existing persisted `essentials-mail` rows will not deserialize.** Their `notifPayload` holds a
  JSON-encoded bare string (`"hello"`); after this change the type expects `{"message":"hello"}`.
  `NotificationDelivery.decodePayload` catches the failure, logs at `WARNING`, and **retains** the
  notification, so such rows sit undelivered until their expiry prunes them. No migration is written:
  `CLAUDE.md` records the project as still in prototyping with no data to preserve, which is the
  assumption this rests on — if the plugin has been deployed anywhere with real mail queued, say so and
  this needs a payload-rewriting migration instead.
- **The class-collision hazard remains open in general.** Nothing prevents two future data types from
  mapping to the same payload class and silently sharing its handlers; this change only removes the one
  live instance. The guard was deliberately deferred (see Architecture).
- **This does not close the delivery-trigger gap generally.** There is still no join listener; only this
  command calls `deliver`. Wiring delivery to player join remains a separate decision.
