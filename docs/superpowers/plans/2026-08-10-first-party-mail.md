# First-party mail Implementation Plan

**Goal:** Retire `platform:essentials-adapter` and replace it with a first-party `mail` notification type that is stored but never delivered — sent with `/mail send`, announced by a "You have new mail!" line, and read from a filtered `/mail` inbox.
**Spec:** `docs/superpowers/specs/2026-08-10-first-party-mail-design.md`

Tasks are ordered so each builds and tests on its own. Task 1 is the persistence change
everything else reads through; Tasks 2–3 are the payload, its renderer and the
never-deliver rule; Task 4 is the command surface and the arrival notice; Task 5 removes
the adapter; Task 6 updates the docs.

---

## Task 1: Data-type-filtered inbox queries

**Files:**
- modify `api/src/main/java/io/github/md5sha256/playernotifications/api/NotificationService.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/NotificationMapper.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/NotificationTargetMapper.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaNotificationMapper.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaNotificationTargetMapper.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/DefaultNotificationService.java`
- test `core/src/test/java/io/github/md5sha256/playernotifications/core/FilteredInboxTest.java` (new)

**Interfaces produced:**

```java
// NotificationService — new overloads; the existing 3/1-arg forms become defaults delegating with null
@NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize, @Nullable String dataType);
int unreadCount(@NotNull UUID playerId, @Nullable String dataType);
void markAllSeen(@NotNull UUID playerId, @Nullable String dataType);
void dismissSeen(@NotNull UUID playerId, @Nullable String dataType);

// NotificationMapper
@NotNull List<InboxNotificationEntity> selectInboxPage(UUID playerId, Instant now, int limit, int offset, @Nullable String dataType);
int countInbox(UUID playerId, Instant now, @Nullable String dataType);
int countUnread(UUID playerId, Instant now, @Nullable String dataType);

// NotificationTargetMapper
int markAllSeen(UUID playerId, Instant seenTime, @Nullable String dataType);
@NotNull List<String> selectSeenKeys(UUID playerId, @Nullable String dataType);
```

- [ ] Write the failing test `core/src/test/java/.../FilteredInboxTest.java`, extending
      `AbstractDatabaseTest` as `InboxReadTest` does. Register two data types (`mail` and
      `test`), enqueue two `mail` notifications and one `test` notification targeting the
      same player, then assert:
      - `service.inbox(player, 1, 10, "mail").entries()` has size 2 and every entry's
        `notifPayloadType()` is `"mail"`;
      - that page's `totalEntries() == 2` and `unreadCount() == 2`;
      - `service.inbox(player, 1, 10, null).totalEntries() == 3`;
      - `service.unreadCount(player, "mail") == 2` and `service.unreadCount(player) == 3`;
      - after `service.markAllSeen(player, "mail")`, `unreadCount(player, "mail") == 0` and
        `unreadCount(player) == 1`;
      - after `service.markAllSeen(player, null)` then `service.dismissSeen(player, "mail")`,
        `inbox(player, 1, 10, null).totalEntries() == 1` and the survivor is the `test` one;
      - paging respects the filter: with `pageSize` 1, `inbox(player, 2, 1, "mail")` returns
        the second mail and `totalPages() == 2`.
- [ ] Run `./gradlew :core:test --tests "*FilteredInboxTest"` — expect FAIL: the four-argument
      `inbox` and the two-argument `unreadCount`/`markAllSeen`/`dismissSeen` do not exist, so
      it will not compile.
- [ ] Implement the read filter. On `NotificationMapper`, add `@Nullable String dataType` as the
      last parameter of `selectInboxPage`, `countInbox` and `countUnread`. In
      `MariaNotificationMapper`, convert those three `@Select`s to `<script>` form, adding to
      each `WHERE`: `<if test="dataType != null">AND n.notifPayloadType = #{dataType}</if>`,
      with `@Param("dataType")` on the new parameter. Leave the
      `ORDER BY n.notifScheduledTime DESC, n.notifPriority DESC, n.notifKey DESC` total order
      intact — it is what stops two notifications sharing a timestamp swapping between page reads.
