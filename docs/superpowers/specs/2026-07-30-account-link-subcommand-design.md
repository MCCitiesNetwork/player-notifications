# Account linking under `/notifications link` — design

**Date:** 2026-07-30
**Status:** approved

## Goal

Replace the Discord adapter's own root command `/discordlink` (alias `/dlink`) with
`/notifications link discord` and `/notifications unlink discord`, so account linking is discoverable from the command a player has already
learned. `/discordlink` and `/dlink` are removed outright — no deprecated alias.

This reverses a decision recorded in `CLAUDE.md` ("Player commands"), which noted that putting linking
under `/notifications` "would have meant giving the host an account-linking registry it has no other use
for". That registry is now built deliberately: it still has exactly one consumer, but it deletes more
host-adjacent machinery than it adds (the whole `CommandMap#getKnownCommands()` unregister path goes).

## Architecture

Linking joins the plugin's existing pattern — a capability is added by **registering an implementation**
against a registry, not by branching inside an existing class. Linking is keyed by *account provider*
(`"discord"`), a new axis, so it gets its own registry alongside `NotificationSinkRegistry` (media) and
`NotificationCategoryRegistry` (grouping).

### `api` — new package `io.github.md5sha256.playernotifications.api.link`

```java
public interface AccountLinkProvider {
    @NotNull String providerKey();                       // "discord"
    default @NotNull Component displayName();            // title-cased providerKey()
    @NotNull Component begin(@NotNull UUID playerUuid);
    @NotNull Component status(@NotNull UUID playerUuid);
    @NotNull Component unlink(@NotNull UUID playerUuid);
}
```

The three action methods return the player-facing reply rather than sending it, matching
`DiscordLinkFlow`'s existing shape: the caller owns threading and the sender. They may block (JDBC), so
the host dispatches them off the main thread.

`displayName()` defaults to title-casing the key, mirroring `NotificationSink#displayName()`.

```java
public class AccountLinkRegistry {
    void registerProvider(@NotNull AccountLinkProvider provider);
    void unregisterProvider(@NotNull String providerKey);
    @NotNull Optional<AccountLinkProvider> getProvider(@NotNull String providerKey);
    @NotNull Set<String> registeredProviders();
}
```

Synchronized-map backed, following `NotificationSinkRegistry` exactly. Provider keys are matched
case-insensitively on lookup (`providerKey()` is lower-cased on registration), because a player typing
`/notifications link Discord` should not get "unknown provider".

**Not** added to `NotificationService`: it is not a notification concern, and `NotificationService` has
implementations in `core` and in tests that would all need changing. It is exposed the same way
`NotificationSinkRegistry` is — `PlayerNotificationsPlugin.accountLinkRegistry()`.

### `platform:paper-plugin`

- `PlayerNotificationsPlugin` constructs one `AccountLinkRegistry` in `onEnable` before
  `registerCommands()`, exposes it via `accountLinkRegistry()`.
- `paper.command.AccountLinkDispatcher` — the testable seam. Pure, no Bukkit:
  - `Component listProviders()` — the reply to a bare `/notifications link`. Names each registered
    provider's `displayName()` and the command to run; says linking is unavailable when none are
    registered.
  - `Component dispatch(String providerKey, UUID playerUuid, Action action)` where
    `Action` is an enum `BEGIN | STATUS | UNLINK`. Resolves the provider; an absent one returns
    "*X* linking is not available on this server." rather than throwing.
  - `Collection<String> suggestions()` — for tab completion.
- `NotificationsCommand.create` gains an `AccountLinkDispatcher` parameter and a `link` subtree:

  ```
  link
    ├─ (executes)                       → listProviders()
    └─ argument "provider" (word)       → suggests registeredProviders()
         ├─ (executes)                  → BEGIN
         └─ literal "status"            → STATUS
  unlink
    ├─ (executes)                       → listProviders()
    └─ argument "provider" (word)       → UNLINK
  ```

  `unlink` is a **sibling** of `link`, not a child of it: they are opposite operations and read as peers.
  (`status` stays under `link <provider>`, since it describes the link rather than performing one.)

  An **argument** node, not one literal per registered provider: the Brigadier tree is built once when
  Paper fires `LifecycleEvents.COMMANDS`, so static literals would freeze the provider set at that
  moment. The argument reads the registry on every dispatch, and its suggestions are computed live.

  Every branch is player-only and dispatched to the async scheduler, exactly as `DiscordLinkCommand.run`
  did.

