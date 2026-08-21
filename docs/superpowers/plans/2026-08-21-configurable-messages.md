# Configurable Messages Implementation Plan

**Goal:** Move the host plugin's chat and command text into a server-editable `messages.yml`
rendered through `plugin-infrastructure`'s `MessageContainer`.
**Spec:** `docs/superpowers/specs/2026-08-21-configurable-messages-design.md`

## Task graph

```
                          ┌──────────────────────────┐
                          │ T1  Foundation           │
                          │ container, MessageKeys,  │
                          │ messages.yml, reload,    │
                          │ TestMessages, key test   │
                          └────────────┬─────────────┘
                                       │  (blocks everything)
        ┌────────────┬────────────┬────┴───────┬────────────┬────────────┐
        │            │            │            │            │            │
     ┌──▼──┐      ┌──▼──┐      ┌──▼──┐      ┌──▼──┐      ┌──▼──┐      ┌──▼──┐
     │ T2  │      │ T3  │      │ T4  │      │ T5  │      │ T6  │      │ T7  │
     │test.│      │link.│      │inbox│      │join.│      │mail.│      │bcast│
     │     │      │notif│      │ row │      │     │      │recip│      │args │
     └──┬──┘      └──┬──┘      └──┬──┘      └──┬──┘      └──┬──┘      └──┬──┘
        └────────────┴────────────┴─────┬──────┴────────────┴────────────┘
                                        │  (all six)
                              ┌─────────▼─────────┐
                              │ T8  Docs + verify │
                              └───────────────────┘
```

**T2–T7 are genuinely parallel.** T1 writes the **complete** `messages.yml` and the **complete**
`MessageKeys` up front — both are pure data and cheap to write once — precisely so that the six
conversion tasks never touch either file and cannot conflict there.

**The one shared file is `PlayerNotificationsPlugin`.** Each of T2–T7 adds the `messages` argument
to one or two constructor calls in it: T2 at the `TestNotificationSender` construction, T3 at
`AccountLinkDispatcher`, T4 at both `InboxRouter`s, T5 at `JoinDeliveryListener`, T6 at
`MailCommand`, T7 at `BroadcastCommand`. Those are distinct, non-adjacent lines, so parallel
worktrees merge cleanly; if run sequentially it is a non-issue. This is stated rather than
engineered around — pretending the tasks are fully disjoint would be wrong.

Run T2–T7 in any order or all at once. Each is independently testable, independently committable,
and independently rejectable.

---

## Task 1: Foundation

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/localisation/MessageKeys.java`
- create `platform/paper-plugin/src/main/resources/messages.yml`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/localisation/TestMessages.java`
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/localisation/MessageKeysTest.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`

**Interfaces this task produces:**

```java
// paper.localisation.MessageKeys — every key in messages.yml, e.g.
public static final String COMMON_ERROR = "common.error";
public static final String MAIL_SENT = "mail.sent";
// … one constant per key listed in the spec's messages.yml block

// PlayerNotificationsPlugin
public @NotNull MessageContainer messages();

// paper.localisation.TestMessages (test sources)
public static @NotNull MessageContainer shipped();
```

`MessageContainer` is
`com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer` — used directly, no
subclass. See the spec's "No subclass" section for why.

- [ ] Write `messages.yml` with the exact content in the spec's messages.yml block, transcribing
      each value from the literal at the call site named in the spec's "Call sites in scope" table.
- [ ] Write `MessageKeys` with one constant per key, grouped by section with a `// <section>`
      comment above each group, matching realty's `MessageKeys` layout.
- [ ] Write the failing test — `MessageKeysTest`:

```java
class MessageKeysTest {

    @Test
    void everyDeclaredKeyResolves() throws Exception {
        MessageContainer messages = TestMessages.shipped();
        for (String key : declaredKeys()) {
            assertNotEquals(key, messages.miniMessageFormattedFor(key),
                    "MessageKeys declares " + key + " but messages.yml has no such key");
        }
    }

    @Test
    void everyShippedKeyIsDeclared() throws Exception {
        Set<String> declared = new HashSet<>(declaredKeys());
        for (String key : TestMessages.shippedKeys()) {
            assertTrue(declared.contains(key),
                    "messages.yml has " + key + " but MessageKeys declares no constant for it");
        }
    }

    private static List<String> declaredKeys() throws IllegalAccessException {
        List<String> keys = new ArrayList<>();
        for (Field field : MessageKeys.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                keys.add((String) field.get(null));
            }
        }
        return keys;
    }
}
```