- [ ] Implement the target-side filter. `markAllSeen` gains
      `<if test="dataType != null">AND EXISTS (SELECT 1 FROM Notification n WHERE n.notifTargetId =
      NotificationTarget.notifTargetId AND n.notifPayloadType = #{dataType})</if>`.
      Replace the filtered path of `dismissSeen` with select-then-delete: add
      `selectSeenKeys(playerId, dataType)` (a `<script>` `SELECT n.notifKey ... WHERE
      t.playerUuid = #{playerId} AND t.seenTime IS NOT NULL
      <if test="dataType != null">AND n.notifPayloadType = #{dataType}</if>`) and have
      `DefaultNotificationService.dismissSeen` call it, then `deleteNotificationTarget(key,
      playerId)` per key. A `DELETE` whose subquery reads `Notification` is refused by MariaDB
      while `trg_delete_targetless_notification` writes it — the same constraint
      `pruneOrphanedTargets` already works around.
- [ ] Implement the `NotificationService` overloads and make the existing `inbox(UUID,int,int)`,
      `unreadCount(UUID)`, `markAllSeen(UUID)` and `dismissSeen(UUID)` `default` methods
      delegating with `null`. In `DefaultNotificationService`, thread `dataType` through, keeping
      the `pageSize` clamp to `1..20` and the `page` clamp against the *filtered* total.
- [ ] Run `./gradlew :core:test --tests "*FilteredInboxTest"` — expect PASS.
- [ ] Run `./gradlew :core:test` — the existing 96 tests plus the new ones, all passing
      (`InboxReadTest` and `InboxDeliveryTest` exercise the unfiltered defaults).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 2: `MailPayload` and `MailRenderer`

**Files:**
- create `api/src/main/java/io/github/md5sha256/playernotifications/api/mail/MailPayload.java`
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailRenderer.java`
- test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailRendererTest.java` (new)

**Interfaces produced:**

```java
public record MailPayload(@NotNull UUID sender, @NotNull String senderName, @NotNull String message) {
    public static final String DATA_TYPE = "mail";
    public static final int MAX_MESSAGE_LENGTH = 256;
}

public final class MailRenderer implements NotificationRenderer<MailPayload> {
    @Override
    public @NotNull RenderableNotification render(@NotNull MailPayload payload, @NotNull UUID target);
}
```

- [ ] Write the failing test `MailRendererTest`, asserting with
      `PlainTextComponentSerializer.plainText()` that
      `new MailRenderer().render(new MailPayload(senderId, "Steve", "hello there"), targetId)`
      has title `"Mail from Steve"` and body `"hello there"`; that a message containing a
      section sign or an `&c` code renders those characters literally rather than as colour; and
      that two different `target` UUIDs render identically (the renderer ignores the target).
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailRendererTest"` — expect FAIL:
      neither class exists.
- [ ] Implement `MailPayload` as the record above, with a compact constructor rejecting a blank
      `senderName` or `message`, and javadoc recording why `senderName` is stored rather than
      looked up at render time (a renderer runs on read, and `getOfflinePlayer` is a blocking
      lookup returning null for an unseen player).
- [ ] Implement `MailRenderer` with the **two-argument** `render(payload, target)` signature —
      `NotificationRenderer` already passes the recipient. Build both components with
      `Component.text(...)`, never MiniMessage or `LegacyComponentSerializer`, since the message
      is player-supplied.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailRendererTest"` — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 3: `MailSender`, registration, and the never-deliver rule

**Files:**
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailSender.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`
- test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailSenderTest.java` (new)
- test `core/src/test/java/io/github/md5sha256/playernotifications/core/MailNotDeliveredTest.java` (new)

**Interfaces produced:**

```java
public final class MailSender {
    public MailSender(@NotNull NotificationService service);
    public @NotNull String send(@NotNull UUID sender, @NotNull String senderName,
                                @NotNull UUID recipient, @NotNull String message);
}
```

- [ ] Write the failing test `MailSenderTest` against a fake `NotificationService` capturing the
      `TypedNotification` passed to `enqueueNotification`, asserting: `notifPayloadType()` equals
      `MailPayload.DATA_TYPE`; `notifExpiryTime()` is `null`; `notifTarget()` holds exactly the
      recipient; `notifKey()` starts with `"mail-"`; `overwriteAllowed` is `false`; the payload's
      three fields round-trip; and that two successive `send` calls produce different keys.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailSenderTest"` — expect FAIL:
      `MailSender` does not exist.
- [ ] Implement `MailSender.send` exactly as spec §3 describes: key `"mail-" + UUID.randomUUID()`,
      `notifScheduledTime` `Instant.now()`, `notifExpiryTime` `null`, single-element
      `NotificationTarget`, priority `0`, `enqueueNotification(n, false)`. No validation here —
      that belongs to the command, so a module calling this does not get an exception thrown
      across a module boundary.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailSenderTest"` — expect PASS.
