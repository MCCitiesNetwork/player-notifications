# `/notifications link discord` Implementation Plan

**Goal:** Move Discord account linking from the module's own `/discordlink` root command to
`/notifications link discord`, via a new `AccountLinkRegistry` extension point in `api`.
**Spec:** `docs/superpowers/specs/2026-07-30-account-link-subcommand-design.md`

**Baseline (measured before starting):** `:api:test` 23, `:platform:paper-plugin:test` 21,
`:platform:discord-adapter:test` 119, `:core:test` 77 — 240 total.

## Task 1: `AccountLinkProvider` + `AccountLinkRegistry` in `api`

**Files:**
- create `api/src/main/java/io/github/md5sha256/playernotifications/api/link/AccountLinkProvider.java`
- create `api/src/main/java/io/github/md5sha256/playernotifications/api/link/AccountLinkRegistry.java`
- create `api/src/test/java/io/github/md5sha256/playernotifications/api/link/AccountLinkRegistryTest.java`

**Interfaces:**
```java
public interface AccountLinkProvider {
    @NotNull String providerKey();
    default @NotNull Component displayName();
    @NotNull Component begin(@NotNull UUID playerUuid);
    @NotNull Component status(@NotNull UUID playerUuid);
    @NotNull Component unlink(@NotNull UUID playerUuid);
}
public class AccountLinkRegistry {
    public void registerProvider(@NotNull AccountLinkProvider provider);
    public void unregisterProvider(@NotNull String providerKey);
    public @NotNull Optional<AccountLinkProvider> getProvider(@NotNull String providerKey);
    public @NotNull Set<String> registeredProviders();
}
```

- [ ] Write the failing tests: registration then lookup; lookup is case-insensitive
      (`getProvider("DISCORD")` finds a provider keyed `discord`); `unregisterProvider` removes it;
      `registeredProviders()` returns the lower-cased keys; re-registering the same key replaces;
      `displayName()` defaults to `"Discord"` for key `discord` and `"Essentials Mail"` for
      `essentials-mail`.
- [ ] Run `./gradlew :api:test --tests "*AccountLinkRegistryTest*"` — expect FAIL: classes do not exist.
- [ ] Implement both types, modelled on `NotificationSinkRegistry` (synchronized `HashMap`) and
      `NotificationSink#displayName()` (title-casing of the key with `-` → space).
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew :api:test` — expect 23 + new, all green.
- [ ] Commit.

## Task 2: `AccountLinkDispatcher` in `paper-plugin`

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/AccountLinkDispatcher.java`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/command/AccountLinkDispatcherTest.java`

**Interfaces:**
```java
public final class AccountLinkDispatcher {
    public enum Action { BEGIN, STATUS, UNLINK }
    public AccountLinkDispatcher(@NotNull AccountLinkRegistry registry, @NotNull Logger logger);
    public @NotNull Component listProviders();
    public @NotNull Component dispatch(@NotNull String providerKey, @NotNull UUID playerUuid,
                                       @NotNull Action action);
    public @NotNull Collection<String> suggestions();
}
```

- [ ] Write the failing tests, with a fake `AccountLinkProvider` recording which method was called:
      each `Action` routes to the matching provider method and returns its `Component`; an unknown key
      returns a message containing "not available"; `listProviders()` on an empty registry says linking
      is unavailable; `listProviders()` with one provider names it; a provider whose method throws
      `RuntimeException` yields a generic reply rather than propagating; `suggestions()` mirrors
      `registeredProviders()`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*AccountLinkDispatcherTest*"` — expect FAIL:
      class does not exist.
- [ ] Implement `AccountLinkDispatcher`.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect 21 + new, all green.
- [ ] Commit.

## Task 3: Wire `link` into `/notifications`

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/NotificationsCommand.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`

**Interfaces:**
```java
// NotificationsCommand
public static LiteralCommandNode<CommandSourceStack> create(PreferenceDialogRouter router,
        Consumer<CommandSender> reloadAction, TestNotificationSender testSender,
        AccountLinkDispatcher linkDispatcher);
