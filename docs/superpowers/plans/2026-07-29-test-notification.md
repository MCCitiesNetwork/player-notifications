# Built-in test notification type — Implementation Plan

**Goal:** Add an admin `/notifications test [message]` that enqueues and delivers a rendered notification
to the sender, and move `essentials-mail` off `String.class` onto its own payload record.
**Spec:** `docs/superpowers/specs/2026-07-29-test-notification-design.md`

## Task 1: Essentials mail owns its payload class

**Files:**
- create `platform/essentials-adapter/src/main/java/io/github/md5sha256/playernotifications/essentials/EssentialsMailPayload.java`
- modify `platform/essentials-adapter/.../EssentialsMailProcessor.java:20,31,33,35`
- modify `platform/essentials-adapter/.../EssentialsMailBinding.java:39-41`
- test `core/src/test/java/io/github/md5sha256/playernotifications/core/database/RecordPayloadDeliveryTest.java`

**Interfaces:**
- `public record EssentialsMailPayload(@NotNull String message)`
- `EssentialsMailProcessor implements NotificationProcessor<EssentialsMailPayload>`

- [ ] Write the failing test `RecordPayloadDeliveryTest` in `core` (extends `AbstractDatabaseTest`):
      registers a local `record MailLike(String message)` via
      `service.registerJsonPayload("mail-like", MailLike.class, processor)` where `processor` records
      the payload it receives and returns `DELETE`; enqueues a
      `TypedNotification<>(key, now, null, target(player), "mail-like", new MailLike("hello"), 0)`;
      calls `new NotificationDelivery(database, registry, logger).deliver(player)`; asserts the
      processor saw `new MailLike("hello")` — specifically `payload.message()` equals `hello` with **no
      surrounding quotes**, which is what regressed under the old `String` payload.
- [ ] Run `./gradlew :core:test --tests "*RecordPayloadDeliveryTest"` — expect FAIL (class does not exist)
- [ ] Implement `EssentialsMailPayload`; change `EssentialsMailProcessor` to
      `NotificationProcessor<EssentialsMailPayload>`, `receiveNotification(EssentialsMailPayload payload, …)`
      passing `payload.message()` into the existing private `deliver(String, UUID)`; replace
      `EssentialsMailBinding:40-41` with
      `service.registerJsonPayload(EssentialsMailModule.MAIL_DATA_TYPE, EssentialsMailPayload.class, processor)`
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: `registerJsonRenderable` on the service

**Files:**
- modify `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationService.java:35`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/DefaultNotificationService.java:117`
- test `core/src/test/java/.../core/database/DefaultNotificationServiceTest.java`

**Interfaces:**
- `<T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type, @NotNull NotificationRenderer<T> renderer)`

- [ ] Write the failing test in `DefaultNotificationServiceTest`: `registerJsonRenderable("x", XPayload.class, renderer)`
      then assert `dataTypeRegistry().resolvePayloadClass("x")` is `XPayload.class`,
      `getSerializer("x")` is present, `getRenderer("x")` is present, and `getProcessor("x")` is **empty**
- [ ] Run `./gradlew :core:test --tests "*DefaultNotificationServiceTest"` — expect FAIL (no such method)
- [ ] Implement: add the interface method with javadoc mirroring `registerJsonPayload`; implement in
      `DefaultNotificationService` as the three calls `registerPayloadMapping` / `registerSerializer(type, jsonSerializer(type))` /
      `registerRenderer(type, renderer)`
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: Test payload and renderer

**Files:**
- create `platform/paper-plugin/src/main/java/.../paper/diagnostic/TestNotificationPayload.java`
- create `platform/paper-plugin/src/main/java/.../paper/diagnostic/TestNotificationRenderer.java`
- test `platform/paper-plugin/src/test/java/.../paper/diagnostic/TestNotificationRendererTest.java`

**Interfaces:**
- `public record TestNotificationPayload(@NotNull String message)`
- `public final class TestNotificationRenderer implements NotificationRenderer<TestNotificationPayload>`
- `public static final String TEST_DATA_TYPE = "test"` on `TestNotificationPayload`

- [ ] Write the failing test: render `new TestNotificationPayload("ping")` for a fixed UUID; assert the
      title's plain text is `PlayerNotifications test`, that `PlainTextComponentSerializer` of the body
      contains `ping` and the UUID string, and that the child carrying `ping` has
      `TextDecoration.BOLD` set
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*TestNotificationRendererTest"` — expect FAIL
- [ ] Implement the record and the renderer per the spec's Rendering section
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 4: Registration, sender, and command

