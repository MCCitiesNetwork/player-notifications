# Discord Adapter Implementation Plan

**Goal:** Add a `platform:discord-adapter` feature module that registers a `DiscordDmSink`
(medium key `discord-dm`) delivering notifications as Discord DMs via its own shaded JDA bot,
resolving Minecraft UUID → Discord id through legacy DiscordSRV behind a swappable
`DiscordAccountProvider` chain.

**Design:** `docs/superpowers/specs/2026-07-29-discord-adapter-design.md`.

**Tech stack:** Java 21, JDA 6.5.0 (shaded + relocated), legacy DiscordSRV 1.30.5 (compile-only, link
source only), Configurate, JUnit 5. No database change and no migration.

## Global constraints

- The module registers a **sink**, not a processor, so it participates in preferences and fan-out.
- No new table, no `MigrationStep`. `MariaSchemaMigrator` tracks one `schema_version` chain and
  `MariaDatabase` registers mappers from a hardcoded list, so a module cannot own a migration today.
- Config records: every non-null reference-typed `@Setting` field is also `@Required`; no
  `java.time.Duration` fields (Configurate 4.2.0 has no serializer) — `long` seconds instead.
- Counting test results: glob `*.xml`, not `TEST-*.xml` (Windows shortens `@Nested` filenames).
- `--tests "<pattern>"` reports BUILD SUCCESSFUL while matching nothing; check the result count.
- Tests touching Adventure/Bukkit types need `testRuntimeOnly` on `paper-api` in the module.

---

## Task 1: Spec + plan docs

- [x] Write the design doc, in the style of the notification-renderers design.
- [x] Write this plan.
- [x] Commit.

## Task 2: Gradle module skeleton

- [x] `include("platform:discord-adapter")` in `settings.gradle.kts`.
- [x] `build.gradle.kts`: `paper-adapter` convention + `com.gradleup.shadow` 9.3.1 (the version
      `paper-plugin` already uses); repo `https://nexus.scarsz.me/content/groups/public/`;
      `compileOnly("com.discordsrv:discordsrv:1.30.5")`,
      `implementation("net.dv8tion:JDA:6.5.0") { exclude(module = "opus-java") }`,
      an slf4j binding, and `testRuntimeOnly` on `paper-api`.
- [x] `shadowJar` relocating every bundled third-party package under
      `io.github.md5sha256.playernotifications.discord.libraries`. **Verify the actual set** from the
      built jar rather than trusting a guessed list — JDA 6's transitive set is not JDA 5's.
- [x] `module-manifest.yml` mirroring `essentials-adapter`'s.
- [x] **Verify JDA 6.5.0's API shapes early** (`JDABuilder.createLight`, `setEnabledIntents`,
      `JDA#openPrivateChannelById`, `MessageCreateData`, `EmbedBuilder`, `ErrorResponseException`).
      JDA 6 is a major release; doing this here makes a fallback to 5.x cost nothing.
- [x] `./gradlew :platform:discord-adapter:build`, then commit.

## Task 3: Component → Discord markdown

`public static String serialize(@NotNull Component component)`

- [x] Failing `DiscordMarkdownSerializerTest`: each decoration's markers; nested bold/italic;
      escaping of `* _ ~ | \``; colour dropped; newlines preserved; a decoration negated
      (`State.FALSE`) inside a decorated parent closes and reopens the run.
- [x] Implement with `ComponentFlattener` + a `FlattenerListener` maintaining a decoration stack.
- [x] Test passes, `./gradlew build` green, commit.

## Task 4: JDA message construction

```java
public enum DiscordMessageFormat { EMBED, MARKDOWN, PLAIN }
public final class DiscordMessageFactory {
    public DiscordMessageFactory(@NotNull DiscordMessageFormat format, int fallbackEmbedColor);
    public @NotNull MessageCreateData create(@NotNull RenderableNotification notification);
}
```

- [x] Failing `DiscordMessageFactoryTest` (offline): embed title/description/colour incl. the title
      component's own colour winning over the fallback; markdown content; plain content free of
      markers; and the three truncations (title 256, description 4096, message 2000).
- [x] Implement, delegating to `DiscordMarkdownSerializer` or `PlainTextComponentSerializer`.
- [x] Test passes, `./gradlew build` green, commit.

## Task 5: Account provider abstraction

- [x] Failing `ChainedDiscordAccountProviderTest`: first non-empty wins; empty falls through;
      unavailable skipped un-queried; throwing provider logged and skipped; unknown key warned and
      skipped; empty chain returns empty.
- [x] Implement `DiscordAccountProvider`, `DiscordAccountProviderRegistry`,
      `ChainedDiscordAccountProvider` (`providerKey()` is `"chain"`).
- [x] Test passes, `./gradlew build` green, commit.

## Task 6: The sink

