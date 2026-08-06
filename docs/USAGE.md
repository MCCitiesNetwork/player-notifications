# PlayerNotifications — User Guide

PlayerNotifications stores notifications for players and delivers them through whichever **delivery
method** each player prefers: in-game chat, a dialog screen, Essentials mail, or a Discord DM. This
guide covers using the plugin — see `CLAUDE.md` for the developer/API side.

Requires Paper **1.21.8**, Java **21**, and a MariaDB database.

---

## For players

### Commands

Everything lives under `/notifications` (alias `/notifs`), and must be run in game — the one exception
is `/notifications reload`, noted below.

| Command | What it does |
|---|---|
| `/notifications` | Nothing yet. The name is **reserved** for a notification management screen that has not been built — running it tells you so and points you at `/notifications preferences`. |
| `/notifications preferences` | Opens the preferences screen. |
| `/notifications preferences media` | Jumps straight to "by delivery method" — pick a method, then tick which notifications reach you there. |
| `/notifications preferences types` | Jumps straight to "by notification type" — pick a category, then tick which methods it uses. |
| `/notifications preferences mute` | Mutes **everything**, immediately. |
| `/notifications preferences reset` | Clears all your preferences, immediately, back to the server default. |
| `/notifications mute` | Shortcut for `/notifications preferences mute` — the same action, kept at the top level because it is the one people want in a hurry. There is no matching `/notifications reset` shortcut. |
| `/notifications link` | Lists the accounts you can link (only those the server has set up). |
| `/notifications link <service>` | Starts linking that account — e.g. `/notifications link discord`. |
| `/notifications link <service> status` | Shows whether that account is currently linked. |
| `/notifications unlink <service>` | Removes the link. |

> The preference subcommands used to sit directly under `/notifications` (`/notifications media`, and
> so on). They moved under `preferences` to keep the top level free for the management screen, whose
> own verbs would otherwise clash with names like `mute` and `reset`.

Permission: `playernotifications.command.preferences`, granted to everyone by default.

Linking has its own permission on top of that, `playernotifications.command.link`, also granted to
everyone by default — so out of the box nothing changes. Negate it to leave preferences open while
restricting `/notifications link` and `/notifications unlink` to a rank. It is an *extra* gate, not a
replacement: `playernotifications.command.preferences` still covers the whole command, so revoking
that hides linking as well.

`<service>` tab-completes to whatever the server has available. Asking for one it does not have (or
whose module is switched off) gets you a message saying so, not an error.

Admin-only extras (op by default):

| Command | Permission | Notes |
|---|---|---|
| `/notifications test [message]` | `playernotifications.command.test` | Sends yourself a test notification right now, through your current preferences. The reply names the methods it *attempted*, by the same names the preferences screen uses — it cannot confirm each one landed. It says so plainly instead if you have test notifications muted, if you have no methods chosen, or if a method you prefer has no sink installed on this server. The notification itself is titled `[Test]` and explains what it is, since it arrives in the same inbox as real ones. |
| `/notifications reload` | `playernotifications.command.reload` | Reloads `categories.yml` and `settings.yml`. Usable from console. Does **not** reload `database.yml`. |

### Setting your preferences

The root screen offers two ways in — both edit the same underlying settings, so they can never
disagree:

- **By delivery method** — "which notifications reach me on Discord?" Pick a method, then tick each
  notification type. Types are grouped under their category to make the list readable.
- **By notification type** — "how should Mail reach me?" Pick a category, then tick each delivery
  method, plus a "use server default" option. A method shows **(mixed)** when the types inside that
  category currently disagree about it.

The root screen also has **Mute everything** and **Reset all to server default**.

**Changes are staged.** Pressing *Save* in an editor only records the change; nothing is written
until you press **Apply** on the root screen, which saves everything at once. **Discard** throws the
staged changes away. Apply/Discard only appear once you have unsaved changes.

Two things about the category editor worth knowing: pressing *Save* writes the state of **every**
type in that category for every method shown, even ones you didn't touch — so opening it and saving
with no changes converts those types from "server default" to an explicit setting matching what was
displayed.

### The three states a notification type can be in

| State | How you get it | What happens |
|---|---|---|
| **Server default** | You have never configured that type, or you used "use server default" | You receive it on whatever `default-media` says (chat, out of the box) |
| **Explicit selection** | You ticked one or more methods | You receive it on exactly those methods |
| **Muted** | You unticked everything, or used mute | You do not receive it at all, and it is discarded rather than queued |

A mute means "do not tell me" — muted notifications are consumed, not saved up for later. Unticking
everything stages a mute; it never silently falls back to the server default.

Muting also covers notification types added by modules installed *later*.

### Linking your Discord account

You need a linked account before "Discord DM" can deliver anything to you. Selecting it without one
means those notifications go nowhere.

1. Run `/notifications link discord` in game. You get a 6-character code, good for 10 minutes.
2. Send `/link <code>` to the server's Discord bot — a DM to the bot, or anywhere it can see you.
3. Done. `/notifications link discord status` confirms it.

Notes:

- You must share a server with the bot. Discord refuses DMs between a bot and a user with no guild
  in common.
- A code does not survive a server restart — just run the command again for a new one.
- One Discord account per player. `/notifications unlink discord` first if you want to switch.
- If your server uses DiscordSRV and you already linked through it, that link keeps working; you do
  not need to redo it here.

### Caveats

- An open preferences session expires after **15 minutes** idle, and is dropped when you log out.
  Reopening starts fresh from what is saved.
