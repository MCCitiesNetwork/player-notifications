# Uniform message styling — design

**Status:** implemented
**Date:** 2026-08-23

## Goal

Give every player-facing chat message in PlayerNotifications one visual language, transplanted from
`realty`, and close the three places where a message escapes `messages.yml` altogether.

The complaint is concrete. Today the plugin has a `prefix` key that **no message references** — a
brand mark that has never once appeared in game. Around it sit messages coloured one at a time: some
are a single `<red>…</red>` wrapper, some carry three colours, the chat listing has a header but no
footer, `read <entry>` prints `title().colorIfAbsent(GOLD)` with no framing at all, and seven
preference replies are hardcoded `Component.text(...)` that an operator cannot reach. Nothing looks
like it came from the same plugin, because in effect it did not.

`realty` solves this with a vocabulary rather than a stylesheet: one gradient prefix, one accent
bullet, and a fixed meaning per colour, applied the same way in 806 lines of messages. This design
copies that vocabulary, not those strings.

## The vocabulary

| Role | Markup | Where |
|---|---|---|
| Brand prefix | `<newline><gradient:#00E0C0:#00A8FF><b>Notifications</b> <dark_grey>»<reset>` | the `prefix` key |
| Accent bullet | ` <#00A8FF><b>»</b></#00A8FF> ` | list entries |
| Row index | `<dark_gray>#<entry></dark_gray>` | listing rows |
| Success | `<green>` | a thing that happened |
| Failure | `<red>` | a thing that did not |
| Caution / player state | `<yellow>` | muted, silenced, unread counts |
| The subject acted on | `<gold>` | a mail's title, a deleted entry |
| Live value | `<white>` | unread content, a medium's name, a command to type |
| Hint / already-read | `<gray>` | the line under a reply, read rows |
| Structure | `<dark_gray>` | brackets, separators, `#` |
| Pager arrows | `<yellow>«</yellow>` / `<yellow>»</yellow>` | listing footer |

The hue is `realty`'s structure in a different gradient (`#00E0C0 → #00A8FF` against realty's
`#00aaff → #008EFF`). Deliberate: the two plugins run on the same network and share a shape so they
read as one suite, but a player glancing at chat should be able to tell which one is talking. Copying
realty's exact gradient was the alternative; it makes the two indistinguishable at the only moment
the distinction matters.

The leading `<newline>` is realty's, kept for the same reason: it gives every reply a gap above it,
which is what stops a block message from being swallowed by combat spam. The cost is a blank line
before one-line replies too. Accepted — it is the single largest reason realty's chat output reads
as composed rather than as debug logging.

## The prefix rule

> "Only use prefix where appropriate."

**Prefix a message when it is the plugin speaking on its own initiative, or answering a command in
one line.** Do not prefix a line that is *inside* something already introduced.

Prefixed: `common.error`, `notifications.players-only`, `reload.*`, `mail.players-only`,
`mail.sent`, `mail.unknown-player`, `mail.blank-message`, `mail.message-too-long`, every
`broadcast.*` except `broadcast.title`, every `join.*`, `inbox.empty`, `inbox.already-empty`,
`inbox.gone`, `inbox.cleared-one`, `inbox.cleared-many`, `inbox.header`, `inbox.deleted`,
`inbox.list-first`, `inbox.no-entry`, `inbox.read-title`, `link.none-available`, `link.header`,
`link.unavailable`, every `test.*` except `test.separator`, every `preferences.*` except
`preferences.session-discarded`.

Unprefixed: `inbox.title`, `mail.title`, `broadcast.title` (screen *names*, not messages);
`inbox.row.*`, `mail.row.*`, `link.entry` (rows under a header that already carried it);
`inbox.usage`, `inbox.row-hover`, `inbox.footer`, `inbox.footer-previous`, `inbox.footer-next`,
`inbox.read-body`, `test.separator`, `preferences.session-discarded` (continuations and fragments).

The rule is mechanical enough to check: a prefixed key is one whose message could be the first thing
a player sees.

