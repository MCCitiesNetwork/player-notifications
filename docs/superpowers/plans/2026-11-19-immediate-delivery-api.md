# Immediate Delivery API Implementation Plan

**Goal:** `NotificationService` can store-and-push now, push transiently, and address an audience by
permission (online or offline), all implemented by `DefaultNotificationService` with no API defaults.
**Spec:** `docs/superpowers/specs/2026-11-19-immediate-delivery-api-design.md`

Paths below abbreviate `…/` = `src/{main,test}/java/io/github/md5sha256/playernotifications/`.

## Task 1: Audience types

**Files:** create `api/…/api/NotificationAudience.java`, `api/…/api/PermissionChain.java`,
`api/…/api/OfflineLookupUnavailableException.java`; test `api/…/api/NotificationAudienceTest.java`.

**Interfaces:** as in the spec's API section;
`public class OfflineLookupUnavailableException extends RuntimeException { public OfflineLookupUnavailableException(@NotNull String message) }`.

- [ ] Write the failing test:
  ```java
  @Test void playersFactoryCopies() {
      List<UUID> src = new ArrayList<>(List.of(A));
      NotificationAudience a = NotificationAudience.players(src);
      src.add(B);
      assertEquals(new NotificationAudience.Players(Set.of(A)), a);
  }
  @Test void anyIsOr() {
      assertEquals(new NotificationAudience.WithPermissions(List.of("x.y"), PermissionChain.OR, true),
              NotificationAudience.withAnyPermission(true, "x.y"));
  }
  @Test void allIsAnd() { /* withAllPermissions(false, "a", "b") → (["a","b"], AND, false) */ }
  @Test void emptyPermissionsRejected() {
      assertThrows(IllegalArgumentException.class, () -> NotificationAudience.withAnyPermission(false));
  }
  @Test void blankPermissionRejected() {
      assertThrows(IllegalArgumentException.class, () -> NotificationAudience.withAnyPermission(false, " "));
  }
  ```
- [ ] Run `./gradlew :api:test --tests "*NotificationAudienceTest"` — expect compile FAIL.
- [ ] Implement: compact constructors `Set.copyOf` / `List.copyOf`; `WithPermissions` throws
  `IllegalArgumentException` if empty or any element blank.
- [ ] Run — expect PASS. Run `./gradlew build`.
- [ ] Commit `feat(api): add NotificationAudience and PermissionChain`.

## Task 2: Service methods, core implementation, fakes

**Files:**
- modify `api/…/api/NotificationService.java` (four abstract methods, javadoc carrying the Contract)
- create `core/…/core/AudienceResolver.java`
- modify `core/…/core/NotificationDelivery.java` (`deliverTransient`)
- modify `core/…/core/DefaultNotificationService.java`
- modify the six fakes listed in the spec's Compatibility section
- test `core/src/test/java/io/github/md5sha256/playernotifications/core/database/ImmediateDeliveryTest.java`

**Interfaces:**
```java
@FunctionalInterface public interface AudienceResolver {
    @NotNull Set<UUID> resolve(@NotNull NotificationAudience.WithPermissions audience);
}
// NotificationDelivery
public void deliverTransient(@NotNull RenderableNotification notification, @NotNull String dataType, @NotNull UUID target)
// DefaultNotificationService
public void attachHost(@NotNull Supplier<@Nullable NotificationDelivery> delivery, @NotNull Executor executor,
                       @NotNull AudienceResolver resolver, @NotNull Logger logger)
```

- [ ] Write the failing test, fixture as `RenderedDeliveryTest` (`Ping`, `registerPing()`,
  `RecordingSink`, past `DUE`, `exists`/`stillDue`). `@BeforeEach` calls
  `service.attachHost(() -> delivery, Runnable::run, stubResolver, Logger.getLogger("test"))`, where
  `stubResolver` returns a per-test `Set<UUID>` field. Cases 1–14 from the spec's Testing section:
  - 4: enqueue `key` directly, then `enqueueAndDeliver` same key, `false`;
    `assertThrows(CompletionException.class, f::join)`; sink empty.
  - 5: preferences whose `isMuted` throws for A (a sink throw would not do — `RenderingProcessor` catches
    those as `UNREACHABLE`; `isMuted` runs unguarded at the top of `deliver`); target A and B; B received it.
  - 7: `attachHost(..., r -> { throw new IllegalStateException(); }, ...)`; `isCompletedExceptionally()`.
  - 9: `new DefaultNotificationService(database)` (the shared `service` is attached).
  - 10: stub returns `{A, B}`; `enqueueAndDeliver(pingTo(List.of()), withAnyPermission(false, "x"), false)`;
    stored target members are exactly `{A, B}` (via `resolveNotifications(A)` and `(B)`).
  - 11: stub returns `{}`; `!exists(key)`, future completes normally.
  - 12: stub throws `OfflineLookupUnavailableException`; `CompletionException` whose cause is that type.
  - 13: `deliverTransient("ping", new RenderableNotification(text("T"), text("B")), players(Set.of(P)))`;
    sink received one; `inbox(P, 1, 20).totalEntries() == 0`.
  - 14: A muted, B with preferences `Set.of(SILENCED_MEDIUM)` for `"ping"`; sink empty.
