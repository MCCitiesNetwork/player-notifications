# PlayerNotifications — User Guide

PlayerNotifications stores notifications for players and delivers them through whichever **delivery
method** each player prefers: in-game chat, a dialog screen, Essentials mail, or a Discord DM. Every
notification also stays in the player's **inbox** until they delete it, so nothing is missed. This
guide covers using the plugin — see `CLAUDE.md` for the developer/API side.

Requires Paper **26.1.2**, Java **25**, and a MariaDB database.

---

## For players

### Commands

Everything lives under `/notifications` (alias `/notifs`), and must be run in game — the one exception
is `/notifications reload`, noted below.

| Command | What it does |
|---|---|
| `/notifications` | Opens your **inbox** — every notification waiting for you, unread ones highlighted. |
| `/notifications list [page]` | The same inbox as chat text, for clients where the dialog does not render. |
| `/notifications read <n>` | Reads entry `n` of the page you last listed, and marks it read. |
| `/notifications delete <n>` | Deletes entry `n` of the page you last listed. |
| `/notifications clear` | **Empties your inbox outright**, unread entries included. The shorthand for *Mark all read* followed by *Delete all read*. |
| `/notifications preferences` | Opens the preferences screen. |
| `/notifications preferences media` | Jumps straight to "Delivery methods" — pick a method, then tick which notifications reach you there. |
| `/notifications preferences types` | Jumps straight to "Notification types" — pick a category, then tick which methods it uses. |
| `/notifications preferences mute` | Mutes **everything**, immediately. |
| `/notifications mute` | Shortcut for `/notifications preferences mute` — the same action, kept at the top level because it is the one people want in a hurry. |
| `/notifications link` | Lists the accounts you can link (only those the server has set up). |
| `/notifications link <service>` | Starts linking that account — e.g. `/notifications link discord`. |
| `/notifications link <service> status` | Shows whether that account is currently linked. |
| `/notifications unlink <service>` | Removes the link. |

> The preference subcommands used to sit directly under `/notifications` (`/notifications media`, and
> so on). They moved under `preferences` so the top level belongs to the inbox, whose own verbs would
> otherwise clash with a name like `mute`.

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

### Your inbox

Every notification sent to you stays readable until you delete it or it expires. Being *delivered* —
appearing in chat, arriving as a Discord DM — marks it **read**; it does not throw it away.

`/notifications` opens the inbox. Each row is one notification, unread ones in bold. Opening a row
shows it in full and marks it read; from there you can *Delete* it, *Mark as unread* (which undoes
that read and leaves it waiting), or go *Back*. Delete is red on every screen it appears on, in game
and in Discord — it is the only control there that destroys something, and there is no confirmation.
The list itself has:

- **Mark all read** — clears the unread count without removing anything.
- **Delete all read** — removes everything you have already read, leaving unread entries alone.
- **Previous / Next** — paging, when there is more than one page.

`/notifications clear` is the blunt version: it empties the inbox completely, unread entries included.
There is no confirmation, so treat it as final.

Three states, and it is worth knowing which is which:

| State | What it means |
|---|---|
| **Unread** | Not yet delivered or opened. Counted when you log in, and still eligible to be pushed to you. |
| **Read** | Delivered or opened. Still listed, no longer counted, never pushed again. |
| **Deleted** | Gone for good. |

When you log in you get a line naming your unread count and pointing at `/notifications` — you get
that line even if you have muted everything, because muting means "do not interrupt me", not "do not
tell me".

### Setting your preferences

The root screen offers two ways in — both edit the same underlying settings, so they can never
disagree:

- **Delivery methods** — "which notifications reach me on Discord?" Pick a method, then tick each
  notification type. Types are grouped under their category to make the list readable.
- **Notification types** — "how should Mail reach me?" Pick a category, then tick each delivery
  method. A method shows **(partly on)** when the types inside that category currently disagree
  about it.

The two work identically — same buttons, same rules. They differ only in which axis you pick first.