**Files:**
- create `platform/paper-plugin/src/main/java/.../paper/diagnostic/TestNotificationSender.java`
- modify `platform/paper-plugin/.../PlayerNotificationsPlugin.java:143-158`
- modify `platform/paper-plugin/.../command/NotificationsCommand.java:42-59`
- modify `platform/paper-plugin/src/main/resources/paper-plugin.yml:26-32`
- modify `platform/paper-plugin/src/main/resources/categories.yml`

**Interfaces:**
- `public final class TestNotificationSender { public TestNotificationSender(Plugin, NotificationService, Supplier<NotificationDelivery>); public void send(Player, String message); }`
  — a `Supplier<NotificationDelivery>` rather than the instance, because `reload()` replaces
  `PlayerNotificationsPlugin.notificationDelivery` with a new object and a captured reference would go stale
- `NotificationsCommand.create(router, reloadAction, testSender)` — third parameter
- `NotificationsCommand.TEST_PERMISSION = "playernotifications.command.test"`

- [ ] No unit test: this task is command registration and plugin wiring, which need a live server (the
      spec's "Requires a live server" list). Verification is manual, after Task 5's automated coverage of
      the underlying path:
      - `./gradlew :platform:paper-plugin:runServer`
      - as an op, run `/notifications test hello` → expect a chat message with the rendered notification
      - run `/notifications types` → expect a `Diagnostics` category containing `Test`
      - set `test` to Discord DM only, re-run `/notifications test hello` → expect a DM and no chat copy
      - as a non-op, `/notifications test` → expect the command to be unavailable
- [ ] Implement `TestNotificationSender.send`: build a `TypedNotification<>("test:" + UUID.randomUUID(),
      Instant.now(), Instant.now().plus(Duration.ofMinutes(10)), new NotificationTarget(List.of(player.getUniqueId())),
      "test", new TestNotificationPayload(message), 0)`; inside
      `getServer().getScheduler().runTaskAsynchronously`, call `enqueueNotification(notification, false)`
      then `delivery.get().deliver(player.getUniqueId())`, then report the media resolved for `test` to
      the sender; wrap the whole body in try/catch `RuntimeException` → log `WARNING` + red message
- [ ] Implement registration in `onEnable`, after the `preferences` field is assigned and **before**
      `registerCommands()`:
      `this.notificationService.registerJsonRenderable("test", TestNotificationPayload.class, new TestNotificationRenderer())`
- [ ] Add the `test` subcommand to `NotificationsCommand` with an optional greedy
      `Commands.argument("message", StringArgumentType.greedyString())`, both branches gated by
      `.requires(source -> source.getSender().hasPermission(TEST_PERMISSION))`
- [ ] Add `playernotifications.command.test` (`default: op`) to `paper-plugin.yml`
- [ ] Add a `diagnostics` category to `categories.yml` claiming `test`
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 5: End-to-end delivery test through the renderer/sink path

**Files:**
- test `core/src/test/java/.../core/database/RenderedDeliveryTest.java`

- [ ] Write the failing test: register a payload via `registerJsonRenderable`; build a
      `NotificationDelivery` with a `NotificationSinkRegistry` holding a recording sink under medium
      `recording` (returning `DELIVERED`) and a `NotificationPreferences` returning `Set.of("recording")`;
      enqueue a `TypedNotification`; `deliver(player)`; assert the sink received the rendered
      `RenderableNotification` and that the target row was pruned (`selectDueByPlayer` now empty)
- [ ] Run `./gradlew :core:test --tests "*RenderedDeliveryTest"` — expect FAIL (class does not exist)
- [ ] Implement the test's supporting fixtures only; production code is already in place from Tasks 2–3
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 6: Documentation

**Files:** modify `CLAUDE.md`

- [ ] Update: `essentials-adapter` description (own payload record, `registerJsonPayload`); the
      `NotificationService` type list (`registerJsonRenderable`); "Player commands" (`/notifications test`
      and its permission); "Testing gotchas" baseline test counts; "Current state" — remove the
      "`notifPayload` is a JSON column / string payloads arrive encoded" gap for `essentials-mail`, note
      that a renderer now has an in-tree consumer, and narrow the "nothing calls `deliver()`" gap to
      "only `/notifications test` calls it; still no join listener"
- [ ] Run `./gradlew build`
- [ ] Commit
