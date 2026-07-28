# Discord Adapter — Design

**Date:** 2026-07-29
**Status:** Approved (design), implementation in progress

## Problem

`NotificationSink` was designed with Discord as the named future medium. The renderer/sink split
exists precisely so that adding a medium is a `+1` registration rather than an `N`-payload change;
`DeliveryResult.UNSUPPORTED`'s javadoc names "no linked Discord account" as its example;
`settings.yml`'s `default-media` comment already lists a Discord key. Nothing implements it.

Meanwhile `RenderableNotification` deliberately holds no `Player` and no `Audience`, precisely
because Essentials mail and Discord deliver to players who are not online. A Discord DM is the case
that motivated that decision, and it has never been exercised.

Two sub-problems have to be solved together:

1. **Delivery.** Getting a `Component` title/body to Discord, which speaks markdown and embeds, not
   Adventure components, and has hard length limits.
2. **Identity.** Mapping a Minecraft `UUID` to a Discord user id. This plugin has no linking flow and
   should not grow one as a side effect of adding a medium.

## Decision summary

- A new **feature module** `platform:discord-adapter` — a jar dropped into `<dataFolder>/modules/`,
  exactly like `platform:essentials-adapter`. The host plugin gains no Discord code and no Discord
  dependency.
- The module registers a **`NotificationSink`**, not a `NotificationProcessor`. The Essentials adapter
  registers a processor and therefore bypasses preferences entirely (a known quirk). A sink
  participates in the preference system and the fan-out, which is what a medium should do.
- Medium key **`discord-dm`**, not `discord`. `discord-channel-ping` is **reserved** in
  `DiscordMedia` for a future sink that posts into a channel and pings the player, so the DM sink is
  not squatting on the generic name. Nothing registers `discord-channel-ping`, so it never appears in
  the preference dialogs — those enumerate `sinkRegistry().registeredMedia()`.
- **The module runs its own JDA bot,** with its own token, shaded and relocated into the module jar.
- Minecraft UUID → Discord id resolves through a **`DiscordAccountProvider`** interface with an
  ordered chain, config-driven. The only implementation shipped is `DiscordSrvAccountProvider`
  (legacy DiscordSRV 2.x). DiscordSRV is a **link source only**; it delivers nothing.
- A `Component` becomes a Discord message via a **`Component` → Discord-markdown serializer** plus a
  message factory offering three formats: `embed` (default), `markdown`, `plain`.
- **No new database table and no migration.** Links come from DiscordSRV.

### Rejected alternatives

- **Borrow DiscordSRV's bundled JDA instead of shipping our own.** Superficially attractive — one
  gateway connection, one token, no shading. Rejected: legacy DiscordSRV relocates its JDA to
  `github.scarsz.discordsrv.dependencies.jda.*`, so its `JDA` type is not the upstream `net.dv8tion`
  one. Using it means either compiling against relocated internals (which break on any DiscordSRV
  update) or reflection over the whole message-construction surface. Both trade a second gateway
  connection for a permanent maintenance liability, and both hard-wire delivery to DiscordSRV rather
  than only identity.
- **An own link table in `core` now.** Would remove the DiscordSRV requirement, but needs an in-game
  link flow (`/notifications link`, code redemption, expiry), which is its own design. It also runs
  into a real constraint: `SchemaMigrator` tracks a single `schema_version` chain and `MariaDatabase`
  registers mappers from a hardcoded list, so a *module* cannot own a migration today — the table
  would have to live in `core` as a V2 migration, i.e. the host would carry Discord-shaped schema for
  a module that may not be installed. Deferred behind `DiscordAccountProvider`, which is exactly the
  seam a future own-table provider slots into.
- **A 2-D `(payload, medium)` renderer registry** so payload authors could write Discord-specific
  renderings. Already rejected in the renderer/sink design for the same reason: every new medium
  leaves every existing payload uncovered. Re-rejected here — the whole point of this module is that
  it lands without touching a single payload.
- **Delivering to a channel and pinging, instead of a DM.** A DM is the closer analogue of a personal
  notification and needs no channel configuration or permission audit. The channel variant is kept as
  a reserved medium key so it can be added later without a preference-row migration.

## Components

All under `io.github.md5sha256.playernotifications.discord`.

```
DiscordModule (entry class, PluginModule<PlayerNotificationsPlugin>)
 ├── DiscordSettings          discord.yml, @ConfigSerializable record
 ├── ModuleConfigs            copy-defaults-then-merge, against the module jar's resources
 ├── DiscordBot               owns the JDA instance (build on init, shutdown on stop)
 ├── DiscordAccountProvider   ← THE SWAP POINT
 │    ├── DiscordAccountProviderRegistry
 │    ├── DiscordSrvAccountProvider      (the only class that touches DiscordSRV)
 │    └── ChainedDiscordAccountProvider  (ordered, first non-empty wins)
 ├── DiscordMessageFactory    RenderableNotification → MessageCreateData
 │    ├── DiscordMessageFormat  EMBED | MARKDOWN | PLAIN
 │    └── DiscordMarkdownSerializer   Component → Discord markdown
 ├── DiscordMedia             DM = "discord-dm", CHANNEL_PING = "discord-channel-ping" (reserved)
 └── DiscordDmSink            NotificationSink, mediumKey "discord-dm"
      └── DiscordMessenger    seam: JdaDiscordMessenger (real) / fake (tests)
```

### The provider swap point

```java
public interface DiscordAccountProvider {
    @NotNull String providerKey();                                   // "discordsrv"
    @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid);  // empty == not linked
    default boolean isAvailable() { return true; }                   // plugin present and ready
}
```

