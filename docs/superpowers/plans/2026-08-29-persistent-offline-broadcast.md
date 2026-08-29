# Persistent and offline broadcasts — implementation plan

**Goal:** add `--chain`, `--persistent`, `--offline` and `--limit` to `/broadcast`, so an operator can
reach players who are not online and send a broadcast that survives being missed.
**Spec:** `docs/superpowers/specs/2026-08-29-persistent-offline-broadcast-design.md`

**Baseline before any change:** `:api:test` 39, `:platform:paper-plugin:test` 218,
`:platform:essentials-mail-converter:test` 21 — all green. `:core:test` and
`:platform:discord-adapter:test` need Docker and were not run.

## Task 1: `BroadcastArguments` — the four flags

**Files:** modify `platform/paper-plugin/src/main/java/.../paper/broadcast/BroadcastArguments.java`;
modify `platform/paper-plugin/src/test/java/.../paper/broadcast/BroadcastArgumentsTest.java`

**Interfaces:**
```java
public record BroadcastArguments(@NotNull String content, @NotNull List<String> permissions,
                                 boolean bypass, @NotNull Chain chain, boolean persistent,
                                 boolean offline, @Nullable Integer limit)
public enum Chain { AND, OR }
// new Result cases: UnknownChainValue(String), InvalidLimitValue(String),
//                   OfflineRequiresPersistent(), OfflineRequiresPermission()
```

- [ ] Write failing tests: `--chain and` parses `Chain.AND`; `--chain OR` is case-insensitive; absent
      `--chain` defaults to `OR`; `--chain sideways` is `UnknownChainValue`; `--chain` as final token is
      `FlagMissingValue`; `--persistent` sets the flag; `--offline` alone is `OfflineRequiresPersistent`;
      `--offline --persistent` with no `--perm` is `OfflineRequiresPermission`;
      `--offline --persistent --perm a` parses; `--limit 50` parses 50; absent `--limit` is null;
      `--limit 0` is `InvalidLimitValue`; `--limit -1` is `InvalidLimitValue`; `--limit x` is
      `InvalidLimitValue`; `--limit` as final token is `FlagMissingValue`; flags in any order;
      content containing `--persistent` is rejected as content-then-flags.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcastArgumentsTest"` — expect FAIL:
      the record has no `chain`/`persistent`/`offline`/`limit` components and the cases do not exist.
- [ ] Implement: add the components, the `Chain` enum, the three new `Result` records, and the flag
      branches in the token loop. `--chain`/`--limit` consume a value like `--perm`; the two
      `--offline` prerequisites are checked after the loop.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect no regression below 218
- [ ] Commit

## Task 2: `BroadcastRecipients` — the AND chain

**Files:** modify `.../broadcast/BroadcastRecipients.java`; modify
`.../broadcast/BroadcastRecipientsTest.java`

**Interfaces:**
```java
public static List<UUID> select(Collection<Candidate>, List<String>, BroadcastArguments.Chain)
public static List<UUID> select(Collection<Candidate>, List<String>)   // delegates, OR
```

- [ ] Write failing tests: AND with both nodes held matches; AND with one of two held does not; AND with
      an empty permission list matches all; OR through the two-argument delegate is unchanged.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcastRecipientsTest"` — expect FAIL: no
      three-argument `select`.
- [ ] Implement: add the `Chain` parameter, branch `allMatch`/`anyMatch`, keep the old signature as a
      delegate.
- [ ] Run the same command — expect PASS
- [ ] Commit

## Task 3: `Broadcaster.suppressed`

**Files:** modify `.../broadcast/Broadcaster.java`; modify `.../broadcast/BroadcasterTest.java`

**Interfaces:** `public List<UUID> suppressed(@NotNull Collection<UUID> recipients)`

- [ ] Write failing tests: a muted recipient is returned; a recipient silenced to `{none}` is returned; a
      recipient with usable media is not; input order is preserved.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcasterTest"` — expect FAIL: no such method.