- [ ] Run `./gradlew :core:test --tests "*ImmediateDeliveryTest"` — expect compile FAIL.
- [ ] Implement.
  - `NotificationService`: four abstract methods.
  - `NotificationDelivery.deliverTransient`: `IllegalStateException` if `sinkRegistry` or `preferences` is
    null; return if muted; media = `preferredMedia(target, dataType)` minus `SILENCED_MEDIUM`; each sink
    via `getSink`, absent logged `fine`, `deliver` in `try/catch (RuntimeException)` logged `WARNING`.
  - `DefaultNotificationService`:
    ```java
    private record HostWiring(Supplier<NotificationDelivery> delivery, Executor executor,
                              AudienceResolver resolver, Logger logger) {}
    private volatile HostWiring host;

    private <R> CompletableFuture<R> submit(Function<HostWiring, R> task) {
        HostWiring h = this.host;
        if (h == null) return CompletableFuture.failedFuture(new IllegalStateException("Immediate delivery is not attached"));
        try { return CompletableFuture.supplyAsync(() -> task.apply(h), h.executor()); }
        catch (RuntimeException ex) { return CompletableFuture.failedFuture(ex); }
    }
    private static Set<UUID> resolve(HostWiring h, NotificationAudience a) {
        return switch (a) {
            case NotificationAudience.Players p -> p.players();
            case NotificationAudience.WithPermissions w -> h.resolver().resolve(w);
        };
    }
    private static NotificationDelivery current(HostWiring h) { /* get(), null → IllegalStateException */ }
    private static void forEachTarget(HostWiring h, Collection<UUID> targets, Consumer<UUID> action) {
        for (UUID t : new LinkedHashSet<>(targets)) {
            try { action.accept(t); }
            catch (RuntimeException ex) { h.logger().log(Level.WARNING, "Immediate delivery to " + t + " failed", ex); }
        }
    }
    ```
    - `enqueueAndDeliver(n, o)` (both overloads): `submit(h -> { enqueueNotification(n, o);
      NotificationDelivery d = current(h); forEachTarget(h, n.notifTarget().playerUUIDs(), d::deliver); return null; })`.
    - audience overload: `submit(h -> { Set<UUID> to = resolve(h, audience); if (to.isEmpty()) return null;
      TypedNotification<T> placed = new TypedNotification<>(n.notifKey(), n.notifScheduledTime(),
      n.notifExpiryTime(), new NotificationTarget(List.copyOf(to)), n.notifPayloadType(), n.notifPayload(),
      n.notifPriority()); …same as above… })`.
    - `deliverTransient`: blank `dataType` → failed future `IllegalArgumentException`; else
      `submit(h -> { NotificationDelivery d = current(h); forEachTarget(h, resolve(h, audience),
      t -> d.deliverTransient(notification, dataType, t)); return null; })`.
  - Each fake: `@Override` the four methods with `throw new UnsupportedOperationException();`.
- [ ] Run `./gradlew :core:test --tests "*ImmediateDeliveryTest"` — expect PASS (14).
- [ ] Run `./gradlew build` — every module's tests compile against the new abstract methods.
- [ ] Commit `feat(api): add immediate, audience and transient delivery to NotificationService`.

## Task 3: Permission resolver and host wiring

**Files:** create `platform/paper-plugin/…/paper/broadcast/PermissionAudienceResolver.java`; test
`platform/paper-plugin/…/paper/broadcast/PermissionAudienceResolverTest.java`; modify
`platform/paper-plugin/…/paper/PlayerNotificationsPlugin.java`.

**Interfaces:**
`public PermissionAudienceResolver(@NotNull BroadcastAudience online, @Nullable BroadcastAudience offline)`
implementing `AudienceResolver`.

- [ ] Write the failing test with lambda `BroadcastAudience`s recording the `BroadcastArguments.Chain`:
  (a) `AND`/`OR` map to the same-named `Chain`; (b) `includeOffline=false` never calls offline and returns
  the online list as a set; (c) `includeOffline=true` returns the union; (d) `includeOffline=true` with
  `offline == null` throws `OfflineLookupUnavailableException`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*PermissionAudienceResolverTest"` — expect compile FAIL.
- [ ] Implement: `Chain.valueOf(chain.name())`; `LinkedHashSet` of `online.resolve(perms, c)`; when
  `includeOffline`, throw `new OfflineLookupUnavailableException("Offline permission lookup requires LuckPerms")`
  if `offline == null`, else `addAll(offline.resolve(perms, c))`.