Permission: the subtree inherits `/notifications`' existing `playernotifications.command.preferences`.
No new permission node; `playernotifications.discord.link` is removed.

### `platform:discord-adapter`

- **Delete** `DiscordLinkCommand` and `DiscordLinkCommandTest` entirely (126 + 51 lines), including
  `isOurs`, `unregister`, and the `Bukkit.getCommandMap().getKnownCommands()` surgery.
- **Add** `DiscordAccountLinkProvider implements AccountLinkProvider` — a thin delegate over
  `DiscordLinkFlow`, `providerKey()` = `DiscordMedia.LINK_PROVIDER_KEY` (`"discord"`),
  `displayName()` = "Discord".
- `DiscordModule.initialize` registers it via `plugin.accountLinkRegistry().registerProvider(...)` when
  `settings.usesEmbeddedProvider()`; `shutdown` unregisters it. Both guarded by the existing
  `linkCommandRegistered` flag (renamed `linkProviderRegistered`). The `permissionRegistered` flag and
  the `PluginManager` permission calls go away.
- `DiscordLinkFlow`'s player-facing strings change `/discordlink …` → `/notifications link discord …`.
  `LinkSlashCommandListener`'s Discord-side replies change the same way, as does `discord.yml`'s
  commentary.

## Behaviour after module shutdown

`/notifications link discord` stays in the command tree and replies "Discord linking is not available on
this server." The alternative — removing the node — needs mutation of an already-registered Brigadier
node's children, for which Paper exposes no API, and would re-introduce exactly the command-map hack this
change deletes. The chosen behaviour is also what a player on a server that never installed the Discord
module sees, so there is one code path, not two.

## Error handling

- Unknown/absent provider key → a player-facing message, never an exception.
- A provider method throwing → caught by the dispatcher, logged at `WARNING`, replaced with a generic
  "Something went wrong" reply. A module's bug must not surface as a red Brigadier stack trace.
- Non-player sender → "Only players can link an account." (`/notifications reload` remains the only
  console-usable subcommand.)

## Testing strategy

| Unit | Where | Covers |
|---|---|---|
| `AccountLinkRegistryTest` | `:api:test` | register / unregister / lookup / keys / case-insensitive lookup / re-registration replaces |
| `AccountLinkProviderTest` | `:api:test` | the `displayName()` default title-casing |
| `AccountLinkDispatcherTest` | `:platform:paper-plugin:test` | each action routes to the right provider method; unknown provider message; empty-registry listing; a throwing provider is contained |
| `DiscordAccountLinkProviderTest` | `:platform:discord-adapter:test` | delegation to `DiscordLinkFlow`, provider key, display name |

`NotificationsCommand` itself stays untested — Brigadier plus a live server, same as before. That is why
`AccountLinkDispatcher` exists as a separate class: everything except the node wiring is under test.

Manual verification (needs `runServer`, a bot token and a Discord account): `/notifications link`,
`/notifications link discord`, `… status`, `… unlink`, tab completion, and that `/discordlink` no longer
resolves.

## Known limitations

- **The `link` node exists even with no providers.** A vanilla install shows `/notifications link` and is
  told nothing is available. Hiding it would mean a `requires` predicate over a registry that is empty at
  tree-build time on the very first enable; not worth the conditional.
- **No admin link management** — unchanged from before; still no way to inspect or clear another
  player's link.
- **One provider at a time per player is not enforced across providers.** The registry does not know
  that `discord` and a hypothetical `telegram` are alternatives; each provider owns its own storage.
  Correct today, and worth revisiting only if a second provider ever appears.
- **`AccountLinkProvider` is an `api` type**, so its signature is a compile target for separately-built
  modules. It is deliberately minimal — four methods matching what `DiscordLinkFlow` already exposes —
  precisely because widening it later is cheap and narrowing it is not.
- **Existing links are untouched.** No schema change; `DiscordAccountLink` rows keep working.
