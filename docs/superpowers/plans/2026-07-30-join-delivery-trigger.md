# Join-Delivery Trigger Implementation Plan

**Goal:** Deliver a player's due notifications on join, gated by a `settings.yml` toggle that
`/notifications reload` can flip without a restart.
**Spec:** `docs/superpowers/specs/2026-07-30-join-delivery-trigger-design.md`

## Task 1: `PluginSettings` gains the two keys

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PluginSettings.java`
- modify `platform/paper-plugin/src/main/resources/settings.yml`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/PluginSettingsTest.java`

**Interfaces this task produces:**
```java
PluginSettings(long pruneIntervalSeconds, List<String> defaultMedia,
               boolean deliverOnJoin, long joinDeliveryDelaySeconds)
boolean PluginSettings.deliverOnJoin()
long PluginSettings.joinDeliveryDelaySeconds()
```

- [ ] Write the failing test `PluginSettingsTest`:

```java
package io.github.md5sha256.playernotifications.paper;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSettingsTest {

    private static PluginSettings load(String yaml) throws ConfigurateException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .build();
        ConfigurationNode root = loader.load();
        PluginSettings settings = root.get(PluginSettings.class);
        assertNotNull(settings);
        return settings;
    }

    @Test
    void readsJoinDeliveryKeys() throws Exception {
        PluginSettings settings = load("""
                prune-interval-seconds: 3600
                default-media:
                  - chat
                deliver-on-join: true
                join-delivery-delay-seconds: 5
                """);
        assertTrue(settings.deliverOnJoin());
        assertEquals(5L, settings.joinDeliveryDelaySeconds());
    }

    @Test
    void readsDisabledJoinDelivery() throws Exception {
        PluginSettings settings = load("""
                default-media:
                  - chat
                deliver-on-join: false
                join-delivery-delay-seconds: 3
                """);
        assertFalse(settings.deliverOnJoin());
    }

    @Test
    void preservesZeroDelay() throws Exception {
        PluginSettings settings = load("""
                default-media:
                  - chat
                deliver-on-join: true
                join-delivery-delay-seconds: 0
                """);
        assertEquals(0L, settings.joinDeliveryDelaySeconds());
    }

    @Test
    void clampsNegativeDelayToZero() throws Exception {
        PluginSettings settings = load("""
                default-media:
                  - chat
                deliver-on-join: true
                join-delivery-delay-seconds: -10
                """);
        assertEquals(0L, settings.joinDeliveryDelaySeconds());
    }

    @Test
    void nonPositivePruneIntervalFallsBackToAnHour() throws Exception {
        PluginSettings settings = load("""
                prune-interval-seconds: 0
                default-media:
                  - chat
                """);
        assertEquals(3600L, settings.pruneIntervalSeconds());
    }
}
```

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*PluginSettingsTest"` — expect FAIL:
      `deliverOnJoin()` and `joinDeliveryDelaySeconds()` do not exist, so the test does not compile.
- [ ] Implement: add to `PluginSettings` the components
      `@Setting("deliver-on-join") boolean deliverOnJoin` and
      `@Setting("join-delivery-delay-seconds") long joinDeliveryDelaySeconds`. Neither takes
      `@Required` — they are primitives and cannot deserialize to `null`. In the compact constructor,
      after the existing prune clamp, add `if (joinDeliveryDelaySeconds < 0) {
      joinDeliveryDelaySeconds = 0; }`. Extend the record javadoc with both new `@param` lines,
      noting that `0` means "immediately, on the next async tick".
- [ ] Implement: append to `settings.yml`:

```yaml
# Whether a player's due notifications are delivered when they join the server.
# Turn this off to leave notifications pending until some other trigger delivers them.
deliver-on-join: true

# How long after a player joins their notifications are delivered, in seconds.
# A short delay keeps notifications from being buried under the join and MOTD messages.
# 0 delivers as soon as the join event has been handled.
join-delivery-delay-seconds: 3
```

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*PluginSettingsTest"` — expect PASS (5 tests)
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: `JoinDeliveryListener`

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/JoinDeliveryListener.java`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/JoinDeliveryListenerTest.java`

