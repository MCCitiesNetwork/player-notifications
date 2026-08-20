# Discord slash commands — design

**Date:** 2026-08-20
**Module:** `platform:discord-adapter`
**Status:** implemented; the live-server checklist in the plan has not been run

## Goal

Give a linked player the mail and notification surface they have in game, from Discord: send and read
mail, list, read and dismiss notifications, and edit delivery preferences. The adapter today is a
one-way sink plus a `/link` redemption command; this makes it a two-way client.

Everything is built inside `platform:discord-adapter`, over host machinery that is already public —
`MailSender`, `MailNotifier`, `MailRecipients`, `InboxEntryRenderer`, `PreferenceEditSession`,
`PreferenceSessionManager`, `DatabaseNotificationPreferences`, and `PlayerNotificationsPlugin`'s
`notificationService()` / `sinkRegistry()` / `preferences()` / `categories()`.

**The one host change** is `PlayerNotificationsPlugin#inboxPageSize()`, a read-only accessor over a
`volatile` field set on enable and on reload. `settings.yml`'s `inbox-page-size` was reachable only by
the two `InboxRouter`s, and a Discord surface paging differently from every in-game screen would be a
bug nobody could configure away. `InboxView` takes it as an `IntSupplier`, so a reload reaches an
already-built view — the same reason `InboxRouter.reloadPageSize` exists.

## Where this sits

Not on any registry axis. A Discord command surface is a **second client** onto the same service APIs
the Paper command layer drives — the analogue of `NotificationsCommand`/`MailCommand`, not of a sink,
renderer or category. It therefore copies the structural rule the module already established for
linking (`DiscordLinkFlow` : `LinkSlashCommandListener`): **every decision lives in a plain class with
no JDA type in its signature, and the JDA listener over it is a logic-free adapter.** Nothing touching
JDA can be unit tested, so anything that can be got out of a listener must be.

## Architecture

New package `io.github.md5sha256.playernotifications.discord.command`.

### Logic classes (unit tested, no JDA in their signatures)

| Class | Responsibility |
|---|---|
| `ComponentIds` | Codec for component custom ids: `encode(surface, action, args…)` / `parse(id)` |
| `DiscordUserResolver` | `discordId → UUID`, the reverse of delivery's lookup |
| `DiscordMailService` | `/mail send` rules: recipient resolution, escaping, enqueue, arrival notice |
| `InboxView` | Paged listing, read, dismiss and clear over `NotificationService`, filtered or not |
| `PreferenceView` | The staged preference model over the host's `PreferenceEditSession` |

### JDA classes (thin; the message factories are still unit tested, as `DiscordMessageFactory` is)

| Class | Responsibility |
|---|---|
| `SlashCommandRegistrar` | Owns `onReady` and the **single** `updateCommands()` call for every command |
| `MailCommandListener` | `/mail` branches → `DiscordMailService` / `InboxView` |
| `NotificationsCommandListener` | `/notifications` branches → `InboxView` / `PreferenceView` |
| `InboxInteractionListener` | Buttons and row selects on a listing message |
| `PreferenceInteractionListener` | Selects and buttons on the preference message; the compose/reply modal |
| `InboxMessageFactory` | `InboxView.Page` → an ephemeral `MessageCreateData` with components |
| `PreferenceMessageFactory` | `PreferenceView.State` → an ephemeral `MessageCreateData` with components |

### Registration is centralised, and that is not optional

`LinkSlashCommandListener.onReady` currently calls `JDA#updateCommands()`, which **replaces the whole
global command set**. A second listener registering the same way would silently delete the first's
commands, with the loser decided by event ordering. `SlashCommandRegistrar` therefore takes over
`onReady` and makes one `updateCommands()` call carrying `/link`, `/mail` and `/notifications`;
`LinkSlashCommandListener` keeps only its `onSlashCommandInteraction` branch and its
`COMMAND_NAME`/`CODE_OPTION` constants, which move nowhere. All commands use
`setContexts(BOT_DM, GUILD)`, as `/link` already does.

### Identity

Every command answers "which player is this Discord user?", the reverse of what delivery asks.
`DiscordAccountLinkStore.playerFor(long)` already exists but only serves the `embedded` provider, and
`DiscordAccountProvider` is forward-only. It gains:

```java
default @NotNull Optional<UUID> playerFor(long discordId) { return Optional.empty(); }
```

