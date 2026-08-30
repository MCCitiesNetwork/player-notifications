# Mail Arrival Notice Names The Sender — Implementation Plan

**Goal:** the mail arrival notice reads "You were sent mail from &lt;sender&gt;" from a configurable
`messages.yml` key, and the Discord "Read mail" button keeps working.
**Spec:** `docs/superpowers/specs/2026-08-30-mail-arrival-notice-sender-design.md`

Two tasks. The host change breaks `discord-adapter`'s compile the moment `MailNotifier`'s signature
changes, so both modules move in **one** commit — neither half can be rejected while the other
stands, which is the split rule.

## Task 1: The notice names its sender, and the button follows the body

**Files:**

- modify `platform/paper-plugin/src/main/resources/messages.yml` (the `mail:` block, after
  `title:` at line 59)
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/localisation/MessageKeys.java` (with the other `MAIL_*` constants, lines 38–47)
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailNotifier.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java:424`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/MailCommand.java:137`
- modify `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/command/MailNoticeButton.java:39`
- modify `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/command/DiscordMailService.java:83`
- modify `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/DiscordModule.java:164`
- test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailNotifierTest.java`
- test `platform/discord-adapter/src/test/java/io/github/md5sha256/playernotifications/discord/command/MailNoticeButtonTest.java`
- test `platform/discord-adapter/src/test/java/io/github/md5sha256/playernotifications/discord/command/DiscordMailServiceTest.java:72` (constructor call only)

**Interfaces this task produces:**

```java
public MailNotifier(@NotNull NotificationSinkRegistry sinks,
                    @NotNull NotificationPreferences preferences,
                    @NotNull MessageContainer messages,
                    @NotNull Logger logger)

public void notifyArrival(@NotNull UUID recipient, @NotNull String senderName)

public @NotNull RenderableNotification arrivalNotice(@NotNull String senderName)

public static boolean isArrivalNotice(@NotNull RenderableNotification notification)
```

`MailNotifier.ARRIVAL_NOTICE` and the one-argument `notifyArrival` are **removed**.

- [ ] Write the failing tests.

  In `MailNotifierTest`, add a `MessageContainer` to every existing `new MailNotifier(...)` call
  (six of them, lines 103–168) via `TestMessages.shipped()`, pass a sender name to every
  `notifyArrival(...)` call, and add:

  ```java
  private static String plain(RenderableNotification notification) {
      return PlainTextComponentSerializer.plainText().serialize(notification.title());
  }

  @Test
  void theNoticeNamesItsSender() {
      NotificationSinkRegistry sinks = new NotificationSinkRegistry();
      RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
      sinks.registerSink(chat);
      MailNotifier notifier = new MailNotifier(sinks, fixedMedia(Set.of("chat")),
              TestMessages.shipped(), LOGGER);

      notifier.notifyArrival(UUID.randomUUID(), "Andrew");

      assertEquals(1, chat.received.size());
      assertTrue(plain(chat.received.get(0)).contains("Andrew"));
  }

  @Test
  void aSenderNameIsNeverParsedAsMarkup() {
      MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
              fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

      RenderableNotification notice = notifier.arrivalNotice("<red>Bob");

      assertTrue(plain(notice).contains("<red>Bob"));
  }

  @Test
  void everyNoticeSharesOneBodyInstance() {
      MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
              fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

      RenderableNotification first = notifier.arrivalNotice("Andrew");
      RenderableNotification second = notifier.arrivalNotice("Bob");

      assertNotEquals(first.title(), second.title());
      assertSame(first.body(), second.body());
  }

  @Test
  void onlyAnArrivalNoticeIsRecognised() {
      MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
              fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

      assertTrue(MailNotifier.isArrivalNotice(notifier.arrivalNotice("Andrew")));
      assertFalse(MailNotifier.isArrivalNotice(new RenderableNotification(
              Component.text("You were sent mail from Andrew"), Component.text("Use /mail to read it."))));
  }
  ```

  (new imports: `TestMessages`, `MessageContainer`, `Component`, `assertNotEquals`, `assertSame`.)

  In `MailNoticeButtonTest`, replace both uses of `MailNotifier.ARRIVAL_NOTICE` with a notice built
  from a real notifier:

  ```java
  private static RenderableNotification arrivalNotice() {
      return new MailNotifier(new NotificationSinkRegistry(),
              (player, dataType) -> Set.of(), TestMessages.shipped(),
              Logger.getLogger(MailNoticeButtonTest.class.getName())).arrivalNotice("Andrew");
  }
  ```

  Check `NotificationPreferences`' functional shape against the interface before writing that lambda;
  use an anonymous class if it is not a `@FunctionalInterface`.

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailNotifierTest"` — expect **compile
      failure**: `MailNotifier` has no four-argument constructor, no `arrivalNotice`, no
      `isArrivalNotice`.

