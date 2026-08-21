# Broadcast command Implementation Plan

**Goal:** Add `/broadcast <content> --perm [perm] … [--bypass]`, delivered immediately through each matching online player's preferred media and never stored, honouring the player's mute and `broadcast` silence unless `--bypass` is given.
**Spec:** `docs/superpowers/specs/2026-08-21-broadcast-command-design.md`

## Task 1: Argument parsing

**Files:** create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/broadcast/BroadcastArguments.java`; create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/broadcast/BroadcastArgumentsTest.java`
**Interfaces:**
```java
public record BroadcastArguments(@NotNull String content, @NotNull List<String> permissions,
                                 boolean bypass) {
    public static Result parse(@NotNull String raw);
    public sealed interface Result {
        record Parsed(@NotNull BroadcastArguments arguments) implements Result {}
        record Invalid(@NotNull String message) implements Result {}
    }
}
```

- [ ] Write the failing test `BroadcastArgumentsTest` with cases:
      `parse("hello world")` → `Parsed`, content `"hello world"`, permissions empty;
      `parse("hi --perm a.b")` → content `"hi"`, permissions `[a.b]`;
      `parse("hi --perm a --perm b")` → permissions `[a, b]`;
      `parse("hi --perm a --perm a")` → permissions `[a]` (de-duplicated, order preserved);
      `parse("hi --perm")` → `Invalid`, message mentions `--perm`;
      `parse("hi --perm --perm a")` → `Invalid`;
      `parse("hi --perm a junk")` → `Invalid`, message contains `junk`;
      `parse("--perm a")` → `Invalid` (blank content);
      `parse("   ")` → `Invalid`;
      `parse("hi   there  --perm  a")` → content `"hi   there"` (interior spacing preserved, trailing trimmed);
      `parse("hi --bypass")` → content `"hi"`, permissions empty, `bypass` true;
      `parse("hi --bypass --perm a")` and `parse("hi --perm a --bypass")` → both content `"hi"`, permissions `[a]`, `bypass` true;
      `parse("hi --bypass --bypass")` → `Parsed`, `bypass` true (idempotent, not an error);
      `parse("hi")` → `bypass` false;
      `parse("--bypass hi")` → `Invalid` (blank content — the flag region starts at the first flag token);
      `parse("hi --bypass junk")` → `Invalid`, message contains `junk`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcastArgumentsTest"` — expect FAIL: `BroadcastArguments` does not exist.
- [ ] Implement `parse`: locate the first whitespace-delimited token equal to `--perm` or `--bypass`; content is `raw.substring(0, tokenStart).trim()` (so interior spacing survives); walk the remaining tokens with an index — `--bypass` sets the flag and advances one, `--perm` consumes the next token as a value (rejecting a missing one and one starting with `--`), anything else is rejected naming the token; collect values into a `LinkedHashSet`; reject blank content.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 2: Audience selection and the audience seam

**Files:** create `.../paper/broadcast/BroadcastRecipients.java`; create `.../paper/broadcast/BroadcastAudience.java`; create `.../paper/broadcast/OnlineBroadcastAudience.java`; create `.../paper/broadcast/BroadcastRecipientsTest.java`
**Interfaces:**
```java
public final class BroadcastRecipients {
    public record Candidate(@NotNull UUID uuid, @NotNull Predicate<String> hasPermission) {}
    public static List<UUID> select(@NotNull Collection<Candidate> candidates,
                                    @NotNull List<String> permissions);
}

@FunctionalInterface
public interface BroadcastAudience {
    @NotNull List<UUID> resolve(@NotNull List<String> permissions);
}

public final class OnlineBroadcastAudience implements BroadcastAudience {
    public OnlineBroadcastAudience(@NotNull Server server);
}
```

- [ ] Write the failing test `BroadcastRecipientsTest`: an empty permission list returns every candidate in iteration order; with `["a", "b"]`, a candidate holding only `b` is selected and one holding neither is not; a candidate holding both appears exactly once; an empty candidate collection returns an empty list.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcastRecipientsTest"` — expect FAIL: class does not exist.
- [ ] Implement `BroadcastRecipients.select`: filter candidates, keeping one whose `permissions.isEmpty()` or whose predicate accepts any listed permission; map to `uuid`; return an unmodifiable list.
- [ ] Run the same command — expect PASS.
- [ ] Implement `BroadcastAudience` — one method, javadoc'd as the swap point for a future offline-capable implementation, naming the open questions in the spec's "Room for offline delivery" so a reader finds them from here.
- [ ] Implement `OnlineBroadcastAudience`: map `server.getOnlinePlayers()` to `Candidate(p.getUniqueId(), p::hasPermission)` and delegate to `BroadcastRecipients.select`. Javadoc the main-thread requirement (Bukkit permission state) and that it is deliberately a map-and-delegate so the untestable class holds no decisions. **No test** — it needs a live server; it is exercised by Task 4's checklist.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 3: Fan-out