- [ ] Write `TestMessages`: `shipped()` loads `messages.yml` from the test classpath with
      `YamlConfigurationLoader.builder().source(() -> new BufferedReader(new InputStreamReader(
      TestMessages.class.getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8))).build().load()`
      and calls `load(node)` on a fresh container; `shippedKeys()` flattens the same node to dotted
      keys for the converse assertion.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MessageKeysTest"` — expect FAIL: neither
      `MessageKeys` nor `messages.yml` is wired into the source set yet on first run, and any
      transcription mismatch between them surfaces here.
- [ ] Implement the plugin wiring: `private final MessageContainer messages = new MessageContainer();`,
      the `messages()` accessor, `reloadMessages()` calling `this.messages.load(copyDefaultsYaml("messages"))`,
      a call to it in `onEnable` **before** `registerCommands()`, and a call in `reload()` beside the
      existing settings and categories reloads.
- [ ] Run the same test command — expect PASS.
- [ ] **Verify the concurrency note in the spec's Known limitations.** Run
      `javap -p -c` over `MessageContainer.class` from the plugin-infrastructure jar (extract it from
      `~/.gradle/caches/modules-2/files-2.1/com.minecraftcitiesnetwork/`) and read what `rawMessages`
      is actually initialised to. If it is a `ConcurrentHashMap`, leave the limitation as written. If
      it is a `HashMap`, change the wiring to load into a **fresh** container and swap a `volatile`
      field, and rewrite that limitation entry to record the fix and its reason.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 2: `test.*` — TestNotificationSender

**Depends on:** T1. **Parallel with:** T3–T7.

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/diagnostic/TestNotificationSender.java` (12 sites)
- modify `PlayerNotificationsPlugin.java` (the `TestNotificationSender` construction)
- create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/diagnostic/TestNotificationSenderTest.java`

**Interfaces:** `TestNotificationSender` gains a leading `@NotNull MessageContainer messages`
constructor parameter, before the existing `Supplier<NotificationDelivery>`.

This class has **no existing test** — CLAUDE.md records it as unverified — but it is a plain class
taking a `Supplier` and a `NotificationPreferences`, so the four reply branches are directly
testable. This task adds that coverage rather than deferring it.

- [ ] Write the failing test — one case per reply branch, each asserting the rendered plain text:

```java
@Test
void mutedPlayerIsToldTheNotificationWasSuppressed() {
    RecordingPreferences prefs = new RecordingPreferences();
    prefs.setMuted(PLAYER, true);
    TestNotificationSender sender =
            new TestNotificationSender(TestMessages.shipped(), () -> delivery, prefs, service);

    Component reply = sender.report(PLAYER);

    assertTrue(PlainTextComponentSerializer.plainText().serialize(reply)
            .contains("/notifications unmute"));
}
```

  plus `silencedTypeIsCalledOut`, `noChosenMediaIsCalledOut`, and
  `attemptedMediaAreListedWithSeparators` (asserting the `test.separator` key joins two media).
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*TestNotificationSenderTest"` — expect
      FAIL: the constructor does not take a `MessageContainer`.
- [ ] Implement: replace all twelve `Component.text(...)` sites with `messages.messageFor(...)`,
      using `MessageContainer.value("media", …)` for the medium list in `test.no-sink` and
      `MessageContainer.markup(...)` where a `Component` is interpolated. Delete the
      `SEPARATOR`/mute/silence `static final Component` constants — they cannot be static once the
      text is reloadable.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 3: `link.*` and `notifications.*` — AccountLinkDispatcher, NotificationsCommand

**Depends on:** T1. **Parallel with:** T2, T4–T7.

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/AccountLinkDispatcher.java` (7 sites)
- modify `.../paper/command/NotificationsCommand.java` (1 site — `notifications.players-only`)
- modify `PlayerNotificationsPlugin.java` (the `AccountLinkDispatcher` and `NotificationsCommand` constructions)
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/command/AccountLinkDispatcherTest.java`