- [ ] Write the failing test `core/src/test/java/.../MailNotDeliveredTest.java`, extending
      `AbstractDatabaseTest` and modelled on `RenderedDeliveryTest`. Register a recording
      `NotificationSink` under medium `chat`; register a `mail` data type whose payload class has
      a renderer **and** a `(payload, target) -> RETAIN` processor; register a second, ordinary
      renderable data type with no processor. Enqueue one of each for the same player, run
      `NotificationDelivery.deliver(player)`, then assert:
      - the recording sink received exactly one notification, the non-mail one;
      - the mail's target row still has `seenTime == null`;
      - `service.inbox(player, 1, 10, "mail").entries()` still has size 1 with `unread()` true.
      This is the design's central claim, and the regression most easily reintroduced by
      "tidying up" the RETAIN processor.
- [ ] Run `./gradlew :core:test --tests "*MailNotDeliveredTest"` — expect FAIL until the
      processor registration exists in the test's setup; implement it there, then expect PASS.
- [ ] Register in `PlayerNotificationsPlugin.onEnable`, next to the existing
      `TestNotificationRenderer` registration:
      ```java
      service.registerJsonRenderable(MailPayload.DATA_TYPE, MailPayload.class, new MailRenderer());
      // Mail is stored but never delivered: an explicit processor wins dispatch precedence and
      // bypasses preferences and sinks. RETAIN also leaves seenTime unset, so mail stays unread
      // until the player opens it in /mail. See the design doc, "Mail is stored, but never
      // delivered" — do not "simplify" this away.
      service.dataTypeRegistry().registerProcessor(MailPayload.class,
              (payload, target) -> NotificationDisposition.RETAIN);
      ```
- [ ] Add the `mail` category to `platform/paper-plugin/src/main/resources/categories.yml`
      (`label: "Mail"`, `description: "Where you are told that new mail has arrived. The mail
      itself is always read with /mail."`, `types: [mail]`). The checkboxes are meaningful: a
      processor bypasses preferences for the *mail*, but `MailNotifier` (Task 4) routes the
      *arrival notice* by exactly these rows.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 4: The `/mail` command tree and the arrival notice

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/inbox/InboxRouter.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/inbox/InboxQuitListener.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/inbox/InboxDialog.java` (title from the router)
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/JoinDeliveryListener.java`
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailRecipients.java`
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailNotifier.java`
- create `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/command/MailCommand.java`
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/PlayerNotificationsPlugin.java`
- modify `platform/paper-plugin/src/main/resources/paper-plugin.yml`
- test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailRecipientsTest.java` (new)
- test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailNotifierTest.java` (new)
- test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/JoinDeliveryListenerTest.java` (modify)

**Interfaces produced:**

```java
// InboxRouter — two new constructor arguments, threaded into every service call
public InboxRouter(@NotNull Plugin plugin, @NotNull NotificationService service,
                   @NotNull InboxEntryRenderer renderer, int pageSize,
                   @Nullable String dataTypeFilter, @NotNull Component title);

// MailRecipients — the testable half of /mail send's argument handling
public final class MailRecipients {
    public sealed interface Result permits Result.Ok, Result.UnknownPlayer, Result.InvalidMessage {}
    public static @NotNull Result resolve(@NotNull String name, @NotNull String message,
                                          @NotNull Function<String, UUID> resolver);
}

// MailNotifier — routes the verbatim "You have new mail!" notice by the player's mail preferences
public final class MailNotifier {
    public MailNotifier(@NotNull NotificationSinkRegistry sinks,
                        @NotNull NotificationPreferences preferences,
                        @NotNull Logger logger);
    public void notifyArrival(@NotNull UUID recipient);
}
```

- [ ] Write the failing test `MailRecipientsTest` against a map-backed resolver (`name -> UUID`,
      `null` for unknown), asserting: a known name and a normal message give `Ok` with that UUID
      and the trimmed message; an unknown name gives `UnknownPlayer` naming it; a blank or
      whitespace-only message gives `InvalidMessage`; a message of exactly
      `MailPayload.MAX_MESSAGE_LENGTH` characters is `Ok`; one character longer is
      `InvalidMessage` and is **not** truncated.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailRecipientsTest"` — expect FAIL:
      `MailRecipients` does not exist.
- [ ] Implement `MailRecipients.resolve` with exactly those rules. The `Function<String, UUID>`
      seam keeps them testable without a server, the same device
      `TestNotificationRenderer.usingServerNames()` uses.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailRecipientsTest"` — expect PASS.