**Files:** create `.../paper/broadcast/Broadcaster.java`; create `.../paper/broadcast/BroadcasterTest.java`; reference `.../paper/mail/MailNotifier.java` and `platform/paper-plugin/src/test/java/.../paper/mail/MailNotifierTest.java` for the shape and the fakes
**Interfaces:**
```java
public final class Broadcaster {
    public static final String BROADCAST_DATA_TYPE = "broadcast";
    public static final String FALLBACK_MEDIUM = "chat";
    public static final Component BROADCAST_TITLE = Component.text("Broadcast");
    public Broadcaster(@NotNull NotificationSinkRegistry sinks,
                       @NotNull NotificationPreferences preferences,
                       @NotNull Logger logger);
    public int broadcast(@NotNull Component content, @NotNull Collection<UUID> recipients,
                         boolean bypass);
}
```

- [ ] Write the failing test `BroadcasterTest`, reusing `MailNotifierTest`'s recording-sink and stub-preferences fixtures. Without `bypass`: a recipient preferring `chat` and `discord-dm` receives the notification on both, with title `BROADCAST_TITLE` and body the passed content; a recipient whose media resolve to `{none}` receives nothing and is **not** counted; a muted recipient receives nothing and is **not** counted; a preferred medium with no registered sink is skipped and the others still deliver; a sink throwing `RuntimeException` does not prevent the second sink delivering; the return value equals the number of recipients actually attempted. With `bypass`: the `{none}` recipient receives it on `chat` and is counted; the muted recipient with no usable media receives it on `chat`; a muted recipient preferring `discord-dm` receives it on `discord-dm` and **not** on `chat` (bypass overrides the suppression, not the choice of medium); a recipient with real media is unaffected by the flag.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*BroadcasterTest"` — expect FAIL: class does not exist.
- [ ] Implement `broadcast` mirroring `MailNotifier.notifyArrival` per recipient: skip on `preferences.isMuted` unless `bypass`; `preferredMedia(uuid, BROADCAST_DATA_TYPE)` into a `LinkedHashSet`; `remove(NotificationPreferences.SILENCED_MEDIUM)`; when the set is now empty, skip the recipient uncounted unless `bypass`, in which case use `Set.of(FALLBACK_MEDIUM)`; for each medium `sinks.getSink(medium)`, `fine`-log and skip when empty, else deliver `new RenderableNotification(BROADCAST_TITLE, content)` inside a `try`/`catch (RuntimeException)` that logs a `warning`; count the recipient.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 4: Command wiring, payload mapping and permission

**Files:** create `.../paper/command/BroadcastCommand.java`; create `.../paper/broadcast/BroadcastPayload.java`; modify `.../paper/PlayerNotificationsPlugin.java` (`onEnable`, `registerCommands`); modify `platform/paper-plugin/src/main/resources/paper-plugin.yml`; modify `platform/paper-plugin/src/main/resources/categories.yml`
**Interfaces:**
```java
public final class BroadcastCommand {
    public static final String PERMISSION = "playernotifications.command.broadcast";
    public static final String DESCRIPTION = "Broadcast a message to online players";
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull Plugin plugin,
                                                                @NotNull Broadcaster broadcaster,
                                                                @NotNull BroadcastAudience audience);
}
```

**Live-server task — no automated test.** The Brigadier node, the op-only gate and `Bukkit.getOnlinePlayers()` all need a running server; every decision it makes was tested in Tasks 1–3.