**Interfaces:** `AccountLinkDispatcher` and `NotificationsCommand` each gain a leading
`@NotNull MessageContainer messages` constructor parameter.

`NotificationsCommand` is wiring only and has no unit test — its one site is covered by
`MessageKeysTest` and by Task 8's manual pass. It rides with this task because it is the class that
hosts the `link` subtree, so both edits land on the same Brigadier tree and the same construction
site in `PlayerNotificationsPlugin`.

- [ ] Update the existing test to construct with `TestMessages.shipped()`, and add:

```java
@Test
void providerListEntryNamesTheCommandThatStartsTheLink() {
    AccountLinkDispatcher dispatcher =
            new AccountLinkDispatcher(TestMessages.shipped(), registryWith("discord"));

    String reply = PlainTextComponentSerializer.plainText().serialize(dispatcher.list());

    assertTrue(reply.contains("/notifications link discord"));
}
```

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*AccountLinkDispatcherTest"` — expect
      FAIL: the constructor does not take a `MessageContainer`.
- [ ] Implement: `link.none-available`, `link.header`, `link.entry` (resolvers `name` via `markup`
      for the provider's `displayName()` `Component`, and `key` via `value`), `link.unavailable`,
      `common.error`. The per-provider loop builds each line from `link.entry` rather than
      concatenating four `Component.text` fragments. In `NotificationsCommand`, replace the
      `PLAYERS_ONLY` static constant with `messages.messageFor(MessageKeys.NOTIFICATIONS_PLAYERS_ONLY)`
      read at reply time — it cannot stay `static final` once the text is reloadable.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 4: `inbox.*` and the row formats

**Depends on:** T1. **Parallel with:** T2, T3, T5–T7.

**Files:**
- modify `.../paper/inbox/InboxRouter.java` (6 sites)
- modify `.../paper/inbox/InboxChatRow.java` (1 site)
- modify `.../paper/mail/MailChatRow.java` (3 sites)
- modify `PlayerNotificationsPlugin.java` (both `InboxRouter` constructions, and the
  `InboxChatRow.titleOnly()` / `MailChatRow` construction that feeds them)
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailChatRowTest.java`

**Interfaces:**
- `InboxRouter` gains a leading `@NotNull MessageContainer messages` constructor parameter.
- `InboxChatRow.titleOnly()` becomes `InboxChatRow.titleOnly(@NotNull MessageContainer messages)`.
- `MailChatRow`'s constructor gains a leading `@NotNull MessageContainer messages` parameter.

`InboxRouter` is **not unit tested** — it marshals onto the Bukkit scheduler and needs a live
server. Its six sites are verified by `MessageKeysTest` plus the manual checklist below.
`MailChatRow` and `InboxChatRow` are pure and stay directly tested.

- [ ] Update `MailChatRowTest` to construct with `TestMessages.shipped()`, and add:

```java
@Test
void rowKeepsEntryTimeAndSenderColumns() {
    MailChatRow row = new MailChatRow(TestMessages.shipped(), ZONE, CLOCK, decoder);

    String rendered = PlainTextComponentSerializer.plainText()
            .serialize(row.render(3, entry, rendered("Mail from Bob")));

    assertTrue(rendered.startsWith("#3 "));
    assertTrue(rendered.contains("[Bob] "));
}
```

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailChatRowTest"` — expect FAIL: the
      constructor does not take a `MessageContainer`.
- [ ] Implement. `InboxRouter`: `inbox.empty` (the one `EMPTY_MESSAGE` constant, read in both
      places — see the spec), `inbox.already-empty`, `inbox.gone`, `inbox.page-footer` (`page`,
      `total-pages`), `inbox.usage` (`command`), `inbox.deleted` (`title` via `markup`).
      `MailChatRow`: `mail.row.entry` (`entry`, `time`) and `mail.row.sender` (`sender`).
      `InboxChatRow.titleOnly`: `inbox.row.title-only` (`entry`, `title` via `markup`).
      **The click and hover events stay in Java**, applied to the rendered `Component` — that is the
      whole reason no subclass is needed.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] **Manual (needs a live server — `./gradlew :platform:paper-plugin:runServer`):** run
      `/notifications list` and `/mail list` and confirm rows still render with their columns and
      that clicking a row still runs the right `read` command for that listing; run
      `/notifications clear` twice and confirm the second reply is the already-empty wording.