`discord.yml` carries `link-providers: [discordsrv]`, an ordered list resolved against a
`DiscordAccountProviderRegistry` (the same shape as `NotificationSinkRegistry`) and wrapped in a
`ChainedDiscordAccountProvider`. Adding a provider later is one class, one registration and one
config line; `DiscordDmSink` never changes. An unknown key logs a warning and is skipped. A provider
that is unavailable is skipped without being queried, and one whose lookup throws is logged and
skipped rather than aborting the chain — a broken provider must not mask a working one further down.

The same abstraction is what a future `discord-channel-ping` sink resolves its mention target
through, which is the other reason it sits behind an interface rather than inside the sink.

### Rendering

- **Body → Discord markdown** via Adventure's `ComponentFlattener` driving a `FlattenerListener` that
  tracks `pushStyle`/`popStyle`: bold `**`, italic `*`, underlined `__`, strikethrough `~~`,
  obfuscated → spoiler `||`. Literal text escapes `` \ * _ ~ | ` > ``. Colours are dropped — Discord
  message text cannot be coloured.
- **Title → plain text** (`PlainTextComponentSerializer`); Discord embed titles do not render
  markdown.
- **Embed colour** from the title `Component`'s colour when it has one, else the configured
  `embed-color`.
- Truncation with `…`: title 256, embed description 4096, plain/markdown message 2000 — Discord's own
  limits.

### Delivery semantics

`DiscordDmSink#deliver` maps onto the existing `DeliveryResult` contract:

| Situation | Result |
|---|---|
| DM sent | `DELIVERED` |
| No linked Discord account | `UNSUPPORTED` (the javadoc's own example; warned once by `RenderingProcessor`) |
| Bot blocked by the user / user unknown (`CANNOT_SEND_TO_USER`, `UNKNOWN_USER`) | `UNSUPPORTED` |
| JDA not connected, rate-limited, timed out, any other exception | `UNREACHABLE` (retried next pass) |
| Called on the main thread | warn, `UNREACHABLE` — the sink blocks on a Discord round trip |

Send is `submit().get(delivery-timeout-seconds, SECONDS)`; the delivery loop already runs off the main
thread, and the main-thread guard exists so a future caller that gets that wrong fails loudly instead
of freezing the server.

### Class loading

Modules load through `ModuleLoader` via `new URLClassLoader(jarUrl, hostClassLoader)` — **parent
first**. Shaded, relocated classes in the module jar resolve fine. DiscordSRV's classes, however, must
be reachable from the *host* plugin's classloader, and Paper plugins are classloader-isolated by
default, so `paper-plugin.yml` needs a soft `dependencies:` entry for DiscordSRV with
`join-classpath: true`. Without it `DiscordSrvAccountProvider` silently reports unavailable.

## Error handling

- A blank `bot-token` makes `initialize` throw `ModuleInitializationException`, matching how
  `EssentialsMailModule` refuses to start without Essentials. A misconfigured module should not
  register a sink that can never deliver.
- `DiscordSrvAccountProvider.isAvailable()` wraps its plugin lookup in `try/catch (LinkageError)`, so
  a missing or version-incompatible DiscordSRV degrades to "unavailable" rather than killing module
  startup.
- `DiscordBot` does not `awaitReady()`, so server startup is never blocked on a Discord handshake.
  `jda()` returns empty until the connection reaches `CONNECTED`, and a send attempted before then is
  `UNREACHABLE`.
- `DiscordDmSink` never propagates an exception out of `deliver`. `RenderingProcessor` already catches
  and downgrades, but the sink not throwing keeps that path free for genuine bugs.

## Testing strategy

Automated (`./gradlew :platform:discord-adapter:test`), all offline — no gateway, no server:

- `DiscordMarkdownSerializerTest` — each decoration's markers, nesting, escaping, colour dropping,
  newlines, and a decoration explicitly negated inside a decorated parent.
- `DiscordMessageFactoryTest` — embed title/description/colour, markdown and plain content, and the
  three truncation limits.
- `ChainedDiscordAccountProviderTest` — chain ordering, fall-through, unavailable-skipped,
  throwing-provider isolation, unknown key, empty chain.
- `DiscordDmSinkTest` — linked/unlinked/throwing paths, `mediumKey()`, and `displayName()`.

Manual, per the plan's Task 8 — JDA login, the DiscordSRV lookup, module class loading through the
shaded jar, the medium appearing in `/notifications media`, and an actual DM landing. These need a
real bot token and a DiscordSRV-linked account and cannot be run here.

`displayName()` is worth a test specifically because the interface default title-cases the medium key,
turning `discord-dm` into `"Discord Dm"`. The sink overrides it to `"Discord DM"`.

## Known limitations

- **No linking flow.** A player with no DiscordSRV link gets `UNSUPPORTED` forever. An in-game
  `/notifications link` plus code redemption is its own design.
- **DiscordSRV is a hard requirement for links,** even though delivery is independent of it. The
  provider interface is the seam for removing that.
- **Partial delivery stays silent,** inherited from the existing DELETE-wins fan-out: chat succeeding
  and Discord failing consumes the notification. Fixing it needs per-medium delivery tracking, already
  deferred by the renderer design.
- **A second gateway connection and a second bot token,** because DiscordSRV's relocated JDA is not
  type-compatible with ours.
- **No attachments, buttons, or click actions** — consistent with `RenderableNotification`'s scope.
  Discord components and dialog buttons share no execution model.
- **`discord-channel-ping` is a reserved name only.** No sink registers it, so a player can never
  select it; the key exists so a channel sink can be added later without a preference-row migration.
- **Colour is lost in the body.** Discord message text has no colour; only the embed's accent bar
  carries one, so a component with several colours collapses to one accent.