- [ ] Run — expect PASS.
- [ ] Wire in `PlayerNotificationsPlugin`: `onEnable` constructs `notificationDelivery` (~line 282) before
  `registerCommands()` builds `broadcastAudience`/`offlineBroadcastAudience` (~lines 441–452). Call, at
  the end of that block in `registerCommands()`:
  ```java
  this.notificationService.attachHost(() -> this.notificationDelivery, asyncExecutor,
          new PermissionAudienceResolver(broadcastAudience, offlineBroadcastAudience), getLogger());
  ```
  (`asyncExecutor` is already defined there; the field is typed `DefaultNotificationService`, line 90.)
  Order is safe: `notificationDelivery` is assigned at line 282 and `registerCommands(...)` is called at
  line 315 (`private void registerCommands(int inboxPageSize)`, line 404).
- [ ] Run `./gradlew build`.
- [ ] Live-server checklist (`:platform:paper-plugin:runServer`, scratch plugin calling the service):
  1. `enqueueAndDeliver` from the **main thread** to an online player: chat immediately, no lag warning.
  2. Same to an offline chat-only player: nothing now; unread in inbox; pushed on join.
  3. Muted player: nothing pushed; unread in inbox.
  4. Discord module, linked offline player: DM at send time.
  5. After `/notifications reload`, step 1 still works.
  6. `deliverTransient` to an online player: chat shown; nothing in `/notifications`.
  7. `withAnyPermission(false, "some.node")`: exactly the online holders receive it.
  8. With LuckPerms, `includeOffline=true`: an offline player holding the node **only through a group**
     finds it unread on join. Without LuckPerms: future fails with `OfflineLookupUnavailableException`.
- [ ] Commit `feat(paper): resolve permission audiences and wire immediate delivery`.

## Task 4: Docs

**Files:** modify `CLAUDE.md`.

- [ ] "Delivery": triggers are join, `/notifications test`, and the service's `enqueueAndDeliver` /
  `deliverTransient` (async, attempted not delivered, no bypass, wired by `attachHost`). `api` bullet: add
  the methods, `NotificationAudience`, `PermissionChain`, `OfflineLookupUnavailableException`. Features
  row linking the spec, noting the LuckPerms requirement and that implementing `NotificationService`
  externally broke. Add Task 3's checklist to "Unverified". Remove "No push to already-online players"
  from Known gaps.
- [ ] Commit `docs: document immediate delivery API`.

## Task 6: Batched delivery

**Files:** create `core/…/core/BatchDeliveryReport.java`, `core/…/core/database/entity/DueNotificationEntity.java`;
modify `NotificationMapper`/`MariaNotificationMapper` (`selectDueByPlayers`), `NotificationDelivery`
(constructor taking `int batchSize`, `deliver(Collection<UUID>)`), `DefaultNotificationService.pushStored`,
`PluginSettings` + bundled `settings.yml` (`delivery-batch-size`), `PlayerNotificationsPlugin` (both
`NotificationDelivery` constructions). Test `core/…/database/BatchDeliveryTest.java`, `PluginSettings` test.

- [ ] Failing tests (`BatchDeliveryTest`, real MariaDB, batch size 2): three players each with a due
  notification all delivered and marked seen; `attempted == 3`; a player whose `isMuted` throws is in
  `failed` and the others still delivered; a muted player gets nothing and counts as attempted; duplicates
  collapse; a seen notification is not re-sent; a `UNREACHABLE` player stays due. Mapper:
  `selectDueByPlayers` returns rows tagged with the right player and excludes seen/expired/future rows.
  Settings: absent key → 200, 5000 → 1000, -1 → 1.
- [ ] Run `./gradlew :core:test --tests "*BatchDeliveryTest"` — expect FAIL.
- [ ] Implement; `pushStored` calls `deliver(targets)`.
- [ ] PASS, `./gradlew build`, commit `feat(core): batch delivery passes for large audiences`.

## Task 7: Broadcast through the batch

**Files:** modify `PersistentBroadcaster` (push type `Function<List<UUID>, BatchDeliveryReport>`, step 2 one
call), `PlayerNotificationsPlugin` (wire `targets -> this.notificationDelivery.deliver(targets)`),
`PersistentBroadcasterTest` (every constructor call site; the failing-push case returns a report with a
`failed` set).

- [ ] Change the failing-push test first: a push returning `new BatchDeliveryReport(1, Set.of(b))` yields
  `Result(attempted 1, failed 1)`. Run `./gradlew :platform:paper-plugin:test --tests "*PersistentBroadcasterTest"` — expect compile FAIL, then FAIL.
- [ ] Implement, PASS, `./gradlew build`, commit `refactor(broadcast): push persistent broadcasts through batch delivery`.
- [ ] Update `CLAUDE.md` (Configuration: `delivery-batch-size`; Delivery: batch path and who uses it).