- [ ] Implement, in this order:

  1. `messages.yml`, under `mail:` immediately after `title:`:

     ```yaml
       # The mail arrival notice, delivered to whichever media the recipient prefers for mail. No
       # prefix: this is also a dialog title and a Discord embed title, not only a chat line. Names
       # the sender and nothing else — the message itself is read through /mail.
       # <sender>
       arrival-notice: '<gold>You were sent mail from</gold> <white><sender></white>'
     ```

  2. `MessageKeys`: `public static final String MAIL_ARRIVAL_NOTICE = "mail.arrival-notice";`
     beside `MAIL_TITLE`.

  3. `MailNotifier`: delete `NOTICE_TITLE` and `ARRIVAL_NOTICE`; keep `NOTICE_BODY` private; add the
     `MessageContainer messages` field and constructor parameter; add

     ```java
     public @NotNull RenderableNotification arrivalNotice(@NotNull String senderName) {
         return new RenderableNotification(
                 this.messages.messageFor(MessageKeys.MAIL_ARRIVAL_NOTICE,
                         MessageContainer.value("sender", senderName)),
                 NOTICE_BODY);
     }

     public static boolean isArrivalNotice(@NotNull RenderableNotification notification) {
         return notification.body() == NOTICE_BODY;
     }
     ```

     and change `notifyArrival` to take `@NotNull String senderName`, building the notice once
     before the media loop and passing it to `deliverSafely`. Rewrite the class javadoc and
     `ARRIVAL_NOTICE`'s doc comment onto `isArrivalNotice`, carrying over the identity-not-wording
     reasoning and adding why the marker is the **body**.

  4. `PlayerNotificationsPlugin:424` — `new MailNotifier(this.sinkRegistry, this.preferences,
     this.messages, getLogger())`; check the field's actual name at that line.

  5. `MailCommand:137` — `mailNotifier.notifyArrival(ok.recipient(), senderName)`.

  6. `MailNoticeButton:39` — `if (!MailNotifier.isArrivalNotice(notification))`, and update the
     javadoc paragraph that describes the identity check to say it is the body that is matched.

  7. `DiscordMailService:83` — `this.notifier.notifyArrival(ok.recipient(), this.senderNames.apply(sender))`;
     hoist that name into a local if it is already computed above for `MailSender#send`.

  8. `DiscordModule:164` — add `plugin.messages()` as the third argument.

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailNotifierTest"` — expect PASS.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MessageKeysTest"` — expect PASS (it walks
      the constants and the shipped file in both directions).
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*MailNoticeButtonTest"` — expect PASS.
      **Needs a running Docker daemon**; if none is available, say so rather than reporting a pass.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 2: Update `CLAUDE.md`

**Files:** modify `CLAUDE.md` (the "Mail" section's arrival-notice paragraph, and the
"Deliberately still hardcoded" bullet under "Message styling")

- [ ] Rewrite the "**The arrival notice**" paragraph: it is no longer "exactly one line, verbatim,
      never templated with a sender" — it names the sender, comes from `mail.arrival-notice`, and
      still carries no count and no preview, with the reason.
- [ ] Rewrite the `MailNotifier.ARRIVAL_NOTICE` bullet under "Deliberately still hardcoded": the
      **body** is the hardcoded marker and the title is configurable; recognition is
      `MailNotifier.isArrivalNotice`, identity on the body, and a configurable body would be
      re-rendered by a reload and drop the button silently.
- [ ] Update the `MailNoticeButton.java:39` reference if the line moved.
- [ ] Check the "Tested" figures under "Testing gotchas": `:platform:paper-plugin:test` gains four
      cases. Re-measure rather than doing arithmetic — run
      `./gradlew :platform:paper-plugin:test` and count `platform/paper-plugin/build/test-results/test/*.xml`
      (glob `*.xml`, **not** `TEST-*.xml`).
- [ ] Commit.