- [ ] Commit

---

## Task 5: `join.*` — JoinDeliveryListener

**Depends on:** T1. **Parallel with:** T2–T4, T6, T7.

**Files:**
- modify `.../paper/JoinDeliveryListener.java` (4 sites)
- modify `PlayerNotificationsPlugin.java` (the `JoinDeliveryListener` construction)
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/JoinDeliveryListenerTest.java`

**Interfaces:** `JoinDeliveryListener` gains a leading `@NotNull MessageContainer messages`
constructor parameter. `announcements(UUID)` keeps its package-private signature — it is already the
seam that makes the gate testable without a live `Player`, and this task does not move it.

- [ ] Update the existing test to construct with `TestMessages.shipped()`, and add:

```java
@Test
void unreadLineNamesTheCount() {
    JoinDeliveryListener listener = listenerWith(TestMessages.shipped(), unreadCount(4), noMail());

    List<Component> lines = listener.announcements(PLAYER);

    assertTrue(PlainTextComponentSerializer.plainText().serialize(lines.getFirst()).contains("4"));
}
```

  and confirm the existing muted-player case still asserts an **empty** list.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*JoinDeliveryListenerTest"` — expect FAIL:
      the constructor does not take a `MessageContainer`.
- [ ] Implement: `join.unread` (resolver `count`) and `join.unread-mail`. Both lines were built as
      two concatenated `Component.text` fragments; each becomes one key, so an operator can reword
      the whole sentence rather than only half of it.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 6: `mail.*` — MailCommand and MailRecipients

**Depends on:** T1. **Parallel with:** T2–T5, T7.

**Files:**
- modify `.../paper/mail/MailRecipients.java` (result hierarchy)
- modify `.../paper/command/MailCommand.java` (4 sites)
- modify `PlayerNotificationsPlugin.java` (the `MailCommand` construction)
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailRecipientsTest.java`

**Interfaces:**

```java
// MailRecipients.Result — InvalidMessage is REMOVED
record Ok(@NotNull UUID recipient, @NotNull String message) implements Result {}
record UnknownPlayer(@NotNull String name) implements Result {}
record BlankMessage() implements Result {}
record MessageTooLong(int maxLength) implements Result {}

// MailCommand gains a leading @NotNull MessageContainer messages constructor parameter
```

`MailRecipients.resolve`'s own signature is unchanged. `InvalidMessage` is removed outright, not
deprecated: it is internal to `platform:paper-plugin` and not in the `api` module, so nothing
compiled separately holds a reference.

- [ ] Rewrite the failing assertions in `MailRecipientsTest` to match on type:

```java
@Test
void blankMessageIsRejected() {
    assertInstanceOf(MailRecipients.Result.BlankMessage.class,
            MailRecipients.resolve("Bob", "   ", KNOWN_PLAYERS));
}

@Test
void overlongMessageIsRejectedNamingTheLimit() {
    MailRecipients.Result result =
            MailRecipients.resolve("Bob", "x".repeat(MailPayload.MAX_MESSAGE_LENGTH + 1), KNOWN_PLAYERS);

    assertEquals(MailPayload.MAX_MESSAGE_LENGTH,
            assertInstanceOf(MailRecipients.Result.MessageTooLong.class, result).maxLength());
}
```

- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailRecipientsTest"` — expect FAIL:
      `BlankMessage` and `MessageTooLong` do not exist.
- [ ] Implement the result split in `MailRecipients`, then in `MailCommand` map each case to a key:
      `mail.players-only`, `mail.sent` (`recipient`), `mail.unknown-player` (`name`),
      `mail.blank-message`, `mail.message-too-long` (`max`). The `switch` over `Result` is already
      exhaustive on a sealed interface, so the compiler names any case left unhandled.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 7: `broadcast.*` — BroadcastCommand, BroadcastArguments, Broadcaster

**Depends on:** T1. **Parallel with:** T2–T6.

