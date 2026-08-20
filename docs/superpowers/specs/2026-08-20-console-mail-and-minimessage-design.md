# Console `/mail send` and MiniMessage message formatting

**Date:** 2026-08-20
**Status:** approved

## Goal

Two changes to `/mail send`, both scoped to the send path only:

1. **Console can send mail.** `/mail send <player> <message>` works from the server console (and from
   any non-player `CommandSender`). Every other `/mail` branch stays player-only — they operate on
   *your* inbox, and the console has none.
2. **The message accepts MiniMessage**, with **one permission per tag group**: colours, decorations,
   gradients and the other purely-cosmetic tags are allowed by default; the tags that can make text
   *do* something (click, hover, insertion, keybind, translatable, selector, score, nbt, font) default
   to `op`.

## Architecture

### Console attribution

`MailPayload.sender` is `@NotNull`, and the console has no UUID. Rather than widen the API record —
which feature modules compile against — the console sends as a reserved identity:

- `MailSender.SERVER_SENDER` = `new UUID(0, 0)`
- `MailSender.SERVER_NAME` = `"Server"`

so a console mail renders as *"Mail from Server"*. No schema change, no API signature change; a future
reply command special-cases the constant. The nil UUID cannot collide with a real player's.

### Where MiniMessage is parsed

**At send time, not at render time.** The permission check needs the *sender*, and a renderer runs on
read — potentially long after the send, with the sender offline or gone. So `/mail send`:

1. validates the **raw** typed message (non-blank, at most `MailPayload.MAX_MESSAGE_LENGTH`), as
   today — the limit is on what the player typed, not on what it serialises to;
2. deserialises it with a `MiniMessage` instance whose tag resolver contains **only the groups this
   sender holds the permission for**. A tag the sender may not use is not registered, so MiniMessage
   leaves it as literal text — the recipient sees `<click:run_command:/op me>`, verbatim, and nothing
   is silently dropped;
3. re-serialises the resulting `Component` with the *standard* `MiniMessage` and stores **that** string
   in `MailPayload.message`. Serialisation escapes any literal `<`, so the stored string is a canonical,
   already-authorised MiniMessage document.

`MailRenderer` therefore deserialises with the **full standard** tag set: everything in the stored
string was authorised at send time, and anything that was not is escaped literal text. This is the
whole reason for the serialise/re-parse round trip — it moves the trust decision to the one moment the
sender's permissions are knowable, and leaves the read path with nothing to decide.

`MailRenderer`'s title stays `Component.text("Mail from " + senderName)`: a player name is not a
formatting document, and parsing it would be a way to smuggle tags past the message's permission gate.

### Tag groups and permissions

Node prefix `playernotifications.command.mail.format.`, one node per group
(`MailFormatting.PERMISSION_PREFIX`):

| Node suffix | `StandardTags` | Default |
|---|---|---|
| `color` | `color()`, `shadowColor()` | `true` |
| `decoration` | `decorations()` | `true` |
| `gradient` | `gradient()`, `transition()`, `pride()` | `true` |
| `rainbow` | `rainbow()` | `true` |
| `reset` | `reset()` | `true` |
| `newline` | `newline()` | `true` |
| `font` | `font()` | `op` |
| `click` | `clickEvent()` | `op` |
| `hover` | `hoverEvent()` | `op` |
| `insertion` | `insertion()` | `op` |
| `keybind` | `keybind()` | `op` |
| `translatable` | `translatable()`, `translatableFallback()` | `op` |
| `selector` | `selector()` | `op` |
| `score` | `score()` | `op` |
| `nbt` | `nbt()` | `op` |

The split is *cosmetic vs. active*: the default-`true` groups can only change how text looks, and a
player already controls that in chat. The `op` groups can attach a runnable command or a hover payload
to text that arrives in someone else's inbox, or pull server-side state (`score`, `nbt`) into it.
`font` is `op` because a client-side font can render text unreadably or misleadingly.

The console is a `ConsoleCommandSender`, whose `hasPermission` is unconditionally true, so console mail
gets every group without a special case.

### Types and files