**Interfaces this task produces:**
```java
JoinDeliveryListener(Plugin plugin, Supplier<NotificationDelivery> delivery,
                     boolean enabled, long delaySeconds)
void JoinDeliveryListener.reloadSettings(boolean enabled, long delaySeconds)
boolean JoinDeliveryListener.enabled()          // package-private, for the test
long JoinDeliveryListener.delaySeconds()        // package-private, for the test
void JoinDeliveryListener.deliver(UUID target)  // package-private; the scheduled body
```

- [ ] Write the failing test `JoinDeliveryListenerTest`. `PlayerJoinEvent` and the Bukkit scheduler
      need a live server, so the test covers the gate and the settings swap only — it constructs the
      listener with a `null` `Plugin`, which is never dereferenced on the paths exercised:

```java
package io.github.md5sha256.playernotifications.paper;

import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinDeliveryListenerTest {

    private final AtomicInteger supplierCalls = new AtomicInteger();
    private final Supplier<NotificationDelivery> delivery = () -> {
        this.supplierCalls.incrementAndGet();
        return null;
    };

    @Test
    void reportsConstructedSettings() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, true, 3L);
        assertTrue(listener.enabled());
        assertEquals(3L, listener.delaySeconds());
    }

    @Test
    void reloadSettingsReplacesBoth() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, true, 3L);
        listener.reloadSettings(false, 30L);
        assertFalse(listener.enabled());
        assertEquals(30L, listener.delaySeconds());
    }

    @Test
    void deliverDoesNothingWhenDisabled() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, false, 0L);
        listener.deliver(UUID.randomUUID());
        assertEquals(0, this.supplierCalls.get());
    }

    @Test
    void deliverIsSkippedAfterAReloadTurnsItOff() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, true, 30L);
        listener.reloadSettings(false, 30L);
        listener.deliver(UUID.randomUUID());
        assertEquals(0, this.supplierCalls.get());
    }
}
```

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*JoinDeliveryListenerTest"` — expect FAIL:
      `JoinDeliveryListener` does not exist.
