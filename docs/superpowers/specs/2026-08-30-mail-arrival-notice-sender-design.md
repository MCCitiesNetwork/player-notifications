# Mail arrival notice names the sender

**Date:** 2026-08-30
**Status:** proposed

## Goal

The mail arrival notice currently reads "You have new mail!" and says nothing else. A player
receiving it in chat, a dialog or a Discord DM cannot tell who wrote to them without opening
`/mail`. Name the sender in the notice — "You were sent mail from Andrew" — and make that line
configurable in `messages.yml`, without losing the Discord "Read mail" button.

Explicitly **not** in scope: the message body, or any preview of it. The notice fans out to
whichever media the player picked for `mail`, and chat is the stock default — a preview would put
private correspondence on someone else's screen and into console logs. It would also undercut the
rule mail's RETAIN processor exists to enforce: mail is stored and waits until the player asks for
it. The sender's name is not the message, and the recipient learns it the moment they open the mail
anyway.

## Architecture

`MailNotifier` stops delivering one shared `RenderableNotification` and builds one per call.

| | today | after |
|---|---|---|
| title | `static final` "You have new mail!" | rendered from `mail.arrival-notice` with `<sender>` |
| body | `static final` "Use /mail to read it." | unchanged, still the same constant instance |
| recognition | `notification == MailNotifier.ARRIVAL_NOTICE` | `MailNotifier.isArrivalNotice(notification)` |

**Recognition moves onto the body, and stays an identity check.** `MailNoticeButton` attaches the
Discord button by testing reference identity so that rewording the notice cannot silently drop the
button, and so that an unrelated notification rendering the same way never gets one. A per-sender
title is a new instance every send, which breaks `==` on the whole notification — but the body is
still one constant instance shared by every notice, so identity on `body()` preserves both
guarantees exactly. The check is wrapped in `MailNotifier.isArrivalNotice(...)` so the marker's
identity stays private to the class that owns it, rather than a second module reaching for a
constant and depending on which field carries the mark.

**The body therefore stays hardcoded while the title becomes configurable.** That asymmetry is the
price of the marker: `MessageContainer` is reloaded in place, so a configurable body would be
re-rendered into a fresh instance by `/notifications reload` and every button after that reload
would vanish, with nothing logged. The title is safe because nothing keys off it. This narrows the
entry under "Deliberately still hardcoded" in `CLAUDE.md`, which currently covers the whole notice.

**`mail.arrival-notice` carries no `<prefix>`.** Every other unprompted line does, but this one is
not chat-only: the same component is the title of a dialog and the title of a Discord embed, where
"Notifications »" is either duplicated chrome or plain wrong. The three existing `*.title` keys are
unprefixed for the same reason, and the new key sits with them.

**The sender name goes through `MessageContainer.value(...)`, never `markup(...)`** — the house rule
for anything a player typed. A player named with a `<` must not open a tag in a message its
recipient never consented to. Console sends are attributed to `MailSender.SERVER_NAME` ("Server")
with no special case, so the notice reads "You were sent mail from Server" and matches the
"Mail from Server" title `/mail` then shows.

### Types and files

**`platform/paper-plugin`**

- `paper/mail/MailNotifier.java` — gains a `MessageContainer` constructor parameter.
  `ARRIVAL_NOTICE` is removed. New public members:
  - `void notifyArrival(@NotNull UUID recipient, @NotNull String senderName)` (replacing the
    one-argument form)
  - `@NotNull RenderableNotification arrivalNotice(@NotNull String senderName)` — the notice this
    class would deliver, public so a sink's tests can build a real one
  - `static boolean isArrivalNotice(@NotNull RenderableNotification notification)`
- `paper/localisation/MessageKeys.java` — `MAIL_ARRIVAL_NOTICE = "mail.arrival-notice"`.
- `resources/messages.yml` — under `mail:`, after `title`:

  ```yaml
  # The mail arrival notice, delivered to whichever media the recipient prefers for mail. No
  # prefix: this is also a dialog title and a Discord embed title, not only a chat line. Names the
  # sender and nothing else — the message itself is read through /mail.
  # <sender>
  arrival-notice: '<gold>You were sent mail from</gold> <white><sender></white>'
  ```

- `paper/command/MailCommand.java:137` — passes the `senderName` it already computed.
- `paper/PlayerNotificationsPlugin.java:424` — passes `this.messages`.

**`platform/discord-adapter`**

- `discord/command/MailNoticeButton.java:39` — `if (!MailNotifier.isArrivalNotice(notification))`.
- `discord/command/DiscordMailService.java:83` — passes `this.senderNames.apply(sender)`, the same
  value it already hands `MailSender#send`.
- `discord/DiscordModule.java:164` — passes `plugin.messages()`.

## Error handling

Nothing new can fail. `MessageContainer#messageFor` renders an absent key as the key's own name
rather than throwing, and `MessageKeysTest` is the defence against that reaching a server. A blank
or null sender name is not defended against: `MailSender#send` already requires one, and both call
sites derive it from a `Player` or the `SERVER_NAME` constant.

The delivery loop is untouched — a sink with no registration is still skipped, a throwing sink is
still caught and logged, and a globally muted recipient still returns before any medium is resolved.

## Testing

- `MailNotifierTest` — extend the existing fake-sink fixture, which already uses
  `paper.localisation.TestMessages`:
  - the delivered notice's title contains the sender name
  - a sender named `<red>x` arrives literally, not as a colour (the `value()` rule)
  - two notices for different senders have titles that differ and bodies that are the **same
    instance** (the marker's contract, stated as a test so a future refactor cannot quietly break
    the Discord button from the host side)
  - `isArrivalNotice` is true for `arrivalNotice(...)` and false for an unrelated
    `RenderableNotification`
- `MailNoticeButtonTest` — build the notice through a real `MailNotifier` rather than the deleted
  constant; keep the negative case.
- `MessageKeysTest` — passes for free once the constant and the YAML line are both added; it is what
  makes adding only one of them a failure.

Nothing here needs Docker or a live server. Manual verification is folded into the existing
unverified mail checklist rather than duplicated: `/mail send` from a player, from the console, and
one from Discord, each producing a notice naming the right sender, with the DM still carrying its
button.

## Known limitations

- **The join-time mail line is unchanged and still a count.** `JoinDeliveryListener` does not call
  `MailNotifier` at all; its `join.unread-mail` line describes a backlog, which may span several
  senders and has no single name to give. Naming senders there would mean a query returning distinct
  senders and a wording decision for the many-sender case, for a line whose job is to point at
  `/mail`.
- **The notice still says nothing about how many mails are waiting.** One send is one notice, so a
  burst produces a burst. Adding a count means a `unreadCount(recipient, mail)` query on the send
  path; deliberately deferred until someone reports the noise.
- **The body cannot be configured**, per the marker argument above. If an operator asks, the fix is a
  marker a reload cannot invalidate — a field on `RenderableNotification`, which is an `api` record
  consumed by separately compiled feature modules and so a breaking change worth its own design.
- **`MailNotifier`'s signature changes rather than gaining an overload.** It lives in `paper-plugin`,
  not the published `api` module, but `discord-adapter` compiles against it — a module jar built
  before this change and dropped into `modules/` afterwards would fail with `NoSuchMethodError` at
  send time. Both are built from this tree together, and an overload preserving a notice that cannot
  name its sender is worse than the clean signature.