- [ ] Implement `create`: `Commands.literal("broadcast").requires(source -> source.getSender().hasPermission(PERMISSION))` with a greedy `content` argument. On the command thread: `BroadcastArguments.parse`, replying red on `Invalid`; `MiniMessage.miniMessage().deserialize(content)` inside a `try`/`catch (RuntimeException)` replying red with the message; call `audience.resolve(arguments.permissions())`; reply "No online player matched those permissions." and return when empty. Otherwise `plugin.getServer().getScheduler().runTaskAsynchronously` calling `broadcaster.broadcast(content, recipients, arguments.bypass())` and replying "Broadcast sent to N player(s)." — or, when it returns 0, "No recipient had broadcasts enabled." (a hint that `--bypass` overrides it).
- [ ] Create `BroadcastPayload`: `public record BroadcastPayload(@NotNull String message) {}`, javadoc'd as a mapping key that is never enqueued, serialized or rendered — it exists so `broadcast` appears in the preference dialogs and can be silenced.
- [ ] Register the mapping in `onEnable`, beside the `TestNotificationPayload` registration and **before** `registerCommands(...)`: `this.notificationService.dataTypeRegistry().registerPayloadMapping(Broadcaster.BROADCAST_DATA_TYPE, BroadcastPayload.class);`
- [ ] Add a `broadcast` category to `categories.yml`: label `"Broadcasts"`, description naming `/broadcast` and saying that a broadcast marked `--bypass` is delivered regardless of this setting, `types: [broadcast]`.
- [ ] Wire it in `registerCommands`: construct `new Broadcaster(this.sinkRegistry, this.preferences, getLogger())` and `new OnlineBroadcastAudience(getServer())`, and register the node alongside `/mail` in the same `LifecycleEvents.COMMANDS` handler, using `BroadcastCommand.DESCRIPTION`.
- [ ] Add to `paper-plugin.yml` under `permissions:`, with a comment saying a broadcast reaches every online player and is not stored:
      `playernotifications.command.broadcast: {description: Broadcast a message to online players with /broadcast., default: op}` (in the file's block style, not inline).
- [ ] Run `./gradlew build`.
- [ ] Manual verification with `./gradlew :platform:paper-plugin:runServer`:
      1. As op, `/broadcast hello` — the message arrives in chat with the "Broadcast" title styling.
      2. As a non-op, `/broadcast hello` — the command is not visible and is refused.
      3. `/broadcast <red>hi</red>` — arrives red; `/broadcast §chi` — replies with a parse error and sends nothing.
      4. `/broadcast hi --perm some.node` with the node granted to one of two online players — only that player receives it.
      5. `/broadcast hi --perm a --perm b` with each of two players holding one node — both receive it.
      6. `/broadcast hi --perm nobody.has.this` — replies "No online player matched those permissions."
      7. `/broadcast hi --perm` — replies with the malformed-flag error.
      8. From the console, `/broadcast hi` — works and reports the count.
      9. With a player `/notifications mute`d — they receive nothing and the reported count excludes them; `/broadcast hi --bypass` then reaches them.
      10. Open `/notifications preferences types` — a "Broadcasts" category is listed; silence `broadcast` from it. `/broadcast hi` no longer reaches that player; `/broadcast hi --bypass` reaches them in chat.
      11. With every online player muted, `/broadcast hi` replies "No recipient had broadcasts enabled."
      12. With the Discord adapter installed and a linked account preferring `discord-dm` for `broadcast`, and that player muted — `/broadcast hi --bypass` arrives as a **DM**, not in chat.
      13. With the Discord adapter installed and a linked account whose `default-media` includes `discord-dm` — the broadcast arrives as a DM.
      14. Confirm nothing appeared in `/notifications` afterwards, and `SELECT COUNT(*) FROM Notification` is unchanged.
- [ ] Commit.

## Task 5: Documentation

**Files:** modify `CLAUDE.md`

- [ ] Note in the "Broadcast" section that `BroadcastAudience` is the seam for future offline delivery, that `OnlineBroadcastAudience` is today's only implementation and must run on the main thread, and that the open questions are recorded in the spec's "Room for offline delivery" — including the `ChatSink`-reports-DELIVERED-for-an-offline-player trap.
- [ ] Add a "Broadcast" section after "Mail": what the command is, the OR permission semantics, that it is delivered through sinks and **never stored**, why it is not a `Notification`, that `"broadcast"` is registered as a payload **mapping only** (`BroadcastPayload`, so the type is silenceable in the dialogs) with no serializer, renderer or processor, that mute and silence both apply, and what `--bypass` overrides including the `chat` fallback.
- [ ] Add `/broadcast` to "Player commands" as a sibling tree note, the way `/mail` is noted there.
- [ ] Add the new permission to the permission list in that section and note `default: op`.
- [ ] Update the test-count baseline under "Testing gotchas" with the counts `./gradlew :platform:paper-plugin:test` actually reports.
- [ ] Record under "Current state" that `BroadcastCommand` is unverified by automated tests and that Task 4's manual checklist has (or has not) been run.
- [ ] Run `./gradlew build`.
- [ ] Commit.