- **create** `platform/paper-plugin/src/main/java/.../paper/mail/MailFormatting.java`
  - `PERMISSION_PREFIX`, `record Group(String node, boolean defaultAllowed, TagResolver resolver)`,
    `List<Group> groups()`, `TagResolver resolverFor(Predicate<String> hasPermission)`,
    `String sanitize(String raw, TagResolver allowed)` (returns `""` when nothing readable survives).
  - Takes a `Predicate<String>` over permission nodes rather than a `CommandSender`, the same seam
    `MailRecipients` uses for name lookup, so every rule here is unit-testable without a server.
- **modify** `paper/mail/MailRecipients.java` — a 4-arg `resolve(name, message, resolver, formatter)`
  overload; the formatter runs *after* the length check and its blank result is rejected
  (`<red>` alone parses to an empty component). The existing 3-arg form delegates with
  `UnaryOperator.identity()`, so no existing caller or test changes.
- **modify** `paper/mail/MailSender.java` — `SERVER_SENDER` / `SERVER_NAME` constants only.
- **modify** `paper/mail/MailRenderer.java` — body via `MiniMessage.miniMessage().deserialize(...)`.
- **modify** `paper/command/MailCommand.java` — `send` runs for any `CommandSender`; the tag resolver
  is built on the command thread (permissions are read where the caller is) and the sanitise + enqueue
  happen on the async task, as today.
- **modify** `platform/paper-plugin/src/main/resources/paper-plugin.yml` — the fifteen permission nodes.

## Error handling

- A disallowed tag is **left literal**, never stripped and never an error: silently deleting part of
  someone's sentence is the failure mode `MailRecipients` already refuses for over-length messages.
- A message that is non-blank raw but carries no readable text after parsing is rejected with the
  existing "Mail cannot be blank." reply. `sanitize` judges that on the *rendered* text and returns
  `""`, because `<red>` alone parses to an empty component that still serialises back to `<red>` — a
  blankness check on the stored string would let it through and then trip `MailPayload`'s constructor.
- Malformed MiniMessage (`<red` unclosed) is not an error in MiniMessage — it renders literally. A
  **legacy section-sign code is**: MiniMessage throws `ParsingException` on `§c` rather than ignoring
  it. Both `sanitize` and `MailRenderer` therefore catch `RuntimeException` and fall back to the text
  as a literal component. The renderer needs its own guard, not just the sender's: a mail stored before
  this change, or one whose sanitising already fell back, can contain a `§`, and an old mail must never
  fail to render — falling back reproduces exactly how it read before.

## Testing strategy

Unit tests (`:platform:paper-plugin:test`), all server-free:

- `MailFormattingTest` — a permitted tag renders as formatting; a denied tag survives as literal text;
  a denied `click` cannot reach the rendered component; the default groups are the cosmetic ones; the
  round trip is idempotent (`sanitize(sanitize(x)) == sanitize(x)`).
- `MailRecipientsTest` — new cases for the formatter overload: formatter applied to the trimmed
  message; a formatter returning blank yields `InvalidMessage`; the length check still runs on the raw
  message.
- `MailRendererTest` — a stored MiniMessage body renders with its formatting; escaped literal text
  stays literal.
- `MailSenderTest` — a mail sent as `SERVER_SENDER` stores `"Server"`.

Manual (needs `:platform:paper-plugin:runServer`): console `/mail send`, the console mail appearing as
"Mail from Server" in `/mail`, a non-op player's `<click>` arriving literal, an op's arriving live.

## Known limitations

- **Permissions are evaluated at send time only.** Revoking `…format.click` afterwards does not
  neutralise mail already sent. Accepted: the alternative is storing the raw text plus the sender's
  permission set and re-deciding on every read, which makes an old mail's appearance depend on a
  player's *current* rank.
- **`MAX_MESSAGE_LENGTH` bounds the typed message, not the stored one.** A heavily-tagged 256-character
  message stores a longer string. The column is `JSON` with no practical bound, so this only matters if
  a future medium imposes a hard limit — Discord's truncation already handles its own.
- **No formatting on the recipient name or on the arrival notice.** The notice is a fixed line by
  design (see the mail spec) and the title is deliberately literal.
- **Console cannot read mail.** `/mail`, `list`, `read`, `dismiss`, `clear` stay player-only; a console
  inbox would need an admin "read another player's mail" command, which is already deferred.
