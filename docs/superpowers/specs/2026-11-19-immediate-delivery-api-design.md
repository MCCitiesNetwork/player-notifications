# Immediate delivery API — design

## Goal

Give other plugins, through `NotificationService`, what `/broadcast` and `/notifications send` already
have:

1. **Store and push now** — including a Discord DM to an offline recipient — rather than waiting for the
   recipient's next join.
2. **Push without storing** (transient), for announcements only true at the moment they are sent.
3. **Address players by permission**, online or offline, for either of the above.

Today the only route to `NotificationDelivery.deliver` is a host-internal lambda, and permission
resolution lives in `paper.broadcast`, unreachable from the API.

## API

All abstract on `NotificationService`. **No default methods**: how a send is resolved and delivered is the
implementation's business, and a default would either lie (enqueue without pushing) or exist only to keep
fakes compiling. The six in-tree test fakes implement the new methods instead.

```java
<T> CompletableFuture<Void> enqueueAndDeliver(TypedNotification<T> notification, boolean overwriteAllowed);
CompletableFuture<Void> enqueueAndDeliver(ResolvedNotification notification, boolean overwriteAllowed);
<T> CompletableFuture<Void> enqueueAndDeliver(TypedNotification<T> notification, NotificationAudience audience,
                                              boolean overwriteAllowed);
CompletableFuture<Void> deliverTransient(String dataType, RenderableNotification notification,
                                         NotificationAudience audience);
```

### `api.NotificationAudience`

Who a send reaches, resolved by the implementation at send time.

```java
public sealed interface NotificationAudience {
    record Players(Set<UUID> players) implements NotificationAudience {}
    record WithPermissions(List<String> permissions, PermissionChain chain, boolean includeOffline)
            implements NotificationAudience {}
    static NotificationAudience players(Collection<UUID> players);
    static NotificationAudience withAnyPermission(boolean includeOffline, String... permissions);  // OR
    static NotificationAudience withAllPermissions(boolean includeOffline, String... permissions); // AND
}
public enum PermissionChain { AND, OR }
```

- Records validate in their compact constructors: `WithPermissions` rejects an empty or blank permission
  list (`IllegalArgumentException`) — for offline it would enumerate every player the backend knows, and
  `--offline` refuses the same. Rejected at construction rather than in the future, because it is a
  caller bug, not a runtime condition.
- **Permission resolution is not API.** There is no `playersWithPermissions`; the caller describes the
  audience and the implementation resolves it. This trades away the caller seeing the audience size
  before sending (the API analogue of `--limit`) for a smaller surface that does not commit the API to a
  resolution mechanism.
- **`includeOffline = true` needs LuckPerms.** Without it the future fails with
  `api.OfflineLookupUnavailableException`, a distinct type so a caller can retry online-only on purpose.
  No silent degradation — `--offline`'s rule.
- A sealed interface rather than an overload per shape keeps the method count fixed as shapes are added.

### The audience overload of `enqueueAndDeliver`

`TypedNotification` carries a `NotificationTarget` (a record over a `List<UUID>`), which the audience
form cannot know in advance. **The notification's own target is ignored and replaced by the resolved
audience**; callers pass `new NotificationTarget(List.of())`. Javadoc states this. Considered instead:
a target-less "draft" notification type — rejected as a second notification shape for one overload.
**An audience resolving to nobody stores nothing** and completes normally (an empty target group cannot
exist — the trigger would delete the row).

## Contract (every new method)

- **Safe from any thread.** Nothing blocks the caller; resolution, store and push run on the host's
  async executor.
- **Store first.** A failed enqueue fails the future and nothing is pushed.
- **Per-target isolation.** One target's pass throwing is logged at `WARNING` and the rest still run; the
  future completes normally.
- **Completion means attempted, not delivered.** `NotificationDelivery.deliver` returns `void`.
- **Duplicates collapse**: one pass per player.
- **The persistent push delivers everything due for each target**, not just the new notification —
  harmless, since due and unseen means owed a push.
- **A future `notifScheduledTime` is not due**, so the immediate pass skips it; it goes out on a later join.
- **Mute and silence always apply. No bypass from the API** — offering one would let any plugin override
  a player's do-not-disturb.
- **Resolution failure fails the whole future**, never a partial audience: the players missed are not
  present to notice.
- **Runtime failures arrive in the future, never as a throw from the call**: implementation not yet
  wired, delivery gone after `onDisable`, scheduler refusal while disabling, LuckPerms absent.

### Why "now and later" needs no new delivery rule

The store makes it inbox-readable regardless. Per target: any `DELIVERED` marks it seen; nothing
delivered leaves it unread, so the next join pushes it; muted or silenced leaves it unread and unpushed.

### Transient semantics

Never stored: not in the inbox, not pushed on join. Per target: skip if muted; `preferredMedia(target,
dataType)` minus `SILENCED_MEDIUM`; deliver to each registered sink, catching a throwing sink. Offline
targets go through the same sinks — a linked Discord account gets the DM, chat answers `UNREACHABLE`.
`dataType` need not be registered (preferences key on the string), but only a registered one appears in
the preference dialogs.