- [ ] Implement `JoinDeliveryListener`:
  - `implements org.bukkit.event.Listener`.
  - Final fields `Plugin plugin`, `Supplier<NotificationDelivery> delivery`; `volatile boolean
    enabled`; `volatile long delaySeconds`. Javadoc the `Supplier` choice ("`reload()` replaces the
    `NotificationDelivery`, so a captured reference goes stale") and the `volatile` choice ("mirrors
    `DatabaseNotificationPreferences.reloadDefaultMedia`: apply a reload to an object other code
    already holds, rather than reconstructing it").
  - `reloadSettings(boolean, long)` assigns both fields, clamping a negative delay to `0`.
  - `@EventHandler public void onJoin(PlayerJoinEvent event)`: return immediately if `!this.enabled`;
    otherwise capture `Player player = event.getPlayer()` and schedule
    `() -> { if (player.isOnline()) { deliver(player.getUniqueId()); } }` — via
    `runTaskAsynchronously` when `delaySeconds == 0`, else `runTaskLaterAsynchronously` with
    `this.delaySeconds * 20L` ticks. Comment the async requirement (blocking JDBC;
    `DiscordDmSink` refuses the main thread) and the `isOnline()` re-check (an offline `Audience`
    still makes `ChatSink` report `DELIVERED`, and DELETE-wins would then drop the notification).
  - `void deliver(UUID target)` (package-private): re-read `enabled` and return if false — a reload
    during a pending delay cancels that delivery. Then
    `try { this.delivery.get().deliver(target); } catch (RuntimeException ex) { log at WARNING naming
    the target; }`. Nothing is sent to the player: this is not a diagnostic they asked for.
  - Package-private `enabled()` / `delaySeconds()` accessors, javadoc'd as test seams.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*JoinDeliveryListenerTest"` — expect PASS
      (4 tests)
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: Wire it into the plugin and `/notifications reload`

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`
  (field block near `pruneTask` at ~`:62-65`; `onEnable` at ~`:184`; `reload` at ~`:276`)

**Interfaces:** none new — this task only calls Task 1's and Task 2's.

- [ ] No new test: this is Bukkit registration, verified by hand below.
- [ ] Implement: add a `private JoinDeliveryListener joinDeliveryListener;` field beside `pruneTask`.
- [ ] Implement: in `onEnable`, immediately before `schedulePruneTask(...)` (both are "start the
      things that cause work to happen", and `notificationDelivery` exists by then):

```java
// Always registered, gated internally: /notifications reload can then flip deliver-on-join without
// re-registering the listener. A supplier, not the instance — reload() replaces notificationDelivery.
this.joinDeliveryListener = new JoinDeliveryListener(
        this, () -> this.notificationDelivery,
        pluginSettings.deliverOnJoin(), pluginSettings.joinDeliveryDelaySeconds());
getServer().getPluginManager().registerEvents(this.joinDeliveryListener, this);
```

- [ ] Implement: in `reload`, immediately after `this.preferences.reloadDefaultMedia(...)`:

```java
this.joinDeliveryListener.reloadSettings(
        newSettings.deliverOnJoin(), newSettings.joinDeliveryDelaySeconds());
```

- [ ] Implement: extend `reload`'s javadoc to say it also refreshes the join-delivery toggle and delay.
- [ ] Run `./gradlew build`
- [ ] Manual verification with `./gradlew :platform:paper-plugin:runServer` (needs a reachable MariaDB
      per `database.yml`):
  1. Join, run `/notifications test hello`, and confirm the notification arrives — the pre-existing
     path still works.
  2. Set `join-delivery-delay-seconds: 30`, `/notifications reload`, run `/notifications test hello`,
     then quit within 30s. Confirm the console logs no error.
  3. Rejoin. Confirm the pending notification is delivered ~30s after join, proving the join trigger
     fires and that quitting mid-delay did not consume it.
  4. Set `deliver-on-join: false`, `/notifications reload`, enqueue with `/notifications test`, quit
     before it delivers, rejoin. Confirm nothing arrives on join.
  5. Set `deliver-on-join: true`, `/notifications reload` (no restart), rejoin. Confirm delivery
     resumes.
  6. Set `join-delivery-delay-seconds: 0`, `/notifications reload`, rejoin with a pending
     notification. Confirm it arrives immediately.
- [ ] Commit

## Task 4: Update `CLAUDE.md`

**Files:** modify `CLAUDE.md`

- [ ] Implement: under "Configuration", extend the `settings.yml` → `PluginSettings` bullet with
      `deliver-on-join` (default `true`) and `join-delivery-delay-seconds` (default 3, `0` = immediate,
      negative clamped to 0).
- [ ] Implement: under "Current state", rewrite the "**Only `/notifications test` calls
      `deliver(UUID)`**" gap — join delivery now exists via `JoinDeliveryListener`, so the two callers
      are that listener and `TestNotificationSender`, and the remaining gap is that an *already-online*
      player's newly-enqueued notification still waits for their next join.
- [ ] Implement: under "Player commands", note that `/notifications reload` also refreshes the
      join-delivery toggle and delay.
- [ ] Implement: bump the test-count baseline in "Testing gotchas" from 29 to 38 in
      `:platform:paper-plugin:test` and from 260 to 269 in total (confirm against an actual
      `./gradlew test` result count, globbing `*.xml` not `TEST-*.xml`).
- [ ] Implement: add `JoinDeliveryListener` to the `platform:paper-plugin` module bullet's description
      of what `onEnable` registers.
- [ ] Run `./gradlew build`
- [ ] Commit