- [ ] Add `dataTypeFilter` and `title` to `InboxRouter`, passing the filter to every
      `service.inbox` / `unreadCount` / `markAllSeen` / `dismissSeen` call it makes, and the
      title to `InboxDialog`. Existing construction in `PlayerNotificationsPlugin` passes `null`
      and `Component.text("Notifications")`, so its behaviour is unchanged.
- [ ] Change `InboxQuitListener` to hold a `List<InboxRouter>` and drop the quitting player from
      each, then construct a second `InboxRouter` in `PlayerNotificationsPlugin` with filter
      `MailPayload.DATA_TYPE` and title `Component.text("Mail")`, registering the listener with
      both. Separate instances rather than one shared router: the cursor and last-listed maps are
      per-screen state, and `/mail list 2` must not make `/notifications read 1` resolve against
      the mail page.
- [ ] Write the failing test `MailNotifierTest` against a fake `NotificationSinkRegistry` and fake
      `NotificationPreferences`, asserting: a player preferring `chat + discord-dm` has the notice
      delivered to both recording sinks; a player whose only medium is
      `NotificationPreferences.MUTED_MEDIUM` gets nothing; a preferred medium with no registered
      sink is skipped without throwing; a sink that throws a `RuntimeException` does not prevent
      the other sink receiving it; and the delivered `RenderableNotification`'s plain text is the
      verbatim notice, containing no sender name, count or message text.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailNotifierTest"` — expect FAIL:
      `MailNotifier` does not exist.
- [ ] Implement `MailNotifier` as spec "The arrival notice" describes: resolve
      `preferences.preferredMedia(recipient, MailPayload.DATA_TYPE)`, drop `MUTED_MEDIUM`, deliver
      a constant `RenderableNotification` to each medium's sink, catching and logging a throwing
      sink. Do not reuse `RenderingProcessor` — it exists to render a stored payload and report a
      `NotificationDisposition`, and the notice has neither.
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailNotifierTest"` — expect PASS.
- [ ] Extend `JoinDeliveryListenerTest` with a case asserting that a player with unread mail gets
      the mail reminder line and a player with none gets nothing, driving the same package-private
      seam the existing unread-count test uses. Run
      `./gradlew :platform:paper-plugin:test --tests "*JoinDeliveryListenerTest"` — expect FAIL.
- [ ] Add the mail reminder line to `JoinDeliveryListener`, alongside the existing unread-count
      line and **outside** the `deliver-on-join` gate and its delay, driven by
      `service.unreadCount(playerId, MailPayload.DATA_TYPE)` and sending nothing when the count is
      zero. Run the same command — expect PASS.
- [ ] Implement `MailCommand`, mirroring `NotificationsCommand`'s structure: a Brigadier node for
      `mail` whose bare execute opens the mail router's dialog, plus `send`, `list [page]`,
      `read <n>`, `dismiss <n>` and `clear`. Every branch is player-only and dispatches off the
      main thread via `runTaskAsynchronously`. `send` resolves its recipient on that async thread
      — online player by name first, else `Bukkit.getOfflinePlayer(name)` accepted only when
      `hasPlayedBefore()`, else `UnknownPlayer` — then calls `MailSender.send`, replies to the
      sender, and calls `MailNotifier.notifyArrival(recipient)` unconditionally (not only when the
      recipient is online: Discord DM reaches them either way, and the notifier already resolves
      what can reach them). Suggest online player names on the recipient argument.
- [ ] Register the node from the existing `LifecycleEvents.COMMANDS` handler in
      `PlayerNotificationsPlugin.registerCommands()`.
- [ ] Declare `playernotifications.command.mail` (`default: true`) and
      `playernotifications.command.mail.send` (`default: true`) in `paper-plugin.yml`, gating the
      `send` node with the latter on top of the root's `requires`.
- [ ] Run `./gradlew :platform:paper-plugin:test` — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

**Manual verification** (needs a live server — `./gradlew :platform:paper-plugin:runServer`; this
task's Brigadier wiring, dialogs and permission gates cannot be unit tested, the same exception
`CLAUDE.md` records for `NotificationsCommand` and the preference dialogs):

1. `/mail send <self> hello` — reply confirms the send, and the notice arrives immediately reading
   exactly "You have new mail!", with no sender, count or preview.
2. **The mail content itself never appears in chat** — only the notice. This is the whole design.
3. With Discord DM linked and selected for Mail in `/notifications preferences types`, send mail —
   the notice arrives as a Discord DM as well as in chat. Deselect chat and it arrives by DM only.