- Dialog buttons are single-use and expire after an hour. A dialog left open a long time will have
  dead buttons — close and reopen it.
- **Essentials mail is not affected by your preferences.** It is delivered by its own handler that
  runs ahead of the preference system, so muting does not stop it. This is a known limitation.
- If you prefer several methods and one of them fails transiently, the notification is still consumed
  — it will not be retried on the method that failed.

---

## For server operators

### Installing

1. Drop the shaded plugin jar into `plugins/`.
2. Start the server once to generate the config files, then edit `plugins/PlayerNotifications/database.yml`.
3. Restart. The schema is created and migrated automatically on enable.

Feature modules are **not** plugins — they are jars placed in
`plugins/PlayerNotifications/modules/`, and are loaded by PlayerNotifications itself:

- `essentials-adapter` — delivers notifications as Essentials mail. Needs EssentialsX.
- `discord-adapter` — delivers notifications as Discord DMs. Must be the **shaded** (`-all`) jar.

### `database.yml`

```yaml
url: mariadb://localhost:3306/player_notifications   # JDBC url WITHOUT the leading "jdbc:"
username: ''
password: ''
```

Changing this needs a full restart — `/notifications reload` deliberately skips it.

### `settings.yml`

```yaml
prune-interval-seconds: 3600     # how often expired notifications are deleted
default-media:                   # what a player with no saved preference receives
  - chat
deliver-on-join: true            # deliver a player's waiting notifications when they log in
join-delivery-delay-seconds: 3   # how long after joining to wait; 0 = immediately
```

Valid `default-media` values are whatever delivery methods are registered — `chat`, `dialog`,
`essentials-mail`, `discord-dm` (the last two only with their module installed).

`deliver-on-join` is the main way notifications reach players. The short delay exists so messages do
not arrive during the join flood, while the client is still loading. A negative value is treated as
`0`. Both keys are picked up by `/notifications reload`, including for a player already waiting out
the delay — turning the setting off cancels their pending delivery.

### `categories.yml`

Categories are a **display grouping only** — they decide how the "by notification type" screen is
organised. They have no effect on how notifications are actually delivered.

```yaml
uncategorized-label: "Other"

categories:
  mail:
    label: "Mail"
    description: "Essentials mail and other direct messages"
    types:
      - essentials-mail
  diagnostics:
    label: "Diagnostics"
    description: "Test notifications sent with /notifications test"
    types:
      - test
```

A notification type that no category claims falls into the catch-all category, so it is always
configurable by players without you editing this file first. A type may appear under more than one
category. A category listing a type that nothing has registered produces a warning at startup.

Removing a category from this file does **not** delete players' saved settings for its types — they
become invisible in the screens and reappear if you re-add the category.

Reload with `/notifications reload`.

### Discord adapter (`modules/discord.yml`)

The module runs **its own Discord bot** — it cannot reuse DiscordSRV's. Create a bot at
<https://discord.com/developers/applications> and invite it to a guild your players are in (Discord
refuses a DM between a bot and a user with no shared server).

```yaml
bot-token: ""                  # blank = the module refuses to start
message-format: embed          # embed | markdown | plain
embed-color: "#5865F2"
delivery-timeout-seconds: 15
link-providers:                # tried in order, first match wins
  - embedded
  - discordsrv
link-code-expiry-seconds: 600  # how long a /notifications link discord code stays valid
```

Once running, "Discord DM" appears in the preference screens automatically.

#### Account linking

`link-providers` decides where a player's Discord id is looked up. Both shipped options can be listed
together, which is the recommended setup:

| Provider | What it uses | Needs |
|---|---|---|
| `embedded` | This plugin's own link table, filled in by `/notifications link discord` | Nothing else |
| `discordsrv` | DiscordSRV's existing account links | DiscordSRV installed |

With both listed, new links land in the embedded table and take priority, while existing
DiscordSRV links keep resolving — so you can install this without asking anyone to re-link.
**DiscordSRV is entirely optional**; `embedded` alone is a complete setup.

`/notifications link discord` and the bot's `/link` slash command only exist when `embedded` is
listed. On a DiscordSRV-only server, players link through DiscordSRV as before.

Upgrading an existing install will **not** silently switch you to `embedded` — config merging only
adds keys it does not find, so your current `link-providers` is left alone. Add `embedded` yourself
if you want it.

If DiscordSRV *is* installed, the plugin also needs it on the classpath; `paper-plugin.yml` already
declares that soft dependency.

Notification text is converted to Discord markdown — **bold**, *italic*, underline, strikethrough
and spoilers survive; colours do not, since Discord message text cannot be coloured. Long
notifications are truncated to Discord's limits.

### Known limitations

- **Delivery is triggered by logging in, or by `/notifications test`** — nothing else. A notification
  queued for a player who is **already online** waits until their next login; there is no push to a
  connected player.
- **There is no inbox.** The commands cover preferences and account linking only — there is no way to
  list past notifications, no player-initiated clear, and no admin view of another player's
  preferences. The bare `/notifications` is reserved for this, but nothing implements it yet.
- Partial delivery failures are silent (see the player caveat above).
- `discord-channel-ping` is reserved but not implemented; nothing can select it.
- **No admin tools for account links.** You cannot link, unlink, inspect or list another player's
  Discord link. Clearing a stuck one means a manual `DELETE` against the `DiscordAccountLink` table.
- **No rate limiting** on link attempts, in game or in Discord. A 6-character code over a
  32-character alphabet inside a 10-minute window makes brute force impractical rather than
  impossible.
- Link codes are held in memory only and are lost on restart, by design.
