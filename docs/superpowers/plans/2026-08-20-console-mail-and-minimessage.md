# Console `/mail send` and MiniMessage Implementation Plan

**Goal:** `/mail send` works from the console and accepts MiniMessage, gated by one permission per tag group.
**Spec:** `docs/superpowers/specs/2026-08-20-console-mail-and-minimessage-design.md`

## Task 1: `MailFormatting` — permission-gated tag resolver

**Files:** create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailFormatting.java`; test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailFormattingTest.java`

**Interfaces:**
- `public static final String PERMISSION_PREFIX = "playernotifications.command.mail.format."`
- `public record Group(String node, boolean defaultAllowed, TagResolver resolver)`
- `public static List<Group> groups()`
- `public static TagResolver resolverFor(Predicate<String> hasPermission)`
- `public static String sanitize(String raw, TagResolver allowed)`

- [ ] Write the failing test `MailFormattingTest`: `<red>hi` sanitized with an allow-all predicate deserialises to a red component; sanitized with a deny-`click` predicate, `<click:run_command:/op me>x</click>` yields a component whose `clickEvent()` is null and whose flattened text contains `<click:`; `groups()` defaults are exactly `color, decoration, gradient, rainbow, reset, newline` allowed by default; `sanitize` is idempotent.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailFormattingTest"` — expect FAIL: `MailFormatting` does not exist.
- [ ] Implement `MailFormatting` per the spec's tag-group table, building a `MiniMessage` from the permitted resolver, deserialising, and re-serialising with `MiniMessage.miniMessage()`; catch `RuntimeException` and fall back to `MiniMessage.miniMessage().serialize(Component.text(raw))`.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 2: formatter seam in `MailRecipients`, MiniMessage rendering, server sender constants

**Files:** modify `paper/mail/MailRecipients.java`, `paper/mail/MailRenderer.java`, `paper/mail/MailSender.java`; tests `MailRecipientsTest`, `MailRendererTest`, `MailSenderTest`

**Interfaces:**
- `MailRecipients.resolve(String name, String message, Function<String, UUID> resolver, UnaryOperator<String> formatter)`
- `MailSender.SERVER_SENDER` (`new UUID(0, 0)`), `MailSender.SERVER_NAME` (`"Server"`)

- [ ] Write the failing tests: `MailRecipientsTest` — formatter applied to the trimmed message; a formatter returning `""` gives `InvalidMessage`; a 257-character message is rejected before the formatter runs (formatter never invoked). `MailRendererTest` — body of `<red>hi` renders red; body of `\<red>hi` renders the literal text. `MailSenderTest` — `send(MailSender.SERVER_SENDER, MailSender.SERVER_NAME, …)` stores `senderName` `"Server"`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailRecipientsTest" --tests "*MailRendererTest" --tests "*MailSenderTest"` — expect FAIL: no 4-arg overload, renderer still plain-text, no constants.
- [ ] Implement: the 4-arg overload (3-arg delegates with `UnaryOperator.identity()`), `MailRenderer` body via `MiniMessage.miniMessage().deserialize(payload.message())`, the two `MailSender` constants.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 3: console-capable `/mail send` and the permission nodes

**Files:** modify `paper/command/MailCommand.java`, `platform/paper-plugin/src/main/resources/paper-plugin.yml`

- [ ] No unit test: `MailCommand`'s Brigadier wiring needs a live server — it is the existing exception recorded for the whole `/mail` surface. Manual verification below.
- [ ] Implement: `send` resolves sender identity (`Player` → uuid/name, otherwise `MailSender.SERVER_SENDER`/`SERVER_NAME`), builds the tag resolver from `MailFormatting.resolverFor(sender::hasPermission)` **on the command thread**, then dispatches async and calls the 4-arg `MailRecipients.resolve` with `raw -> MailFormatting.sanitize(raw, allowed)`; replies go to the `CommandSender`, not a `Player`. All other branches keep the player-only `run` helper.
- [ ] Add the fifteen `playernotifications.command.mail.format.*` nodes to `paper-plugin.yml` with the spec's defaults.
- [ ] Run `./gradlew build`.
- [ ] Manual, with `./gradlew :platform:paper-plugin:runServer`:
      1. `mail send <player> <gray>hello` from the console — reply confirms, no "Only players" message.
      2. `/mail` as the recipient shows "Mail from Server" with grey body.
      3. As a non-op player, `/mail send <other> <click:run_command:/op me>click</click>` — recipient sees the tag as literal text, and clicking does nothing.
      4. As op, the same message — recipient sees a working click.
      5. `/mail send <other> <red>` alone — rejected with "Mail cannot be blank."
- [ ] Commit.

## Task 4: documentation

**Files:** modify `CLAUDE.md`

- [ ] Update the "Mail" section: the `/mail send` row noting console support and MiniMessage; the `MailRenderer` paragraph, which currently states the body is *never* MiniMessage — that rule is now inverted and its replacement (authorised at send time, escaped otherwise) must be stated in its place; the permission list; and the "Current state" manual-checklist entry to include Task 3's five items.
- [ ] Commit.