The root screen also has **Mute everything**, which opens a screen of its own explaining what muting
does. Nothing is muted until you press *Apply* there; *Back* leaves your preferences untouched.

**Apply and Discard.** Each editor has exactly two buttons besides *Back*: **Apply** saves what you
have ticked, together with anything staged on other screens, and **Discard** throws all of it away.
There is no separate *Save* — Apply is the one that writes.

They are on every screen where something can be edited — the two editors and the mute screen always,
the two picker screens as soon as you have unsaved changes. The root screen has neither: it only
navigates. Wherever you are, a line tells you how many changes are waiting, so you never have to
navigate somewhere else to save.

*Back* leaves an editor **without saving, and resets what you ticked there** — it is the way out when
you have changed your mind. It only affects the screen you are on; anything you already applied, or
staged from another screen, is left alone. *Discard* is the one that throws everything away.

One thing about the category editor worth knowing: Apply writes the state of **every** type in that
category for every method shown, even ones you didn't touch — so opening it and applying with no
changes converts those types from unconfigured to an explicit setting matching what was displayed.

### The three states a notification type can be in

| State | How you get it | What happens |
|---|---|---|
| **Server default** | You have never configured that type | You receive it on whatever the server's `default-media` says (chat, out of the box) |
| **Explicit selection** | You ticked one or more methods | You receive it on exactly those methods |
| **Muted** | You unticked everything, or used mute | Nothing is pushed to you — but it still lands in your inbox, unread, to read whenever you like |

**The first state is a starting point, not a choice you can make.** There is no "reset to server
default" — once you have configured a type, your setting stands until you change it to something
else. Pick different methods, or mute it.

A mute means "do not interrupt me", not "do not tell me": muted notifications are kept, unread, in
your inbox. Unticking everything stages a mute; it never silently falls back to the server default.

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
- Essentials mail can only carry plain text, so a notification delivered there loses anything a chat
  message or dialog could show beyond its title and body.
- If you prefer several methods and one of them fails transiently, the notification is still marked
  read and is not retried on the method that failed — but it remains in your inbox to read there.
- Your inbox has no size limit and nothing trims it automatically. Notifications leave it only when
  you delete them or they expire.

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
inbox-page-size: 7               # inbox entries per page; clamped to 1-20
```

Valid `default-media` values are whatever delivery methods are registered — `chat`, `dialog`,
`essentials-mail`, `discord-dm` (the last two only with their module installed).

`deliver-on-join` is the main way notifications reach players. The short delay exists so messages do
not arrive during the join flood, while the client is still loading. A negative value is treated as
`0`. Both keys are picked up by `/notifications reload`, including for a player already waiting out
the delay — turning the setting off cancels their pending delivery.

### `categories.yml`

Categories are a **display grouping only** — they decide how the "Notification types" screen is
organised. They have no effect on how notifications are actually delivered.

```yaml
uncategorized-label: "Other"

categories:
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

- **Push delivery is triggered by logging in, or by `/notifications test`** — nothing else. A
  notification queued for a player who is **already online** is not pushed until their next login,
  though they can read it in `/notifications` immediately.
- **No admin commands.** There is no way to view or edit another player's inbox or preferences.
- **Inboxes are unbounded.** Nothing trims them; entries leave only by deletion or expiry.
- **Read is tracked per player, not per delivery method.** A notification read in chat counts as read
  everywhere.
- Partial delivery failures are silent (see the player caveat above) — but no longer lossy, since the
  notification stays in the player's inbox.
- `discord-channel-ping` is reserved but not implemented; nothing can select it.
- **No admin tools for account links.** You cannot link, unlink, inspect or list another player's
  Discord link. Clearing a stuck one means a manual `DELETE` against the `DiscordAccountLink` table.
- **No rate limiting** on link attempts, in game or in Discord. A 6-character code over a
  32-character alphabet inside a 10-minute window makes brute force impractical rather than
  impossible.
- Link codes are held in memory only and are lost on restart, by design.