- [ ] Implement: extract the existing mute/silence condition from `broadcast` into a private predicate and
      expose `suppressed` over it, so the two cannot drift.
- [ ] Run the same command — expect PASS
- [ ] Commit

## Task 4: `BroadcastRenderer` and the payload registration

**Files:** create `.../broadcast/BroadcastRenderer.java`; create
`.../broadcast/BroadcastRendererTest.java`; modify `.../broadcast/BroadcastPayload.java` (javadoc)

**Interfaces:** `public final class BroadcastRenderer implements NotificationRenderer<BroadcastPayload>`

- [ ] Write failing tests: a MiniMessage body renders with its formatting; a body containing a legacy `§`
      code falls back to literal text rather than throwing; the title comes from the `MessageContainer`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcastRendererTest"` — expect FAIL: class
      does not exist.
- [ ] Implement: mirror `MailRenderer` — deserialize with MiniMessage inside a `try`, catch
      `RuntimeException` and fall back to `Component.text(raw)`; title from
      `messages.messageFor(MessageKeys.BROADCAST_TITLE)`; ignore `target`.
- [ ] Run the same command — expect PASS
- [ ] Commit

## Task 5: `PersistentBroadcaster`

**Files:** create `.../broadcast/PersistentBroadcaster.java`; create
`.../broadcast/PersistentBroadcasterTest.java`

**Interfaces:**
```java
public PersistentBroadcaster(NotificationService, Supplier<NotificationDelivery>, Broadcaster,
                             Server, Logger)
public Result broadcast(Component content, String rawContent, Collection<UUID> recipients, boolean bypass)
public record Result(int stored, int pushed, int bypassed) {}
```

- [ ] Write failing tests, against a recording fake `NotificationService` and a fake `Broadcaster`: one
      notification is enqueued with every recipient in a single `NotificationTarget`; the stored payload
      holds the raw MiniMessage string, not the rendered component; only online recipients are pushed
      through `NotificationDelivery.deliver`; with `bypass` the transient fan-out receives exactly
      `Broadcaster.suppressed`'s set; without `bypass` it is never called; a `deliver` that throws for one
      recipient does not stop the rest; the returned counts.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*PersistentBroadcasterTest"` — expect FAIL:
      class does not exist.
- [ ] Implement the three steps in the spec's order, catching a throwing `deliver` per recipient.
- [ ] Run the same command — expect PASS
- [ ] Commit

## Task 6: the LuckPerms lookup, isolated

**Files:** create `.../broadcast/PermissionLookup.java`, `.../broadcast/LuckPermsBinding.java`,
`.../broadcast/LuckPermsPermissionLookup.java`, `.../broadcast/OfflineBroadcastAudience.java`; modify
`platform/paper-plugin/build.gradle.kts`, `platform/paper-plugin/src/main/resources/paper-plugin.yml`

**Interfaces:**
```java
public interface PermissionLookup {
    String providerName();
    Set<UUID> matching(List<String> permissions, BroadcastArguments.Chain chain);
}
public static Optional<PermissionLookup> LuckPermsBinding.tryCreate(Plugin plugin)
```

**Live-server exception.** `LuckPermsPermissionLookup` queries another plugin's storage and
`LuckPermsBinding` exists to be verified by class loading, so neither can run under JUnit. They are
implemented directly and verified by hand (Task 8) plus the `javap` check below. `PermissionLookup`
itself is an interface with no logic.

- [ ] Add `compileOnly("net.luckperms:api:5.4")` to `platform/paper-plugin/build.gradle.kts`
- [ ] Implement `PermissionLookup`, then `LuckPermsPermissionLookup` with the three stages: granting
      groups from `GroupManager#getLoadedGroups()` filtered by resolved `checkPermission`; members via
      `searchAll(NodeMatcher.key(InheritanceNode.builder(group).build()))`; direct holders via
      `searchAll(NodeMatcher.key(node))` with `getValue()`/`hasExpired()` filtering; the `default`-group
      fallback to `getUniqueUsers()`; `Chain.OR` unions and `Chain.AND` intersects per-node sets.
