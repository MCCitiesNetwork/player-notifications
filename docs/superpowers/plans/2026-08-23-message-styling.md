# Uniform message styling — implementation plan

**Goal:** Give every player-facing chat message one visual language borrowed from `realty`, and pull
the three groups of text that escape `messages.yml` back into it.
**Spec:** `docs/superpowers/specs/2026-08-23-message-styling-design.md`

## Task 1: The pager

**Files:** create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/inbox/InboxChatFooter.java`;
create `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/inbox/InboxChatFooterTest.java`;
modify `platform/paper-plugin/src/main/resources/messages.yml` (add `inbox.footer`, `inbox.footer-previous`, `inbox.footer-next`);
modify `…/paper/localisation/MessageKeys.java` (three constants)

**Interfaces:**

```java
public final class InboxChatFooter {
    public static @Nullable Component build(@NotNull MessageContainer messages,
                                            @NotNull String commandLabel,
                                            int page, int totalPages);
}
```

- [ ] Write the failing test `InboxChatFooterTest` with these cases:
      `singlePageHasNoFooter` — `build(shipped(), "notifications", 1, 1)` is null;
      `middlePageLinksBothWays` — plain text of `build(…, "notifications", 2, 3)` contains `«`, `»`
      and `Page 2 of 3`, and the click events are `runCommand` of `/notifications list 1` and
      `/notifications list 3`;
      `firstPagePreviousIsInert` — `build(…, "notifications", 1, 3)` has no click event on the `«`
      arrow but still renders it;
      `lastPageNextIsInert` — `build(…, "notifications", 3, 3)` has no click event on the `»` arrow;
      `commandLabelIsHonoured` — `build(…, "mail", 2, 3)` emits `/mail list 1` and `/mail list 3`.
      Click events are read by walking the built component's children for a
      `ClickEvent.Action.RUN_COMMAND`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*InboxChatFooterTest"` — expect FAIL:
      `InboxChatFooter` does not exist.
- [ ] Implement `InboxChatFooter`: return `null` when `totalPages <= 1`; otherwise render
      `MessageKeys.INBOX_FOOTER` with `markup("previous", …)`, `markup("next", …)` and
      `value("page")` / `value("total-pages")`. Each arrow is `INBOX_FOOTER_PREVIOUS` /
      `INBOX_FOOTER_NEXT` rendered from the container, given a
      `ClickEvent.runCommand("/" + commandLabel + " list " + n)` only when that page exists, and
      `.color(NamedTextColor.DARK_GRAY)` when it does not.
- [ ] Add the three keys to `messages.yml` and the three constants to `MessageKeys`.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*InboxChatFooterTest" --tests "*MessageKeysTest"`
      — expect PASS.
- [ ] Commit.

## Task 2: The listing emits the footer, and `read` gets a frame

**Files:** modify `…/paper/inbox/InboxRouter.java` (`listInChat` ~line 315, `readInChat` ~line 352);
modify `messages.yml` (add `inbox.read-title`, `inbox.read-body`);
modify `…/paper/localisation/MessageKeys.java` (two constants)

**Interfaces:** none new — both are private call-site changes inside `InboxRouter`.

- [ ] Add `inbox.read-title` and `inbox.read-body` to `messages.yml` and their constants to
      `MessageKeys`. `MessageKeysTest` is the failing test: run
      `./gradlew :platform:paper-plugin:test --tests "*MessageKeysTest"` after adding the constants
      but before the YAML lines — expect FAIL naming the missing key.
- [ ] Add the YAML lines. Run the same command — expect PASS.
- [ ] In `listInChat`, after the usage line, send `InboxChatFooter.build(this.messages,
      this.commandLabel, read.page(), read.totalPages())` when it is non-null.
- [ ] In `readInChat`, replace
      `player.sendMessage(rendered.title().colorIfAbsent(NamedTextColor.GOLD))` and the bare body
      send with `messageFor(INBOX_READ_TITLE, markup("title", rendered.title()))` and
      `messageFor(INBOX_READ_BODY, markup("body", rendered.body()))`. Drop the now-unused
      `NamedTextColor` import if nothing else in the file uses it.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect PASS (206 + the new footer cases).
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 3: The preference replies join the container

**Files:** modify `…/paper/preferences/PreferenceDialogRouter.java` (constructor ~line 42,
`applyChanges` ~line 170, `discard` ~line 194, `muteImmediately` ~line 206, `unmuteImmediately`
~line 214, `setMutedImmediately` ~line 220); modify `…/paper/PlayerNotificationsPlugin.java` (the
single construction site); modify `messages.yml` (new `preferences:` section);
modify `…/paper/localisation/MessageKeys.java` (eight constants)

**Interfaces:**

```java
public PreferenceDialogRouter(@NotNull MessageContainer messages,
                              @NotNull Plugin plugin,
                              @NotNull NotificationSinkRegistry sinkRegistry,
                              @NotNull NotificationCategories categories,
                              @NotNull NotificationDataTypeRegistry dataTypeRegistry,
                              @NotNull TypeNames typeNames,
                              @NotNull DatabaseNotificationPreferences preferences)
```

