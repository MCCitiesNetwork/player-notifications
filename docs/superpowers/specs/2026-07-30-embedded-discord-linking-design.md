# Embedded Discord account linking — design

**Date:** 2026-07-30
**Status:** implemented (plan: `docs/superpowers/plans/2026-07-30-embedded-discord-linking.md`).
The manual end-to-end checklist in that plan's Task 7 has **not** been run.
**Supersedes nothing.** Extends `2026-07-29-discord-adapter-design.md`, whose "Discord account
linking is DiscordSRV-only, and there is no in-game link flow" limitation this closes.

## Goal

Let a server run the Discord adapter with **no DiscordSRV installed**, by shipping a second
`DiscordAccountProvider` backed by the plugin's own link table, plus a self-service in-game flow that
populates it. Which link source is used stays an operator choice made in `discord.yml`.

Concretely, after this change:

- A player runs `/discordlink`, receives a short-lived code, and redeems it with a `/link <code>`
  slash command sent to **this module's own bot** in Discord. The redemption writes a verified
  `(playerUuid, discordId)` row.
- `EmbeddedDiscordAccountProvider` (provider key **`embedded`**) resolves that table.
- `link-providers` defaults to `[embedded, discordsrv]`, so a DiscordSRV-free server works out of the
  box and a server migrating off DiscordSRV can keep resolving old links while new ones land in our
  table.

### Non-goals

- No change to `DiscordDmSink`, `DiscordMessageFactory`, `DiscordMarkdownSerializer`, or any part of
  the delivery path. The provider seam already exists; this fills it.
- No admin link management (`/discordlink <player> <id>`, link listings, forcing a link).
- No `discord-channel-ping` sink, still reserved and unimplemented.

## Why this shape

### The provider axis already exists — extend it, don't branch