- [ ] Implement `LuckPermsBinding.tryCreate` naming no LuckPerms type in any signature, guarded by
      `isPluginEnabled("LuckPerms")` and catching `LinkageError`.
- [ ] Implement `OfflineBroadcastAudience` — delegate to the lookup, drop currently-online UUIDs.
- [ ] Add the `LuckPerms` entry to `paper-plugin.yml` (`required: false`, `join-classpath: true`)
- [ ] Run `./gradlew :platform:paper-plugin:build`
- [ ] Verify the guard holds: `javap -c -p .../LuckPermsBinding.class | grep -i luckperms` matches string
      literals only
- [ ] Commit

## Task 7: threading, command wiring and messages

**Files:** modify `.../broadcast/BroadcastAudience.java`, `.../broadcast/OnlineBroadcastAudience.java`,
`.../command/BroadcastCommand.java`, `.../PlayerNotificationsPlugin.java`,
`.../localisation/MessageKeys.java`, `platform/paper-plugin/src/main/resources/messages.yml`

**Interfaces:** `List<UUID> BroadcastAudience.resolve(List<String> permissions, Chain chain)`

**Live-server exception** for `BroadcastCommand`, `OnlineBroadcastAudience` and the plugin wiring, as
today. `MessageKeysTest` still covers the new keys in both directions and runs under JUnit.

- [ ] Add the seven keys to `MessageKeys` and `messages.yml`
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MessageKeysTest"` — expect PASS (it fails if a
      constant and its YAML line disagree, which is the check being relied on)
- [ ] Implement: `Chain` on `BroadcastAudience#resolve`; `OnlineBroadcastAudience` takes a `Plugin` and
      marshals via `callSyncMethod` with an `isPrimaryThread()` short-circuit; `BroadcastCommand` moves
      `resolve` into the async task, applies the `--limit` check before any send, and selects the
      persistent or transient path; `PlayerNotificationsPlugin` swaps `registerPayloadMapping` for
      `registerJsonRenderable`, attempts the binding, and builds both audiences plus
      `PersistentBroadcaster`.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect no regression
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 8: `CLAUDE.md` and the manual checklist

**Files:** modify `CLAUDE.md`

- [ ] Update the Broadcast section, Player commands, and the test counts in Current state
- [ ] Record the manual checklist below as unrun
- [ ] Run `./gradlew build` and the three non-Docker suites once more
- [ ] Commit

### Manual verification (needs `runServer` + a real LuckPerms install) — NOT RUN

1. `/broadcast hello` — unchanged behaviour, transient, reaches online players.
2. `/broadcast hello --perm a --perm b --chain and` — only players holding both match.
3. `/broadcast hello --limit 1` with two matching players — refused, naming the count 2.
4. `/broadcast hello --limit 0` — rejected as an invalid limit.
5. `/broadcast hello --offline` — rejected, requires `--persistent`.
6. `/broadcast hello --offline --persistent` — rejected, requires `--perm`.
7. `/broadcast hello --persistent` — lands in `/notifications` for an online recipient and is marked seen
   after the live push, so it is not re-pushed on rejoin.
8. `/broadcast hello --offline --persistent --perm <node>` with an offline holder — appears in their
   inbox on next join and is pushed then.
9. The same on a server with **no** LuckPerms — `--offline` replies "not available", the rest still works.
10. LuckPerms present but the node held only via a group — the offline holder is still matched (stage 1).
11. A recipient who silenced `broadcast` — stored, not pushed; with `--bypass`, delivered via chat and
    still unread in the inbox.
12. A muted recipient — same as 11.
13. Remove the LuckPerms jar and restart — plugin enables cleanly, `--offline` unavailable.