**Files:**
- modify `.../paper/broadcast/BroadcastArguments.java` (result hierarchy)
- modify `.../paper/broadcast/Broadcaster.java` (1 site)
- modify `.../paper/command/BroadcastCommand.java` (5 sites)
- modify `PlayerNotificationsPlugin.java` (the `BroadcastCommand` construction)
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/broadcast/BroadcastArgumentsTest.java`
- modify `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/broadcast/BroadcasterTest.java`

**Interfaces:**

```java
// BroadcastArguments.Result — Invalid is REMOVED
record Parsed(@NotNull BroadcastArguments arguments) implements Result {}
record BlankContent() implements Result {}
record FlagMissingValue(@NotNull String flag) implements Result {}
record UnrecognisedToken(@NotNull String token) implements Result {}

// Broadcaster gains a leading @NotNull MessageContainer messages constructor parameter;
// BROADCAST_TITLE the static constant is removed, the title read from broadcast.title
// BroadcastCommand gains a leading @NotNull MessageContainer messages constructor parameter
```

`BroadcastArgumentsTest` has 17 cases, several asserting on the rejection sentence. Those become
type assertions; the wording is covered once by `MessageKeysTest`.

- [ ] Rewrite the failing assertions:

```java
@Test
void unrecognisedTokenIsRejectedNamingTheToken() {
    BroadcastArguments.Result result = BroadcastArguments.parse("hello --wat");

    assertEquals("--wat",
            assertInstanceOf(BroadcastArguments.Result.UnrecognisedToken.class, result).token());
}

@Test
void permFlagWithoutValueIsRejectedNamingTheFlag() {
    BroadcastArguments.Result result = BroadcastArguments.parse("hello --perm");

    assertEquals("--perm",
            assertInstanceOf(BroadcastArguments.Result.FlagMissingValue.class, result).flag());
}
```

  and update `BroadcasterTest` to construct with `TestMessages.shipped()`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*Broadcast*"` — expect FAIL: the new
      result records do not exist and `Broadcaster`'s constructor does not take a container.
- [ ] Implement the result split; in `Broadcaster` replace `BROADCAST_TITLE` with
      `messages.messageFor(MessageKeys.BROADCAST_TITLE)`, read per broadcast so a reload takes
      effect; in `BroadcastCommand` map `broadcast.no-audience`, `broadcast.nothing-enabled`,
      `broadcast.blank-content`, `broadcast.flag-missing-value` (`flag`),
      `broadcast.unrecognised-token` (`token`).
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 8: Docs and full verification

**Depends on:** T2–T7 (all six).

**Files:** modify `CLAUDE.md`; modify the spec's Known limitations if Task 1's concurrency check
changed the answer.

- [ ] Add a **Messages** section to `CLAUDE.md` after "Configuration": `messages.yml` →
      `MessageContainer` (the infrastructure class, used unsubclassed, and why — the realty
      divergence), `MessageKeys` as the single source of keys, `MessageKeysTest` guarding both
      directions and *why* (a missing key renders as the key itself), and the two deliberate
      exclusions — `MailNotifier.ARRIVAL_NOTICE`'s identity check and the out-of-scope dialogs,
      renderers and feature modules.
- [ ] Update the "Configuration" section's file list to include `messages.yml`, and the
      `/notifications reload` entry under "Player commands" to say it reloads messages too.
- [ ] Update the test baseline in "Testing gotchas" with the new `:platform:paper-plugin:test`
      count. Count with a `*.xml` glob, **not** `TEST-*.xml` — CLAUDE.md records why.
- [ ] Run `./gradlew :platform:paper-plugin:test :api:test` and record the real numbers. Do not
      re-run the Docker-backed suites (`:core:test`, `:platform:discord-adapter:test`) unless a
      daemon is available; nothing in this plan touches either module.
- [ ] Run `./gradlew build`
- [ ] **Manual (`./gradlew :platform:paper-plugin:runServer`):** edit a key in
      `run/plugins/PlayerNotifications/messages.yml`, run `/notifications reload`, and confirm the
      new wording appears without a restart. Delete a key from the file, reload, and confirm the
      reply prints the key name — the failure mode `MessageKeysTest` exists to keep out of a
      release, confirmed present at runtime.
- [ ] Commit