## Implementation

- **`core.DefaultNotificationService`** implements the methods. It is constructed in `onEnable` before
  `NotificationDelivery` exists and cannot ask Bukkit about permissions, so the host supplies both late:
  `attachHost(Supplier<NotificationDelivery> delivery, Executor executor, AudienceResolver resolver, Logger logger)`,
  stored in one `volatile` record. Not on the interface. The `Supplier` is read per call because
  `reload()` replaces `NotificationDelivery`.
- **`core.AudienceResolver`** — internal: `Set<UUID> resolve(NotificationAudience audience)`, blocking.
  `Players` is resolved in `core` directly; only `WithPermissions` reaches the host's resolver.
- **`NotificationDelivery.deliverTransient(RenderableNotification, String dataType, UUID target)`** — it
  already holds sinks and preferences and is swapped on reload. The 3-arg constructor has no sinks;
  calling it there throws `IllegalStateException`.
- **`paper.broadcast.PermissionAudienceResolver(BroadcastAudience online, @Nullable BroadcastAudience offline)`**
  — maps `PermissionChain` to `BroadcastArguments.Chain`; online ∪ offline when `includeOffline`; throws
  `OfflineLookupUnavailableException` when offline is requested and `offline` is null. Reuses
  `OnlineBroadcastAudience` and `OfflineBroadcastAudience` unchanged.
- **`PlayerNotificationsPlugin`** calls `attachHost` once both the delivery and the audiences exist.

### Not changed

`Broadcaster`, `PersistentBroadcaster` and `SendCommand`. Their replies need per-recipient counts and
`--bypass`, which the API deliberately lacks. They call the same `NotificationDelivery` and audiences;
only `usableMedia` (three lines) is duplicated into `deliverTransient`, judged cheaper than moving
`Broadcaster`'s `MessageContainer` dependency into `core`. `BroadcastArguments.Chain` stays rather than
being replaced by `PermissionChain`, to avoid touching every broadcast test for a rename.

## Compatibility

**Breaking for anything implementing `NotificationService` outside this repo** (new abstract methods).
Accepted: implementing the service is not a supported extension point — plugins consume it from the
`ServicesManager` — and the only known implementors are `DefaultNotificationService` and in-tree fakes.
Callers are unaffected. Ships in the next `api` release.

In-tree fakes to update (each overrides with `throw new UnsupportedOperationException()`):
`discord/command/FakeNotificationService`, `essentials/convert/EssentialsMailConverterTest`,
`paper/broadcast/PersistentBroadcasterTest`, `paper/customtype/CustomTypeRegistrarTest`,
`paper/JoinDeliveryListenerTest`, `paper/mail/MailSenderTest`.

## Testing

`core` — `ImmediateDeliveryTest` (`AbstractDatabaseTest`, Docker; same-thread executor, recording sink,
stub `AudienceResolver`):

1. `enqueueAndDeliver` stores and delivers; seen, still exists.
2. `UNREACHABLE` sink leaves it stored and due.
3. Muted target: nothing delivered, still due.
4. Duplicate key with `overwriteAllowed=false` fails the future; nothing delivered.
5. One target's pass throwing does not stop another's.
6. Duplicate UUIDs in an audience → one pass per player.
7. Throwing executor → failed future, no throw.
8. `null` delivery supplier → failed future.
9. Never-attached service → failed future.
10. Audience overload with `WithPermissions`: the stub's set becomes the stored target; the
    notification's own target is ignored.
11. Audience resolving to nobody: nothing stored, completes normally.
12. Resolver throwing `OfflineLookupUnavailableException` fails the future with it as the cause.
13. `deliverTransient` reaches the sink and stores nothing.
14. `deliverTransient` to a muted and to a silenced target reaches no sink.

`api` — `NotificationAudienceTest`: factories build the right records; `WithPermissions` rejects empty
and blank permission lists; `Players` copies defensively.

`paper` — `PermissionAudienceResolverTest` with fake `BroadcastAudience`s: chain mapping; online-only
never queries offline; `includeOffline` unions; offline requested with none → exception.

Host wiring needs a live server — checklist in the plan.

## Known limitations

- **No per-target or per-medium result** — inherited from `deliver` returning `void`.
- **The caller cannot see the audience size before sending.** The trade for keeping resolution out of the
  API; revisit with a `limit` component on `WithPermissions` if a plugin needs one.
- **No push at a future-scheduled notification's time** — waits for a later join.
- **Online and offline permission matching can disagree** (Bukkit vs LuckPerms stored data); LuckPerms
  installed after enable stays invisible until restart. Both inherited from `/broadcast`.
- **A transient send to an unregistered `dataType` cannot be silenced from the dialogs.**
- **`DefaultNotificationService` gains late-bound mutable state**, set once at enable.