## Changes

### 1. `messages.yml` — every value restyled

No key is renamed except where noted below, so `MessageKeys` and every call site keep working. The
file's comment header gains the vocabulary table so an operator editing one message can match the
rest.

Two value changes carry meaning beyond colour:

- **`inbox.title` becomes `Inbox`** (from `Notifications`). It is the dialog's title *and* the
  `<title>` slot of `inbox.header`, so with the prefix applied the header read "Notifications »
  Notifications — page 1 of 3". `Inbox` is both shorter and more accurate: `/mail` titles the same
  screen `Mail`, and the two now read as siblings.
- **`inbox.row.title-only-*` becomes `#<entry> <title>`** (from `<entry>. <title>`), so a
  notification row and a mail row share their leading column. `mail.row.*` is unchanged in text —
  only its colours move — which keeps `MailChatRowTest`'s exact plain-text assertions valid.

### 2. A clickable page footer on the chat listings

`realty`'s listings end with `« Page 1 of 3 »`, each arrow running the command for that page. Ours
end with a usage hint and nothing else, so a chat-fallback reader on page 1 has no way to reach page
2 but to know that `/notifications list 2` exists.

New keys `inbox.footer`, `inbox.footer-previous`, `inbox.footer-next`, and the inert forms
`inbox.footer-previous-inert` / `inbox.footer-next-inert`. **The click is attached in
Java, not in the file** — `MessageContainer` has no way to fill a `<click>` tag *argument*, and
realty pays for that with a `deserializeRaw` subclass this plugin deliberately does not have. So the
two arrow keys hold arrow text only, and `paper.inbox.InboxChatFooter` wraps each in a
`ClickEvent.runCommand`.

**An arrow's live and inert forms are two keys, not one key recoloured in Java.** MiniMessage renders
`<yellow>«</yellow>` as a parent carrying a coloured *child*, so `Component#color` on what `messageFor`
returns is a no-op — the first implementation did exactly that and the unclickable arrow still rendered
yellow, indistinguishable from the live one. Two keys also leave the look where the rest of it lives:
an operator can restyle either state, or blank the inert one to hide it after all.

`InboxChatFooter` is a separate class rather than a private method on `InboxRouter` for the reason
`MailChatRow` and `InboxFilters` are: it holds a decision (when an arrow is live, what command it
runs) and no Bukkit type, so it is unit testable without a server. An arrow at the end of its range
is rendered inert — present but unclickable and dimmed — rather than omitted, so the footer does not
change width as a player pages through it.

The footer is emitted only when `totalPages() > 1`. A one-page listing gets the usage hint alone;
a pager offering no destination is noise.

### 3. `read <entry>` gets a frame

`InboxRouter.readInChat` currently sends `rendered.title().colorIfAbsent(NamedTextColor.GOLD)` and
then the body, both unreachable from `messages.yml`. New keys `inbox.read-title` (prefixed, so the
entry announces itself the way every other reply does) and `inbox.read-body` (unprefixed, since it
continues the line above). The rendered title and body go in as `markup()` — they are Components the
plugin built, not player text, which is the container's own criterion.

### 4. The seven hardcoded preference replies move into the file

`PreferenceDialogRouter` sends seven `Component.text(...)` replies that never touch the container:
save succeeded, save failed, changes discarded, muted, unmuted, mute failed, unmute failed — plus a
`" Any unsaved preference changes were discarded."` suffix appended by string concatenation.

New `preferences.*` section: `saved`, `save-failed`, `discarded`, `muted`, `unmuted`, `mute-failed`,
`unmute-failed`, `session-discarded`. **Mute and unmute get a key each rather than one key with the
verb substituted**, which is this repo's existing rule ("text whose value varies per call gets a key
per case, not a ternary") and the reason `join.unread-one`/`-many` are already separate. The current
code builds those two strings with `"Could not " + (muted ? "mute" : "unmute")`, which is exactly the
construction the rule exists to prevent.