- [x] Failing `DiscordDmSinkTest`: unlinked → `UNSUPPORTED`, messenger never called; linked →
      messenger gets that id, result passed through; messenger throwing → `UNREACHABLE`, never
      propagates; `mediumKey()` is `discord-dm`; `displayName()` is `"Discord DM"` (the interface
      default would give `"Discord Dm"`).
- [x] Implement `DiscordMedia`, `DiscordMessenger` (interface only), `DiscordDmSink` including the
      main-thread guard.
- [x] Test passes, `./gradlew build` green, commit.

## Task 7: JDA bot, config, and module wiring

**In the live-server exception** — JDA login, DiscordSRV lookup and module loading cannot be unit
tested. Verified manually per Task 8.

- [x] `discord.yml` bundled default with an empty `bot-token` and per-key comments.
- [x] `DiscordSettings` record (`bot-token`, `message-format`, `embed-color`,
      `delivery-timeout-seconds`, `link-providers`).
- [x] `ModuleConfigs.load(...)` — copy-on-first-run, merge new keys, save; the host's
      `copyDefaultsYaml` is private and reads the *host* jar's resources, so it cannot be reused.
- [x] `DiscordBot` — `createLight` with no intents, no `awaitReady()`, `jda()` empty until
      `CONNECTED`, `shutdown()` on stop.
- [x] `JdaDiscordMessenger` — `openPrivateChannelById(...).flatMap(sendMessage).submit().get(timeout)`;
      `CANNOT_SEND_TO_USER`/`UNKNOWN_USER` → `UNSUPPORTED`, everything else → `UNREACHABLE`.
- [x] `DiscordSrvAccountProvider` — cache lookup then blocking lookup, `LinkageError`-guarded
      availability check.
- [x] `DiscordModule` — blank token → `ModuleInitializationException`; build bot, registry, chain,
      factory, messenger, sink; register the sink; unregister and shut down on stop.
- [x] `paper-plugin.yml` — soft `dependencies: server: DiscordSRV` with `join-classpath: true`.
- [x] `./gradlew build` green, commit.

## Task 8: End-to-end manual verification — OUTSTANDING

Not performed. It needs a live Paper server, a real Discord bot token and a DiscordSRV-linked
account, none of which exist in the development environment. The throwaway delivery-trigger listener
is deliberately **not** committed. Checklist for the operator:

1. `./gradlew :platform:paper-plugin:build :platform:discord-adapter:build`.
2. Copy `platform/discord-adapter/build/libs/discord-adapter-<version>-all.jar` into
   `platform/paper-plugin/run/plugins/PlayerNotifications/modules/`.
3. Drop DiscordSRV 2.x into `run/plugins/`, configure and link it to a test Discord account.
4. Start once so `modules/discord.yml` is written, then put a real bot token in it and restart.
   The bot must share a guild with the linked account, or Discord refuses the DM.
5. `./gradlew :platform:paper-plugin:runServer`. In the log confirm: the module loaded, the
   `discord-dm` sink registered, JDA reached `CONNECTED`, and no `NoClassDefFoundError` from the
   relocation.
6. `/notifications media` in game — confirm **Discord DM** appears, and `discord-channel-ping` does
   **not**. Select it for a data type, Apply, and confirm a `medium = 'discord-dm'` row lands in
   `PlayerNotificationPreference`.
7. Nothing calls `NotificationDelivery.deliver(UUID)` yet (a pre-existing gap — see *Current state*
   in `CLAUDE.md`). To exercise delivery, patch a scratch `PlayerJoinEvent` listener calling
   `notificationDelivery().deliver(uuid)` into the host locally, enqueue a notification for the
   linked player, rejoin, and confirm the DM arrives as an embed. **Revert the scratch listener.**
8. Verify the failure paths by hand: an unlinked player → one `UNSUPPORTED` warning and the
   notification retained; the bot blocked by the user → `UNSUPPORTED`; the bot offline →
   `UNREACHABLE` with the notification surviving to the next pass.

## Task 9: Docs

- [x] `CLAUDE.md` — add `platform:discord-adapter` to the module list and the `settings.gradle.kts`
      line; add a "Discord adapter" subsection under *Rendering & delivery media*; note under
      *Current state* that linking is DiscordSRV-only with no in-game flow, and that Task 8 is
      outstanding.
- [x] Rename the medium key `"discord"` → `"discord-dm"` everywhere it is named as an example —
      `CLAUDE.md` (the `NotificationSinkRegistry` bullet and the persistence-layer example),
      `settings.yml`'s `default-media` comment, and **`NotificationSink`'s own javadoc** (two places).
      Record `discord-channel-ping` as reserved-but-unimplemented.
- [x] Update the test-baseline line with the new `:platform:discord-adapter:test` count.
- [x] `./gradlew build` and the full `./gradlew test`; record actual counts. Commit.