- [ ] Add the eight constants (`PREFERENCES_SAVED`, `PREFERENCES_SAVE_FAILED`,
      `PREFERENCES_DISCARDED`, `PREFERENCES_MUTED`, `PREFERENCES_UNMUTED`,
      `PREFERENCES_MUTE_FAILED`, `PREFERENCES_UNMUTE_FAILED`, `PREFERENCES_SESSION_DISCARDED`) and
      run `./gradlew :platform:paper-plugin:test --tests "*MessageKeysTest"` — expect FAIL naming
      each missing key.
- [ ] Add the `preferences:` section to `messages.yml`. Run the same command — expect PASS.
- [ ] Add the `MessageContainer` parameter to `PreferenceDialogRouter` and pass `this.messages` at
      the construction site in `PlayerNotificationsPlugin`.
- [ ] Replace the seven `Component.text(...)` sends with container calls. `setMutedImmediately`
      takes two key names (success, failure) instead of a `String successMessage`, so the
      `"Could not " + (muted ? … )` concatenation disappears; `muteImmediately` and
      `unmuteImmediately` each pass their own pair.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect PASS.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 4: The restyle itself

**Files:** modify `platform/paper-plugin/src/main/resources/messages.yml` (every value and the
comment header)

**Interfaces:** none — no key is added or renamed in this task.

Constraints the restyle must not break, each already asserted by an existing test:

| Must hold | Test |
|---|---|
| `test.muted` contains `muted` and `/notifications unmute` | `TestNotificationSenderTest` |
| `test.silenced` contains `silenced` and `/notifications preferences` | `TestNotificationSenderTest` |
| `test.no-media` contains `no delivery methods` and **not** `silenced` | `TestNotificationSenderTest` |
| `test.no-sink` contains `skipped` | `TestNotificationSenderTest` |
| `test.separator` is plain-text `, ` | `TestNotificationSenderTest` (`Chat, Dialog`) |
| `join.unread-one` contains `1 unread notification.` | `JoinDeliveryListenerTest` |
| `join.unread-many` contains `<count>` and `/notifications` | `JoinDeliveryListenerTest` |
| `join.unread-mail` contains `/mail` | `JoinDeliveryListenerTest` |
| `mail.row.*` plain text stays `#1 [14:03] [Steve] …` | `MailChatRowTest` |
| `broadcast.title` is plain-text `Broadcast` | `BroadcasterTest` |

- [ ] Set `prefix` to `<newline><gradient:#00E0C0:#00A8FF><b>Notifications</b> <dark_grey>»<reset>`.
- [ ] Apply the prefix to exactly the keys the spec's "prefix rule" lists as prefixed, and to no
      others.
- [ ] Recolour every value to the spec's vocabulary table. Change `inbox.title` to `Inbox` and
      `inbox.row.title-only-*` to the `#<entry>` form; leave every other key's *text* alone except
      where the vocabulary requires a structural character.
- [ ] Rewrite the file's comment header to carry the vocabulary table and the prefix rule, so an
      operator editing one line can match the rest.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect PASS, all of the table above included.
- [ ] Run `./gradlew build`.
- [ ] Commit.

## Task 5: Documentation

**Files:** modify `CLAUDE.md` (the "Messages" section); modify
`docs/superpowers/specs/2026-08-23-message-styling-design.md` (status → implemented)

- [ ] Update `CLAUDE.md`'s "Messages" section: the vocabulary and the prefix rule, `InboxChatFooter`
      as the pager's home and why the click stays in Java, `PreferenceDialogRouter` no longer in the
      hardcoded list, and the refreshed test baseline from an actual run.
- [ ] Run `./gradlew test` with Docker available and record the real per-module counts.
- [ ] Commit.

## Manual verification (needs `:platform:paper-plugin:runServer`)

Not automatable — every item needs a live `Player`.

1. `/mail send <player> hi` — one blank line, then `Notifications » Mail sent to <player>.`
2. `/notifications list` — prefixed header, `#1`-style rows, usage hint, no footer on one page.
3. Enqueue enough to fill two pages, `/notifications list` — footer reads `« Page 1 of 2 »` with a
   dimmed `«`; clicking `»` lists page 2 and the `»` there is the dimmed one.
4. `/mail list 2` — the footer's arrows run `/mail list …`, never `/notifications list …`.
5. `/notifications read 1` — prefixed title line, then the body under it.
6. `/notifications preferences`, tick something, Apply — `Notifications » Notification preferences saved.`
7. Same, then Discard — the discarded message, prefixed.
8. `/notifications mute`, then `/notifications unmute` — both prefixed and worded apart.
9. `/notifications preferences`, stage a change, then `/notifications mute` — the reply carries the
   unprefixed `session-discarded` suffix on the same line.
10. Edit `prefix` in `messages.yml` to remove `<newline>`, `/notifications reload` — replies tighten
    up with no other change.