`PreferenceDialogRouter` takes a `MessageContainer` as its first constructor argument, matching
`InboxRouter` and `AccountLinkDispatcher`. `PlayerNotificationsPlugin` is the only construction site.

## Files touched

| File | Change |
|---|---|
| `platform/paper-plugin/src/main/resources/messages.yml` | every value restyled; `preferences.*`, `inbox.footer*`, `inbox.read-*` added |
| `…/paper/localisation/MessageKeys.java` | 13 new constants |
| `…/paper/inbox/InboxChatFooter.java` | **new** — the pager |
| `…/paper/inbox/InboxRouter.java` | emit the footer; frame `readInChat` |
| `…/paper/preferences/PreferenceDialogRouter.java` | take a `MessageContainer`; seven replies via the container |
| `…/paper/PlayerNotificationsPlugin.java` | pass `messages` to the router |
| `…/test/…/paper/inbox/InboxChatFooterTest.java` | **new** |

## Error handling

Nothing new can fail. `MessageContainer.messageFor` never throws: an unknown key renders as its own
name and a malformed template does the same. The `<gradient>` and `<newline>` tags in the prefix are
parsed once and cached, and a prefix that will not parse falls back to `Component.empty()` — so the
worst outcome of a bad edit is an unprefixed message, never a broken reply.

`InboxChatFooter` is total: it clamps nothing and queries nothing, taking the page numbers
`InboxPage` already clamped.

## Testing

- `MessageKeysTest` already walks `MessageKeys` against the shipped file **in both directions**, so
  all 13 new keys are covered the moment they exist — a constant without a line, or a line without a
  constant, fails it.
- `InboxChatFooterTest` (new): a middle page renders both arrows with the right commands; page 1 has
  an inert previous arrow; the last page has an inert next arrow; a single-page listing renders
  nothing; the command label is honoured, so `/mail list 2` is never emitted by the notifications
  router; and each arrow's colour is asserted per glyph, which is what caught the `Component#color`
  no-op above.
- Existing wording assertions constrain the restyle and must keep passing unchanged:
  `TestNotificationSenderTest` (`muted`, `silenced`, `no delivery methods`, `skipped`, and that the
  no-media reply does **not** say "silenced"), `JoinDeliveryListenerTest`
  (`1 unread notification.`, `/notifications`, `/mail`), `MailChatRowTest` (exact plain text
  `#1 [14:03] [Steve] …`), `BroadcasterTest` (`broadcast.title` is plain-text `Broadcast`).
- `PreferenceDialogRouter`'s replies and the framed `read` view need a live `Player` and stay
  manually verified, per this repo's standing exception.

## Known limitations

- **The dialogs are untouched.** The six preference screens and the three inbox screens still carry
  hardcoded English. `paper.ui` must import nothing from this plugin, so their strings have to arrive
  as `Component` parameters — a boundary change, not a styling one, and worth its own design.
- **`MailNotifier.ARRIVAL_NOTICE` stays hardcoded.** The Discord adapter recognises it by reference
  identity, and a configurable notice is rebuilt on reload, which would drop the "Read mail" button
  silently. Unchanged from the messages design that first recorded this.
- **Renderer titles stay hardcoded** (`MailRenderer`, `TestNotificationRenderer`) — they render
  *stored* notifications, so editing them changes how old notifications read.
- **Both feature modules are out of scope.** The Discord adapter's strings are Discord-shaped and
  belong in a `discord-messages.yml` if ever configured.
- **Still one language, one file.** No per-player locale.
- **The prefix's leading newline applies to one-line replies too**, so `/mail send` costs a blank
  line. This is realty's rhythm and is the deliberate trade named above; removing the `<newline>`
  from the `prefix` key is the operator's one-character escape hatch.
- **The footer's inert arrows are dimmed, not hidden**, so a player may click one and get nothing.
  Hiding them shifts the footer's width between pages, which reads worse — but the inert forms are
  their own keys, so an operator who disagrees can blank them.