`DiscordAccountProvider` / `DiscordAccountProviderRegistry` / `ChainedDiscordAccountProvider` and the
`link-providers` config key were designed for exactly this ("A future provider — an own link table, a
different linking plugin — is one class, one registration and one config line"). The account-resolution
half of this change is therefore purely additive: one new provider class, one `providers.register(...)`
line, one documented config key value. `DiscordDmSink` does not change.

"Making DiscordSRV optional" needs almost no code: it is already `compileOnly`, already a soft
`paper-plugin.yml` dependency, and `DiscordSrvAccountProvider.isAvailable()` already catches
`LinkageError` so the chain falls through. What made it *effectively* mandatory was that it was the only
provider, and that `link-providers` defaulted to `[discordsrv]` alone. Both are fixed here by addition.

The one behavioural gap worth closing: a chain whose configured providers are **all** currently
unavailable resolves nothing, silently, forever — every DM is `UNSUPPORTED`. `ChainedDiscordAccountProvider`
gains a startup-time report (below) so that misconfiguration is visible in the log rather than only as
undelivered notifications.

### The link table lives in `core`, as migration V2

A feature module cannot own a schema migration today: `MariaSchemaMigrator` tracks a single
`schema_version` chain from a hardcoded `DEFAULT_MIGRATIONS` list, and `MariaDatabase` registers its
mappers from a hardcoded list too. So the table, entity, and mapper live in `core` even though only the
Discord module uses them. This is a deliberate layering compromise, not an oversight — it is recorded in
Known limitations with the condition that would retire it (a per-module migration facility).

**A new `V2__discord_account_link.sql` rather than editing `V1` in place.** Earlier migrations were
collapsed into V1 because the project had no data to preserve, but V1 is already recorded as applied in
any dev database that has been run, so editing it would require dropping that database. Layering costs
one file and one `MigrationStep` entry.

```sql
CREATE TABLE IF NOT EXISTS DiscordAccountLink
(
    playerUuid BINARY(16) NOT NULL PRIMARY KEY,
    discordId  BIGINT     NOT NULL,
    linkedAt   DATETIME   NOT NULL,
    UNIQUE KEY uk_discord_account_link_discord_id (discordId)
);
```

- `playerUuid` is the primary key and `discordId` is uniquely indexed, so the relation is **one-to-one in
  both directions**. A player cannot link two Discord accounts, and one Discord account cannot receive
  notifications for two players. Enforcing this in the schema rather than in application code means a
  concurrent double-redeem fails at the constraint instead of producing a duplicate.
- `discordId` is a **signed** `BIGINT`, matching the `long` that `DiscordAccountProvider#discordIdFor`
  already returns. Discord snowflakes are unsigned 64-bit in principle, but the timestamp field puts
  real ids far below 2^63 (they would need to exceed year ~2084 to overflow), and `BIGINT UNSIGNED`
  would force either a `BigInteger` mapping or unsigned-read handling for no present benefit.
- `linkedAt` exists for operator diagnostics — "when did this link happen" is the first question asked
  about a link that misbehaves. Nothing reads it in code; it is not indexed.

### `/discordlink` is owned by the module, not the host

The command lives in `platform:discord-adapter` and registers its own top-level Brigadier literal. The
alternative considered was a host-side `AccountLinkFlow` registry so the command could be
`/notifications link`, keeping the whole tree under one name. Rejected: it introduces a **fourth
extension axis** in the host purely to relay three strings to a module, and the host would gain an
account-linking concept it otherwise has no use for. A module-owned command means **zero host changes**
for this feature beyond the `core` table.

The cost, accepted: linking sits outside the `/notifications` tree, so a player who has learned
`/notifications` does not discover `/discordlink` from it. Mitigated only by the module logging its
command at startup and by `discord.yml`'s comments.

Registration mechanics:

- `DiscordModule.initialize` calls `plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)`.
  Modules are started from inside the host's `onEnable` (`startModules()` runs after `registerCommands()`),
  so this is the same enable-phase registration the host itself uses.
- The permission `playernotifications.discord.link` cannot be declared in `paper-plugin.yml` — that file
  belongs to the host and the module jar has no plugin descriptor. The module registers it
  programmatically with `PermissionDefault.TRUE` on initialize and removes it on shutdown.
- **Both are torn down in `shutdown`,** before the bot stops. Paper's Brigadier registrar exposes no
  unregister, so `DiscordLinkCommand.unregister` goes through `Bukkit.getCommandMap()` — whose
  `getKnownCommands()` *is* API — dropping the literal, its `dlink` alias and their plugin-namespaced
  forms, then calling `Player#updateCommands` on everyone online, since clients cache the command tree.
  Matching is on the whole name after any namespace, never a substring, because a mis-match would remove
  *another plugin's* command from a running server; that matcher is the one unit-tested part of the class.
  Both teardown steps are flag-guarded so shutdown is idempotent and undoes only what was registered.
  Without this, a module stop/start cycle would leave a `/discordlink` dispatching into a dead flow and a
  shut-down bot.
- The command is registered **only when `link-providers` contains `embedded`**. An operator who
  deliberately runs DiscordSRV-only gets no dead command, and no config key is needed to express it.

### Codes are in-memory, single-use, and short-lived

`LinkCodeService` holds pending codes in a `ConcurrentHashMap` inside the module. Not persisted:
a code lives minutes, a restart invalidating one costs the player a re-run of `/discordlink`, and
persisting it would mean a second migration for state whose whole purpose is to be transient.

- Codes are 6 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` — no `I`/`O`/`0`/`1`, because the player
  reads the code off a chat line and retypes it in Discord. `SecureRandom`, so a code cannot be guessed
  by an observer who knows one; a guessed code would link an attacker's Discord account to the victim's
  player, which is the one real threat here.
- Redeeming **consumes** the code, so a code overheard after use is worthless.
- Re-running `/discordlink` **replaces** the player's outstanding code rather than issuing a second one,
  so a player can only ever have one live code and a stale one cannot be redeemed later.
- Expiry is `link-code-expiry-seconds` (default 600). Enforced on redeem, with a sweep of expired
  entries on each issue — there is no scheduled task, since the map is bounded by online players.
- Time comes from an injected `Clock`, so expiry is unit-testable without sleeping.

### Redemption happens over a JDA slash command

`DiscordBot` is currently built with `createLight` and **no gateway intents**, and never receives events.
Interactions do not require any intent (privileged or otherwise), so accepting a slash command needs no
intent change and no privileged-intent toggle in the Discord developer portal — which reading DM message
*content* would have required. That is why redemption is a slash command rather than "DM the bot the
code".

- `DiscordBot.start` gains a varargs `Object... eventListeners` parameter, passed to
  `JDABuilder#addEventListeners`. Listeners must be attached at build time to avoid missing the ready
  event.
- `LinkSlashCommandListener extends ListenerAdapter` registers the command on `onReady` (the bot never
  calls `awaitReady()`, so registration cannot be done inline after `start`) and handles
  `onSlashCommandInteraction`.
- The command is registered **globally** and with its interaction contexts set to include the bot DM
  context, since a player links from a DM with the bot rather than in a guild channel.
- The reply is **ephemeral** (`deferReply(true)`), because the code and the resulting link are the
  player's business and the interaction may happen in a guild channel.
- Redemption does blocking JDBC on a JDA event thread, so the listener hops onto an injected
  `Executor` — the module supplies Bukkit's async scheduler — and replies from there via the deferred
  hook.

## Architecture

```
/discordlink  ──▶ DiscordLinkCommand ──▶ DiscordLinkFlow ──┬─▶ LinkCodeService  (in-memory codes)
                                                           │
Discord /link <code> ──▶ LinkSlashCommandListener ─────────┘
                                                           └─▶ DiscordAccountLinkStore
                                                                     │
                                              DatabaseDiscordAccountLinkStore
                                                                     │
                                              core: DiscordAccountLinkMapper ──▶ DiscordAccountLink

DiscordDmSink ──▶ ChainedDiscordAccountProvider ──┬─▶ EmbeddedDiscordAccountProvider ──▶ store
                                                  └─▶ DiscordSrvAccountProvider  (optional)
```

`DiscordLinkFlow` holds every decision and every player-facing message; `DiscordLinkCommand` and
`LinkSlashCommandListener` are thin adapters over it. That split is what makes the flow unit-testable
without a server or a bot — the two adapters are the only untestable parts, and they contain no logic.

## Types and files

### `core`

| File | Change |
|---|---|
| `core/src/main/resources/sql/migrations/V2__discord_account_link.sql` | create — the DDL above |
| `.../database/maria/MariaSchemaMigrator.java` | add `new MigrationStep(2, "discord account link", "V2__discord_account_link.sql")` to `DEFAULT_MIGRATIONS` |
| `.../database/entity/DiscordAccountLinkEntity.java` | create — `record DiscordAccountLinkEntity(@NotNull UUID playerUuid, long discordId, @NotNull Instant linkedAt)` |
| `.../database/mapper/DiscordAccountLinkMapper.java` | create — neutral interface (below) |
| `.../database/maria/mapper/MariaDiscordAccountLinkMapper.java` | create — `@Select`/`@Insert`/`@Delete` impl |
| `.../database/SqlSessionWrapper.java` | add `@NotNull DiscordAccountLinkMapper discordAccountLinkMapper()` |
| `.../database/maria/MariaSqlSession.java` | implement it |
| `.../database/maria/MariaDatabase.java` | `configuration.addMapper(MariaDiscordAccountLinkMapper.class)` |

```java
public interface DiscordAccountLinkMapper {
    @Nullable DiscordAccountLinkEntity selectByPlayer(@NotNull UUID playerUuid);
    @Nullable DiscordAccountLinkEntity selectByDiscordId(long discordId);
    int insertLink(@NotNull UUID playerUuid, long discordId, @NotNull Instant linkedAt);
    int deleteByPlayer(@NotNull UUID playerUuid);
    int deleteByDiscordId(long discordId);
}
```

`Instant` needs no custom type handler — MyBatis ships one, and `NotificationEntity` already relies on
it for `DATETIME` columns.

### `platform:discord-adapter`

| File | Change |
|---|---|
| `DiscordAccountLinkStore.java` | create — interface: `Optional<Long> discordIdFor(UUID)`, `Optional<UUID> playerFor(long)`, `void link(UUID, long)`, `boolean unlink(UUID)` |
| `DatabaseDiscordAccountLinkStore.java` | create — impl over `Database`, one `SqlSessionWrapper` per call |
| `EmbeddedDiscordAccountProvider.java` | create — `PROVIDER_KEY = "embedded"`, delegates `discordIdFor` to the store |
| `LinkCodeService.java` | create — `String issue(UUID)`, `Optional<UUID> redeem(String)`, `void cancel(UUID)` |
| `DiscordLinkFlow.java` | create — `Component begin(UUID, String playerName)`, `Component status(UUID)`, `Component unlink(UUID)`, `RedeemResult redeem(String code, long discordId)` |
| `DiscordLinkCommand.java` | create — Brigadier node for `/discordlink [status\|unlink]` |
| `LinkSlashCommandListener.java` | create — `ListenerAdapter`; registers `/link` on ready, handles the interaction |
| `DiscordBot.java` | modify — `start(String botToken, Object... eventListeners)` |
| `DiscordSettings.java` | modify — add `@Setting("link-code-expiry-seconds") long linkCodeExpirySeconds` with `DEFAULT_LINK_CODE_EXPIRY_SECONDS = 600`, clamped in the canonical constructor like `deliveryTimeoutSeconds`; add `boolean usesEmbeddedProvider()` |
| `ChainedDiscordAccountProvider.java` | modify — add `void reportAvailability()`, logging at startup |
| `DiscordModule.java` | modify — wire the store, provider, code service, flow, listener, command, permission |
| `src/main/resources/discord.yml` | modify — new key, updated `link-providers` default and comments |

`store.link(UUID, long)` is an **upsert that clears both sides**: within one transaction it deletes any
row for that `playerUuid` and any row for that `discordId`, then inserts. Without clearing both, the
unique index on `discordId` would reject a player re-linking a Discord account previously linked to
someone else, and the natural operator reading of "I linked my account again" is "replace", not "fail".

`RedeemResult` is an enum — `LINKED`, `UNKNOWN_CODE`, `ALREADY_LINKED_TO_THIS_ACCOUNT`, `FAILED` — mapped
to a Discord reply string by the listener, so the flow stays free of JDA types. There is no separate
`EXPIRED`; see the `/link` reply table for why.

## Behaviour

### `/discordlink`

Permission `playernotifications.discord.link` (default `true`), player-only.

- **bare** — issues a code, replies in chat with the code, the expiry in minutes, and the instruction to
  send `/link <code>` to the bot. If the player already has a link, the reply says so and asks them to
  `/discordlink unlink` first — re-linking silently would let a player move notifications to a new
  account with no confirmation of the old one being dropped.
- **`status`** — reports the linked Discord id, or that no link exists, plus whether an unredeemed code
  is outstanding.
- **`unlink`** — deletes the player's row, cancels any outstanding code, and reports whether a link was
  actually removed.

### Discord `/link <code>`

| Case | `RedeemResult` | Ephemeral reply |
|---|---|---|
| Code valid, no conflicting link | `LINKED` | "Linked. Notifications you have set to Discord will arrive here." |
| Code unknown **or** expired | `UNKNOWN_CODE` | "That code is not valid or has expired. Run `/discordlink` in game for a new one." |
| This Discord account is already linked to that same player | `ALREADY_LINKED_TO_THIS_ACCOUNT` | "This Discord account is already linked to that player." |
| Store throws | `FAILED` | "Something went wrong. Run `/discordlink` in game for a new code and try again." + `WARNING` in the server log |

**Unknown and expired are deliberately one case.** An earlier draft of this spec separated them, on the
grounds that "expired" tells the player more precisely what to do. It cannot be done without leaking
expiry state out of `LinkCodeService`: `redeem` removes an expired entry and returns empty, so by the time
the flow sees the result there is nothing left to distinguish the two by. Keeping the removal inside
`redeem` is worth more than the finer message — it is what guarantees an expired code cannot linger — and
the single reply names both causes, so the player's next action is the same either way. Revisit only if
`redeem` ever needs to return a richer result for another reason.

### Availability reporting

On initialize, after building the chain, the module logs one line per configured provider at `INFO`:
the key and whether it is currently available. If **none** are available it logs a `WARNING` naming
`discord-dm` as undeliverable until one becomes available. This is the only diagnostic for the
"everything is `UNSUPPORTED`" failure mode, which otherwise produces no log at all until a notification
is actually dropped.

### Config

```yaml
link-providers:
  - embedded
  - discordsrv

link-code-expiry-seconds: 600
```

`embedded` first: a link a player made through this plugin is a deliberate, recent act and should win
over a DiscordSRV link that may predate it. Existing installs keep whatever they have in
`discord.yml` — the copy-defaults-then-merge idiom in `ModuleConfigs` adds `link-code-expiry-seconds`
but does not rewrite `link-providers`, so an install already reading `[discordsrv]` keeps DiscordSRV-only
behaviour until an operator edits it. That is the correct default for an upgrade: it changes nothing.

## Error handling

- `DatabaseDiscordAccountLinkStore` lets `RuntimeException` (MyBatis `PersistenceException`) escape.
  `ChainedDiscordAccountProvider` already catches a throwing delegate, logs, and tries the next — so a
  database outage degrades to "no link found from this provider" rather than aborting delivery.
  `DiscordLinkFlow` catches it explicitly and returns `FAILED` / an error `Component`, because a command
  and an interaction both need a reply.
- The unique index on `discordId` means a concurrent double-redeem loses at the constraint. The loser
  surfaces as `FAILED`; the code was already consumed, so the player re-runs `/discordlink`. Not worth
  retry logic for a race between two Discord users redeeming within milliseconds.
- Slash-command registration failing (bad permissions, Discord outage) is logged at `WARNING` from the
  `queue` failure callback and leaves the rest of the module working; DMs still deliver for players who
  are already linked.
- The module refuses to start on a blank token exactly as before. A blank token means no bot, which
  means no redemption path — so when `embedded` is configured the existing check is what guards the link
  flow too.

## Testing strategy

Unit tests, no server and no Discord connection:

- `LinkCodeServiceTest` (`:platform:discord-adapter`) — issue returns a code from the restricted
  alphabet; redeem returns the player and consumes; second redeem fails; a code past its TTL fails
  (driven by a mutable `Clock`); re-issuing replaces the previous code, and the previous code no longer
  redeems; `cancel` invalidates.
- `EmbeddedDiscordAccountProviderTest` (`:platform:discord-adapter`) — key is `embedded`, `isAvailable`
  is true, `discordIdFor` delegates to a fake store and passes through empty.
- `DiscordLinkFlowTest` (`:platform:discord-adapter`) — every row of the `/discordlink` and `/link`
  tables above, against a fake store and a real `LinkCodeService`; plus a store that throws, asserting
  `FAILED` rather than a propagated exception.
- `ChainedDiscordAccountProviderTest` (existing class, extended) — `reportAvailability` with a chain of
  all-unavailable providers logs at `WARNING`.
- `DiscordAccountLinkMapperTest` (`:core`, Testcontainers `mariadb:11.7`) — insert then select by both
  keys; `deleteByPlayer` / `deleteByDiscordId`; inserting a second row for the same `discordId` fails;
  a V2 migration applied on top of an existing V1 database creates the table.

**Not covered by automated tests** (needs a live server, a real bot token, and a Discord account):
`LinkSlashCommandListener`, the `DiscordBot` listener parameter, `DiscordLinkCommand`'s Brigadier
registration, the programmatic permission, and the whole redemption round trip. Manual checklist in the
implementation plan.

## Known limitations

- **A Discord-side table lives in `core`.** `DiscordAccountLink`, its entity and its mappers sit in a
  module that has no other reason to know Discord exists, because `MariaSchemaMigrator` owns one
  hardcoded migration chain and `MariaDatabase` one hardcoded mapper list. Retire this when a module can
  own a migration and register a mapper; until then, adding a per-module table means editing `core`.
- **Codes do not survive a restart.** Deliberate: a code's lifetime is minutes. A player who is issued a
  code and then sees the server restart gets "not valid" and must re-run `/discordlink`. Reconsider only
  if code issuance ever becomes expensive or asynchronous.
- **Linking is outside the `/notifications` tree.** `/discordlink` is discoverable only from the log line
  and `discord.yml`. Revisit if a second link flow ever ships, at which point a host-side registry — the
  alternative rejected here — starts paying for itself.
- **No admin link management.** No way to link, unlink, or inspect another player's link, and no listing.
  A stuck link needs a manual `DELETE` against `DiscordAccountLink`.
- **No rate limiting on `/discordlink` or `/link`.** A player can spam code issuance (bounded work: one
  map write) and a Discord user can spam redemption attempts (one indexed read each). Both are cheap
  enough that a limiter would be speculative, but a 6-character code with unbounded guesses is a
  brute-force surface — 32^6 ≈ 10^9 with a 10-minute window makes it impractical rather than impossible.
  Add a per-Discord-user attempt limit if abuse is ever observed.
- **One Discord account per player, enforced in the schema.** A player wanting notifications in two
  Discord accounts cannot have it. No use case was identified, and the constraint is what makes reverse
  lookup unambiguous.
- **`linkedAt` is written and never read.** Kept for operator diagnostics only.
- **The DiscordSRV path is still unverified end to end**, unchanged by this work — see the discord
  adapter design's own limitations.