// PlayerNotificationsPlugin
public @NotNull AccountLinkRegistry accountLinkRegistry();
```

**Live-server exception:** the Brigadier node wiring cannot be unit tested (no server). Task 2 is the
tested seam; this task is wiring only. Verified manually in Task 6.

- [ ] Add the `accountLinkRegistry` field, construct it in `onEnable` before `registerCommands()`, add
      the accessor.
- [ ] Add the `link` subtree to `NotificationsCommand.create` per the spec: bare `link` →
      `listProviders()`; `Commands.argument("provider", StringArgumentType.word())` with a suggestion
      provider reading `linkDispatcher.suggestions()`; bare and `status` children under `link`, and a
      sibling `unlink` literal taking the same provider argument — the three `Action`s. All player-only, all dispatched via `Bukkit.getScheduler().runTaskAsynchronously`.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect 21 + Task 2's, still green.
- [ ] Run `./gradlew build` — expect success.
- [ ] Commit.

## Task 4: `DiscordAccountLinkProvider`, and delete `DiscordLinkCommand`

**Files:**
- create `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/DiscordAccountLinkProvider.java`
- create `platform/discord-adapter/src/test/java/io/github/md5sha256/playernotifications/discord/DiscordAccountLinkProviderTest.java`
- delete `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/DiscordLinkCommand.java`
- delete `platform/discord-adapter/src/test/java/io/github/md5sha256/playernotifications/discord/DiscordLinkCommandTest.java`
- modify `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/DiscordModule.java`
- modify `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/DiscordMedia.java`
  (add `LINK_PROVIDER_KEY = "discord"`)

**Interfaces:**
```java
public final class DiscordAccountLinkProvider implements AccountLinkProvider {
    public DiscordAccountLinkProvider(@NotNull DiscordLinkFlow flow);
}
```

- [ ] Write the failing tests: `providerKey()` is `"discord"`; `displayName()` renders as `"Discord"`;
      `begin`/`status`/`unlink` each return exactly what the underlying `DiscordLinkFlow` returns for
      the same UUID (built over the existing `FakeDiscordAccountLinkStore`).
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*DiscordAccountLinkProviderTest*"` —
      expect FAIL: class does not exist.
- [ ] Implement `DiscordAccountLinkProvider` and the `DiscordMedia.LINK_PROVIDER_KEY` constant.
- [ ] Run the same command — expect PASS.
- [ ] Delete `DiscordLinkCommand` and `DiscordLinkCommandTest`. In `DiscordModule`, replace
      `registerLinkCommand` with `plugin.accountLinkRegistry().registerProvider(...)`, rename
      `linkCommandRegistered` → `linkProviderRegistered`, drop `permissionRegistered` and both
      `PluginManager` permission calls, and unregister the provider in `shutdown`.
- [ ] Run `./gradlew :platform:discord-adapter:test` — expect 119 − 4 (deleted) + new, all green.
      **Needs a running Docker daemon.**
- [ ] Run `./gradlew build` — expect success.
- [ ] Commit.

## Task 5: Update player-facing strings

**Files:**
- modify `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/DiscordLinkFlow.java`
- modify `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/LinkSlashCommandListener.java`
- modify `platform/discord-adapter/src/main/resources/discord.yml`
- modify `platform/discord-adapter/src/test/java/io/github/md5sha256/playernotifications/discord/DiscordLinkFlowTest.java`

- [ ] Write the failing test in `DiscordLinkFlowTest`: the already-linked reply, the not-linked
      `status` reply and the outstanding-code `status` reply all name `/notifications link discord`
      and never `/discordlink`.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*DiscordLinkFlowTest*"` — expect FAIL:
      the messages still say `/discordlink`.
- [ ] Replace every `/discordlink` occurrence in `DiscordLinkFlow`, `LinkSlashCommandListener` and the
      `discord.yml` comments with the new form (`/notifications link discord`,
      `… discord status`, `… discord unlink`). Sweep the remaining javadoc references in
      `DiscordAccountLinkStore`, `DiscordSettings`, `EmbeddedDiscordAccountProvider`, `LinkCodeService`
      in the same pass.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew :platform:discord-adapter:test` — all green.
- [ ] Commit.

## Task 6: Docs and manual verification

**Files:** modify `CLAUDE.md`

- [ ] Update "Player commands": add `/notifications link [provider] [status|unlink]`, remove the
      `/discordlink is not part of this tree` paragraph and replace it with the registry rationale
      (recording that the earlier decision was reversed and why).
- [ ] Update "Discord adapter": the `/discordlink is module-owned` bullet becomes the
      `AccountLinkProvider` registration; drop the `getKnownCommands()` unregister paragraph and the
      `playernotifications.discord.link` permission.
- [ ] Update "Module architecture" → `api` to list the new `api.link` package.
- [ ] Update the "Current state" test-count line to the measured totals.
- [ ] Run `./gradlew build` and the full `./gradlew test` — record the counts.
- [ ] Manual check with `./gradlew :platform:paper-plugin:runServer`: `/notifications link` lists
      Discord; `/notifications link discord` issues a code; `… status` and `… unlink` behave;
      tab completion suggests `discord`; `/discordlink` no longer resolves. Report as manually
      verified.
- [ ] Commit.