`EmbeddedDiscordAccountProvider` delegates to the store; `DiscordSrvAccountProvider` uses DiscordSRV's
own reverse lookup; `ChainedDiscordAccountProvider` walks the configured order exactly as
`discordIdFor` does — first answer wins, unavailable skipped un-queried, a thrower logged and skipped.
A `default` returning empty rather than a new abstract method, so a third-party provider compiled
against the old interface still builds; and the reverse lookup on the chain rather than on the store
alone, so the commands work on a `link-providers: [discordsrv]` server instead of being silently dead
there. An unresolved user gets one reply naming `/notifications link discord`, the same wording the
host uses.

### Command surface

Names match the in-game commands exactly — no root literal. Every reply is ephemeral.

| Discord | Behaviour |
|---|---|
| `/mail send player:<name> message:<text>` | enqueues one mail, then the arrival notice |
| `/mail compose player:<name>` | opens a **modal** with a paragraph body, then as `send` |
| `/mail list [page:<n>]` | ephemeral embed listing, Prev/Next buttons, a row select |
| `/mail read entry:<n> [page:<n>]` | reads entry `n` of page `n`; marks it seen |
| `/mail dismiss entry:<n> [page:<n>]` | dismisses entry `n` of page `n` |
| `/mail clear` | `markAllSeen` + `dismissSeen`, both filtered to `mail` |
| `/notifications list\|read\|dismiss\|clear` | the same four, unfiltered |
| `/notifications prefs` | the staged preference message |
| `/notifications mute` / `/notifications unmute` | immediate, as in game |
| `/link code:<code>` | unchanged |

`InboxView` is constructed twice — once with `dataTypeFilter = MailPayload.DATA_TYPE` and the title
"Mail", once with `null` and "Notifications" — which is exactly how the host runs two `InboxRouter`s,
and for the same reason.

### Entry indexing is stateless

`InboxRouter` holds a per-player page cursor so `/mail read 3` knows which page `3` indexes, and drops
it on quit. Discord needs no such thing: `page` is an explicit slash option defaulting to 1, and a
component's custom id carries its own page and notification key. The whole class of stale-cursor bug
the host has to guard against therefore cannot arise here, and there is no per-user state to expire or
clean up on disconnect. Clamping is unchanged — `NotificationService.inbox` clamps `page` into
`1..totalPages` and `pageSize` into `1..20` regardless of caller.

An `entry` outside the page's row count is rejected with a message naming the page's real size rather
than silently clamped: unlike a page number, an out-of-range entry means the player is looking at
information that has changed under them, and acting on the wrong mail is worse than a second command.

### Rendering

`InboxEntryRenderer` (host) resolves payload → renderer exactly as the inbox does, including its
placeholder for an unrenderable payload; `DiscordMarkdownSerializer` (module) turns the resulting
`Component`s into Discord markdown. Both already exist and neither changes. `InboxMessageFactory`
composes them into an embed per page, truncating to Discord's limits before handing anything to JDA's
builders, which throw on overlong input rather than trimming — the rule `DiscordMessageFactory`
already follows.

### Mail from Discord is always plaintext

`DiscordMailService` calls `MailRecipients.resolve(name, message, resolver, MiniMessage::escapeTags)`.
That is the EssentialsX converter's escaping, chosen for the same reason: the text has passed through
no permission gate, so it must be able to render only as the literal text it was. `MailFormatting` and
its fifteen `…format.*` permissions are not consulted at all — there is no `CommandSender` in Discord
to check them against, and a Discord-side permission model would be a second, divergent gate on the
same feature.

Recipient resolution matches `MailCommand`: an online player by name first, else
`Bukkit.getOfflinePlayer(name)` accepted only when `hasPlayedBefore()` — a never-joined name is
rejected, because `getOfflinePlayer(String)` fabricates a UUID for any string at all. The sender is
the linked player's UUID, with their name captured at send time as `MailSender` requires. The arrival
notice fires through `MailNotifier` unchanged, so a mail sent from Discord announces itself by the
recipient's own preferences.

### Preferences

`/notifications prefs` posts an ephemeral message backed by the host's `PreferenceEditSession`, held
in a **module-owned** `PreferenceSessionManager` instance:

```
Preferences — 2 pending changes
[ Notification type: mail          v ]   string select, one option per dataType
[ Media: chat x  discord-dm x      v ]   multi-select, plus "Mute this type"
( Apply )  ( Discard )  ( Mute everything )
```