4. Mute Mail, send mail — no notice anywhere, and the mail is still unread in `/mail`.
3. `/mail` — the dialog opens titled "Mail" and shows the mail, bold (unread).
4. `/notifications` — the same mail appears there too, since the unfiltered inbox is unfiltered.
5. `/mail list`, `/mail read 1` — the chat fallback renders "Mail from …" with the full message,
   and the entry becomes read; `/mail` now shows it unbolded.
6. `/mail dismiss 1` — gone from `/mail` **and** from `/notifications`.
7. `/notifications test`, then `/mail clear` — the mail goes, the test notification stays.
8. `/mail send <a-name-nobody-has-used> hi` — rejected as unknown, nothing enqueued.
9. `/mail send <self> <300 characters>` — rejected naming the 256 limit, not truncated.
10. Send mail, quit without reading, rejoin — the join line reports unread mail and points at
    `/mail`; the content is still not pushed.
11. Revoke `playernotifications.command.mail.send` — `/mail` still works, `/mail send` is hidden.
12. Send to an offline player, have them join — the notice arrives and the mail is in their
    `/mail`.

---

## Task 5: Delete the Essentials adapter

**Files:**
- delete `platform/essentials-adapter/` (whole directory)
- modify `settings.gradle.kts`
- modify `platform/paper-plugin/build.gradle.kts`
- modify `platform/paper-plugin/src/main/resources/paper-plugin.yml`

- [ ] Delete the directory: `git rm -r platform/essentials-adapter`
- [ ] Remove `include("platform:essentials-adapter")` from `settings.gradle.kts`.
- [ ] Remove the
      `featureModules(project(path = ":platform:essentials-adapter", configuration = "moduleJar"))`
      line from `platform/paper-plugin/build.gradle.kts`.
- [ ] Remove the soft `Essentials` entry from `paper-plugin.yml`'s `dependencies: server:` block —
      it exists only for the adapter, and carries the same `join-classpath: true` classloader
      exposure `CLAUDE.md` documents for DiscordSRV.
- [ ] Run `./gradlew build` — expect BUILD SUCCESSFUL with no `essentials-adapter` task in the
      output.
- [ ] Run `./gradlew test` — expect the full suite passing.
- [ ] Confirm nothing references it:
      `grep -ri "essentials" --include=*.java --include=*.kts --include=*.yml . | grep -v build/`
      returns nothing outside `docs/`.
- [ ] Commit

---

## Task 6: Update `CLAUDE.md`

**Files:** modify `CLAUDE.md`

Every one of these is a claim the code now contradicts:

- [ ] Remove `platform:essentials-adapter` from the *Overview*, *Module architecture*, the build
      commands (`:platform:essentials-adapter:jar`), *Testing gotchas* ("`platform:essentials-adapter`
      has no tests"), and every mention of the `essentials-mail` medium in *Rendering & delivery
      media*, *Player commands* and *Current state*.
- [ ] Drop the *Current state* bullet "Persisted `essentials-mail` rows will not deliver at all"
      and replace it with one noting that stored `essentials-mail` **preference** rows are inert.
- [ ] Add a *Mail* section covering: mail as a `dataType` rather than a medium; that it is stored
      but **never delivered**, via a RETAIN processor exploiting the dispatch-precedence rule, and
      why (`RETAIN` also leaves `seenTime` unset, so unread counts stay honest); that the verbatim
      "You have new mail!" notice is routed through sinks by the player's `mail` preference rows
      while the mail itself never is, so a `mail` category *is* shipped and its checkboxes select
      where the notice goes; `MailPayload` / `MailRenderer` / `MailSender` / `MailRecipients` /
      `MailNotifier`, and why `MailNotifier` does not reuse `RenderingProcessor`; null expiry and
      why; the second `InboxRouter` instance and why two rather than one; the `/mail` command table
      and the two-permission split.
- [ ] Update the *known quirk* bullet under *Player commands* — an explicitly registered processor
      bypassing preferences now has an in-tree instance again (mail), where it is deliberate rather
      than a wart, and where the preference rows it bypasses are reused to route the notice.
- [ ] Update *Notification inbox* for the data-type-filtered `inbox` / `unreadCount` /
      `markAllSeen` / `dismissSeen` overloads and the select-then-delete shape of filtered
      `dismissSeen`.
- [ ] Update the test baseline counts in *Testing gotchas* with the real numbers from
      `./gradlew test` (glob `*.xml`, not `TEST-*.xml`).
- [ ] Add the unrun manual checklist (Task 4 above) to *Current state*, alongside the existing
      unrun checklists.
- [ ] Run `./gradlew build`
- [ ] Commit