The two selects are the same axis pair as `MediumEditorDialog`, transposed: pick a `dataType`, then
its media. Apply calls `DatabaseNotificationPreferences.applyChanges(player, explicitChanges,
Set.of(), stagedMute)` — one transaction, an empty reset set, exactly as the dialogs do. Emptying the
media selection stages `{none}`, the per-`dataType` mute; there is no "server default" affordance,
matching the host, where that concept was deliberately removed from the UI.

**A module-owned session manager, not the host's.** Sharing would make an in-game staged edit visible
in Discord, which sounds desirable and is not: the dialog screens' "Back abandons this screen's
checkboxes" semantics assume one owner of the session, and an Apply from Discord would commit a
half-finished dialog edit with nothing on the player's screen changing to say so. Two managers means
two independent staged edits, last Apply wins — which is the same outcome as two dialogs, and is
legible.

### Why modals are used only for prose

A modal opens only in response to an interaction and submits once; it cannot re-render as the player
toggles. Used for the preference matrix, it would mean either no staging (one modal per data type
edited, no aggregate view) or asking players to type medium keys as free text, where a typo is silent.
Message components can do neither of those wrong things. Modals therefore carry exactly the input
components cannot: the `/mail compose` body — a slash option is single-line, and mail is prose — and
the *Reply* button on a read mail, which opens the same modal pre-addressed.

### Threading

Every handler `deferReply(true)` and hops to the module's existing `asyncExecutor` before touching
anything. All of it is blocking JDBC, plus `Bukkit.getOfflinePlayer`, which `MailCommand` already
performs off the main thread. Nothing here touches a `Player` object or a scheduler-bound API.

### Config

`discord.yml` gains one key, `commands-enabled` (`boolean`, default `true`). When false,
`SlashCommandRegistrar` registers `/link` alone, so a server that wants Discord as a delivery medium
only can have that without also exposing mail sending. Merge-on-upgrade only *adds* absent keys, so an
existing install picks up the default.

## Testing strategy

Unit tests, no Docker, in `platform/discord-adapter/src/test`:

- `ComponentIdsTest` — round trip, an argument containing the delimiter, an unparseable id.
- `DiscordUserResolverTest` — resolved, unlinked, unavailable provider skipped, throwing provider
  skipped, first answer wins (extending the `ChainedDiscordAccountProviderTest` fixtures).
- `DiscordMailServiceTest` — over a fake `NotificationService`: unknown recipient, blank and
  over-length messages, a message containing `<red>` and `<click:run_command:/op me>` stored escaped,
  a successful send producing a notice.
- `InboxViewTest` — paging and clamping, a filtered view showing only mail, `read` marking seen,
  `dismiss` removing, `clear`, an out-of-range entry rejected, an unrenderable payload showing the
  placeholder.
- `PreferenceViewTest` — staging, dirty counts, emptying a type staging `{none}`, mute staging,
  Apply's argument shape, Discard.
- `InboxMessageFactoryTest` / `PreferenceMessageFactoryTest` — over-limit truncation and component
  counts, as `DiscordMessageFactoryTest` does today.
- `DiscordSettingsTest` — the new `commands-enabled` key and its default.

Unverifiable without a live server, bot token and Discord account, and so covered by a manual
checklist in the plan: `SlashCommandRegistrar`, all four listeners, the modal, and `DiscordModule`'s
wiring.

## Known limitations

- **Discord selects cap at 25 options.** A server with more than 25 registered data types or media
  pages them; the initial implementation lists the first 25 and logs a warning naming the overflow,
  because paging a select needs its own navigation row and no server here is near the cap. Revisit
  when one is.
- **Components die with their interaction token, after 15 minutes.** A stale listing or preference
  message answers a click with "This message has expired; run the command again." There is no way to
  keep an ephemeral message interactive longer, and persisting the message would mean storing Discord
  message ids.
- **Discord mail carries no formatting and no `…format.*` gate.** Deliberate, per above. A player who
  wants a formatted mail sends it in game.
- **No admin surface.** No reading or editing another player's inbox or preferences from Discord, the
  same gap the in-game surface has.
- **A globally muted player can still read and edit here.** Correct: the mute suppresses unsolicited
  push, and every command in this document is solicited.
- **No rate limiting**, matching `/link`. A linked user driving `/mail send` in a loop is bounded by
  Discord's own interaction rate limits, not by anything here.
- **No Reply button on a read mail**, per the modal section above.
- **A Discord-side staged preference edit is invisible in game** and vice versa, per the two-manager
  decision above. Last Apply wins.
