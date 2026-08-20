# Discord slash commands Implementation Plan

**Goal:** Give a linked Discord user the mail, inbox and preference surface they have in game, entirely inside `platform:discord-adapter`.
**Spec:** `docs/superpowers/specs/2026-08-20-discord-slash-commands-design.md`

All new production code lands in `platform/discord-adapter/src/main/java/io/github/md5sha256/playernotifications/discord/command/`, tests in `platform/discord-adapter/src/test/java/io/github/md5sha256/playernotifications/discord/command/`. Every test command below is a variant of:

```
./gradlew :platform:discord-adapter:test --tests "io.github.md5sha256.playernotifications.discord.command.<Class>"
```

That module's suite as a whole needs Docker (its schema tests use Testcontainers), but **every test in this plan is hermetic** — filter to the named class and no daemon is needed.

## What changed while executing this plan

Recorded here rather than silently: the plan below is as written, these are the places the code
diverged from it.

- **`InboxView` takes an `IntSupplier` page size, not an `int`**, and Task 12 adds
  `PlayerNotificationsPlugin#inboxPageSize()` to feed it. `settings.yml`'s `inbox-page-size` was
  reachable only by the two `InboxRouter`s, and a size captured at construction would have left the
  Discord surface paging differently from every in-game screen after a reload. This is the plan's only
  host change; the spec's "no host change is required" was wrong and has been corrected there too.
- **`PreferenceView` has no `Clock` parameter.** `PreferenceSessionManager` stamps and expires sessions
  with `Instant.now()`, so an injected clock could only disagree with the thing deciding whether a
  session is alive — a fixed test clock made every session instantly expired. The expiry test therefore
  drops the session explicitly instead of advancing a clock.
- **`PreferenceView#setMuted` takes the displayed `dataType`.** The mute button shares a screen with the
  type select, so the row on show has to survive pressing it.
- **`InboxReplies` was extracted** (with its own test) rather than duplicating the out-of-range wording
  in both command listeners.
- **No *Reply* button on a read mail.** `InboxView.Row` carries the rendered title, not the sender's
  name, so wiring it means either parsing a name out of rendered text or widening the row for one
  button. Deferred; the compose modal is the only modal built.

## Parallelisation

Waves, not a chain. Every task inside a wave touches a disjoint file set and depends only on **interfaces declared in an earlier wave's task header**, which are reproduced verbatim below so a task can be written against them before its dependency's body exists.

| Wave | Tasks | May run concurrently |
|---|---|---|
| A | 1, 2, 3 | yes — disjoint files |
| B | 4, 5, 6 | yes — each depends only on Wave A signatures |
| C | 7, 8 | yes — depend on Wave B record shapes only |
| D | 9, 10, 11 | yes — one listener class each |
| E | 12 | no — wires everything, single file |
| F | 13 | no — docs, after everything lands |

Task 12 is the only one that edits `DiscordModule`, so nothing in A–D contends for it. Task 1 is the only one that edits `LinkSlashCommandListener` and `DiscordSettings`.

---

## Wave A

### Task 1: Centralise slash-command registration, add `commands-enabled`

**Why first:** `LinkSlashCommandListener.onReady` calls `updateCommands()`, which replaces the *entire* global command set. Any second registering listener silently deletes `/link`. Nothing else in this plan is safe to ship before registration has one owner.

**Files:**
- create `command/SlashCommandRegistrar.java`
- modify `LinkSlashCommandListener.java` (delete `onReady`, keep `COMMAND_NAME`, `CODE_OPTION` and `onSlashCommandInteraction`)
- modify `DiscordSettings.java` (new `commands-enabled` component)
- modify `src/main/resources/discord.yml` (new key + comment)
- modify `DiscordModule.java` (construct the registrar, pass it as an event listener) — the minimal wiring only; Task 12 adds the rest
- test `DiscordSettingsTest.java` (modify), `command/SlashCommandRegistrarTest.java` (create)

**Interfaces this task produces:**

```java
public final class SlashCommandRegistrar extends ListenerAdapter {
    public SlashCommandRegistrar(boolean commandsEnabled, boolean linkingEnabled, @NotNull Logger logger);
    /** The command set this registrar would register; package-visible for test, no JDA connection needed. */
    static @NotNull List<CommandData> commandData(boolean commandsEnabled, boolean linkingEnabled);
    @Override public void onReady(@NotNull ReadyEvent event);   // one updateCommands() call
}
```

`DiscordSettings` gains `@Setting("commands-enabled") @Nullable Boolean commandsEnabled` plus `public boolean commandsEnabled()` returning `true` when null — a boxed field, so an absent key is distinguishable from an explicit `false`, and the accessor supplies the default the same way `resolvedMessageFormat()` does.

- [ ] Write the failing test `SlashCommandRegistrarTest`:
      `commandData(true, true)` contains exactly the names `link`, `mail`, `notifications`;
      `commandData(false, true)` contains exactly `link`;
      `commandData(true, false)` contains `mail` and `notifications` but not `link`;
      every returned `CommandData` has contexts `BOT_DM` and `GUILD`;
      `/mail` has subcommands `send`, `compose`, `list`, `read`, `dismiss`, `clear`;
      `/notifications` has `list`, `read`, `dismiss`, `clear`, `prefs`, `mute`, `unmute`;
      `/mail send` has a required `player` and a required `message` option; `/mail read` a required `entry` and an optional `page`.
- [ ] Add to `DiscordSettingsTest`: a `discord.yml` node without `commands-enabled` deserialises to `commandsEnabled() == true`; with `commands-enabled: false` to `false`.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*SlashCommandRegistrarTest" --tests "*DiscordSettingsTest"` — expect FAIL: `SlashCommandRegistrar` does not exist and `DiscordSettings` has no such component.
- [ ] Implement `SlashCommandRegistrar`. `commandData` builds `Commands.slash(...)` for the three roots; `/link`'s definition moves across verbatim from `LinkSlashCommandListener.onReady`, including its description and the `DiscordLinkFlow.LINK_COMMAND` reference in the option description. `onReady` makes exactly one `event.getJDA().updateCommands().addCommands(commandData(...)).queue(...)`, logging success at `info` and failure at `WARNING` with the same wording the old code used.
- [ ] Delete `LinkSlashCommandListener.onReady` and its now-unused imports; leave a class-level javadoc line saying registration lives in `SlashCommandRegistrar` and why.
- [ ] Add `commands-enabled: true` to the bundled `discord.yml` with a comment naming what `false` withholds.
- [ ] In `DiscordModule.initialize`, append `new SlashCommandRegistrar(settings.commandsEnabled(), settings.usesEmbeddedProvider(), logger)` to `eventListeners` — unconditionally, since it registers `/link` regardless.
- [ ] Run the same test command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

### Task 2: `ComponentIds`

**Files:** create `command/ComponentIds.java`; test `command/ComponentIdsTest.java`

**Interfaces this task produces:**

```java
public final class ComponentIds {
    public static final String SURFACE_INBOX = "inbox";
    public static final String SURFACE_PREFS = "prefs";
    /** Encodes "pn|surface|action|arg0|arg1…", at most 100 characters (Discord's custom-id limit). */
    public static @NotNull String encode(@NotNull String surface, @NotNull String action, @NotNull String... args);
    /** Empty when the id is not ours or is malformed. */
    public static @NotNull Optional<Parsed> parse(@NotNull String customId);
    public record Parsed(@NotNull String surface, @NotNull String action, @NotNull List<String> args) {
        public @NotNull Optional<String> arg(int index);
        public @NotNull OptionalInt intArg(int index);
    }
}
```

- [ ] Write the failing test `ComponentIdsTest`: round trip with zero, one and three args; `parse` of an id from another plugin returns empty; `parse` of `"pn|inbox"` (no action) returns empty; `intArg` of a non-numeric arg is empty; an arg containing `|` is rejected by `encode` with `IllegalArgumentException` (notification keys are `mail-<uuid>` and registry data types, none of which contain the delimiter, so rejecting is safe and cheaper than escaping); `encode` throws when the result would exceed 100 characters.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*ComponentIdsTest"` — expect FAIL: class does not exist.
- [ ] Implement as above, `String.join`/`split` on `|` with `-1` limit so trailing empty args survive.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

### Task 3: Reverse account lookup

**Files:**
- modify `DiscordAccountProvider.java` (new `default playerFor`)
- modify `EmbeddedDiscordAccountProvider.java`, `DiscordSrvAccountProvider.java`, `ChainedDiscordAccountProvider.java`
- create `command/DiscordUserResolver.java`
- test `ChainedDiscordAccountProviderTest.java` (modify), `command/DiscordUserResolverTest.java` (create)

**Interfaces this task produces:**

```java
// DiscordAccountProvider
default @NotNull Optional<UUID> playerFor(long discordId) { return Optional.empty(); }

public final class DiscordUserResolver {
    public DiscordUserResolver(@NotNull DiscordAccountProvider accounts);
    /** The player linked to this Discord id, or empty if none is. Blocking; call off the main thread. */
    public @NotNull Optional<UUID> resolve(long discordId);
    /** The reply an unlinked user gets, naming /notifications link discord. */
    public static final String NOT_LINKED_MESSAGE;
}
```

- [ ] Write the failing tests. In `ChainedDiscordAccountProviderTest`, mirroring its existing `discordIdFor` cases: first provider answering wins; an unavailable provider is not queried; a throwing provider is logged and the chain continues; nothing linked returns empty. In `DiscordUserResolverTest`: a linked id resolves; an unlinked id is empty; a provider throwing is contained and returns empty rather than propagating into an interaction.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*ChainedDiscordAccountProviderTest" --tests "*DiscordUserResolverTest"` — expect FAIL: no `playerFor`.
- [ ] Implement: the `default` on the interface; `EmbeddedDiscordAccountProvider.playerFor` delegating to `DiscordAccountLinkStore.playerFor`; `DiscordSrvAccountProvider.playerFor` going through DiscordSRV's `AccountLinkManager` reverse lookup with the same reflection-free, availability-guarded shape its `discordIdFor` already uses; `ChainedDiscordAccountProvider.playerFor` copying its own `discordIdFor` loop exactly.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Wave B — depends only on the signatures declared in Wave A

### Task 4: `InboxView`

**Files:** create `command/InboxView.java`; test `command/InboxViewTest.java`, `command/FakeNotificationService.java`

**Interfaces this task produces:**

```java
public final class InboxView {
    public InboxView(@NotNull NotificationService service, @NotNull InboxEntryRenderer renderer,
                     @Nullable String dataTypeFilter, @NotNull String title, int pageSize);
    public @NotNull Page page(@NotNull UUID player, int page);
    public @NotNull ReadResult read(@NotNull UUID player, int page, int entry);
    public @NotNull ReadResult readByKey(@NotNull UUID player, @NotNull String notifKey);
    public @NotNull ActionResult dismiss(@NotNull UUID player, int page, int entry);
    public @NotNull ActionResult dismissByKey(@NotNull UUID player, @NotNull String notifKey);
    public @NotNull ActionResult clear(@NotNull UUID player);

    public record Page(@NotNull String title, @NotNull List<Row> rows, int page, int totalPages,
                       int totalEntries, int unreadCount) { public boolean isEmpty(); }
    public record Row(int entry, @NotNull String notifKey, boolean unread,
                      @NotNull String title, @NotNull String body) {}
    public sealed interface ReadResult {
        record Ok(@NotNull Row row) implements ReadResult {}
        record OutOfRange(int entry, int rowCount) implements ReadResult {}
    }
    public sealed interface ActionResult {
        record Ok(@NotNull String message) implements ActionResult {}
        record OutOfRange(int entry, int rowCount) implements ActionResult {}
    }
}
```

`Row.title`/`body` are already Discord markdown: `InboxEntryRenderer.render` then `DiscordMarkdownSerializer.serialize` on each half. `entry` is 1-based within the page.

- [ ] Write the failing test `InboxViewTest` over `FakeNotificationService` (an in-memory `NotificationService` recording `markSeen`/`deleteNotificationTarget`/`markAllSeen`/`dismissSeen` calls and their `dataType` argument): a page of three renders three rows numbered 1–3; `unread()` maps to `Row.unread`; a filtered view passes `"mail"` on every service call and an unfiltered one passes `null`; `read(player, 1, 2)` returns `Ok` and calls `markSeen` with that row's key; `read(player, 1, 9)` returns `OutOfRange(9, 3)` and calls nothing; `dismiss` calls `deleteNotificationTarget`; `clear` calls `markAllSeen` then `dismissSeen` with the view's filter; an empty inbox yields `Page.isEmpty()` with `totalPages == 1`; a payload with no registered renderer yields the placeholder text rather than throwing.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*InboxViewTest"` — expect FAIL: class does not exist.
- [ ] Implement. No caching and no cursor: every call re-queries, which is what makes the surface stateless.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

### Task 5: `DiscordMailService`

**Files:** create `command/DiscordMailService.java`; test `command/DiscordMailServiceTest.java` (reuses `FakeNotificationService` from Task 4 — if Task 4 has not landed, write the send test against a hand-rolled recording `NotificationService` and delete it in Task 12's cleanup; the two tasks otherwise do not interact)

**Interfaces this task produces:**

```java
public final class DiscordMailService {
    public DiscordMailService(@NotNull MailSender sender, @NotNull MailNotifier notifier,
                              @NotNull Function<String, UUID> recipientResolver,
                              @NotNull Function<UUID, String> senderNames, @NotNull Logger logger);
    /** Resolves, escapes, enqueues and fires the arrival notice. Blocking; call off the main thread. */
    public @NotNull SendResult send(@NotNull UUID sender, @NotNull String recipientName, @NotNull String message);
    public sealed interface SendResult {
        record Ok(@NotNull String recipientName) implements SendResult {}
        record Rejected(@NotNull String reason) implements SendResult {}
        record Failed(@NotNull String reason) implements SendResult {}
    }
}
```

The message is escaped with `MiniMessage.miniMessage().escapeTags(...)` passed as `MailRecipients.resolve`'s formatter, so Discord mail can only ever render as the literal text it was — the EssentialsX converter's rule, for the same reason.

- [ ] Write the failing test `DiscordMailServiceTest`: an unknown recipient name gives `Rejected` naming the name; a blank message gives `Rejected`; a 257-character message gives `Rejected` naming the 256 limit and enqueues nothing; a message of `"<red>hello"` is stored with the tag escaped and still enqueues; `"<click:run_command:/op me>x"` likewise; a successful send returns `Ok`, enqueues one `mail` notification whose payload sender is the caller's UUID and whose sender name comes from `senderNames`, and calls `MailNotifier.notifyArrival` exactly once with the recipient; an enqueue throwing gives `Failed` and does **not** fire the notice.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*DiscordMailServiceTest"` — expect FAIL: class does not exist.
- [ ] Implement over `MailRecipients.resolve(name, message, recipientResolver, MiniMessage.miniMessage()::escapeTags)`, mapping `Result.UnknownPlayer`/`InvalidMessage` to `Rejected` and `Ok` to `MailSender.send` + `MailNotifier.notifyArrival`.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

### Task 6: `PreferenceView`

**Files:** create `command/PreferenceView.java`; test `command/PreferenceViewTest.java`

**Interfaces this task produces:**

```java
public final class PreferenceView {
    public PreferenceView(@NotNull DatabaseNotificationPreferences preferences,
                          @NotNull NotificationSinkRegistry sinks,
                          @NotNull Supplier<Set<String>> knownDataTypes,
                          @NotNull PreferenceSessionManager sessions, @NotNull Clock clock);
    public @NotNull State open(@NotNull UUID player, @Nullable String selectedDataType);
    public @NotNull State selectDataType(@NotNull UUID player, @NotNull String dataType);
    public @NotNull State setMedia(@NotNull UUID player, @NotNull String dataType, @NotNull Set<String> media);
    public @NotNull State setMuted(@NotNull UUID player, boolean muted);
    public @NotNull String apply(@NotNull UUID player);     // the reply text
    public @NotNull String discard(@NotNull UUID player);
    public void muteImmediately(@NotNull UUID player);
    public void unmuteImmediately(@NotNull UUID player);

    public record State(@NotNull String selectedDataType, @NotNull List<Choice> dataTypes,
                        @NotNull List<Choice> media, @NotNull Set<String> selectedMedia,
                        boolean muted, int pendingChanges, boolean expired) {}
    public record Choice(@NotNull String key, @NotNull String label, boolean selected) {}
}
```

`media` lists every `sinks.registeredMedia()` labelled by `sinks.displayName(key)`, plus a synthetic "Mute this type" choice keyed `NotificationPreferences.MUTED_MEDIUM`. An empty selection stages `{MUTED_MEDIUM}`, matching the in-game editors.

- [ ] Write the failing test `PreferenceViewTest` over a fake `DatabaseNotificationPreferences` (subclass, overriding the read/write methods) and a real `PreferenceSessionManager`: `open` on a player with no rows selects the first data type and reports `pendingChanges == 0`; `setMedia` stages and increments `pendingChanges`; `setMedia` with an empty set stages `{none}`; `setMuted(true)` sets `State.muted` and counts as a pending change; `apply` calls `applyChanges(player, stagedMedia, Set.of(), stagedMute)` once and reports the count applied; `discard` drops the session and reports it; a second `open` after `apply` reports zero pending; `muteImmediately`/`unmuteImmediately` call `mute`/`unmute` and drop any open session; an expired session (advance the clock past `PreferenceSessionManager.IDLE_TIMEOUT`) yields `State.expired` and starts fresh; more than 25 data types truncates the choice list to 25 and logs a warning.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*PreferenceViewTest"` — expect FAIL: class does not exist.
- [ ] Implement.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Wave C — depends on the Wave B record shapes only

### Task 7: `InboxMessageFactory`

**Files:** create `command/InboxMessageFactory.java`; test `command/InboxMessageFactoryTest.java`

**Interfaces this task produces:**

```java
public final class InboxMessageFactory {
    public InboxMessageFactory(int embedColor);
    public @NotNull MessageCreateData listing(@NotNull InboxView.Page page, @NotNull String surfacePrefix);
    public @NotNull MessageCreateData detail(@NotNull InboxView.Row row, @NotNull String surfacePrefix);
    public static final String EXPIRED_MESSAGE;   // "This message has expired; run the command again."
}
```

`surfacePrefix` is the `ComponentIds` surface (`"inbox"` for `/notifications`, `"inbox-mail"` for `/mail`) so a click on a mail listing cannot be answered by the notification view. Buttons: Previous / Next carrying the target page, a row select carrying each row's notif key, a Dismiss button on the detail, a Reply button on a `mail` detail.

- [ ] Write the failing test `InboxMessageFactoryTest` (JDA builders need no connection, exactly as `DiscordMessageFactoryTest` relies on): a three-row page produces one embed and a select with three options; the embed title carries the page's title and `page/totalPages`; unread rows are marked distinctly from read ones; Previous is disabled on page 1 and Next on the last page; a 6000-character body is truncated below Discord's 4096 description limit rather than throwing; an empty page produces a message with **no** select and no row buttons (an empty select is rejected by Discord the same way an empty `multiAction` dialog is by vanilla — see the inbox design doc); every custom id round-trips through `ComponentIds.parse`.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*InboxMessageFactoryTest"` — expect FAIL: class does not exist.
- [ ] Implement, truncating before every JDA builder call.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

### Task 8: `PreferenceMessageFactory`

**Files:** create `command/PreferenceMessageFactory.java`; test `command/PreferenceMessageFactoryTest.java`

**Interfaces this task produces:**

```java
public final class PreferenceMessageFactory {
    public PreferenceMessageFactory(int embedColor);
    public @NotNull MessageCreateData preferences(@NotNull PreferenceView.State state);
}
```

- [ ] Write the failing test `PreferenceMessageFactoryTest`: the message carries a data-type string select with the state's choices and the selected one marked default; a media multi-select with `minValues 0` and `maxValues` equal to its option count, pre-selecting `state.selectedMedia`; Apply, Discard and a mute button whose label flips with `state.muted`; the header names `pendingChanges` when non-zero and does not when zero; an `expired` state produces the expiry message and no components; option counts above 25 are truncated rather than throwing; every custom id round-trips through `ComponentIds.parse`.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*PreferenceMessageFactoryTest"` — expect FAIL: class does not exist.
- [ ] Implement.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Wave D — JDA adapters, one class each, no shared files

Each of these is a logic-free adapter and, like `LinkSlashCommandListener`, **cannot be unit tested**. Each task's verification is a compile plus the manual steps recorded in Task 13's checklist; none of them may contain a decision that could have lived in Wave B. Reviewer's rule: a `switch` mapping a result record to a reply string is fine, an `if` computing one is not.

### Task 9: `MailCommandListener` and the compose modal

**Files:** create `command/MailCommandListener.java`

Handles `/mail send|compose|list|read|dismiss|clear`. `deferReply(true)` then hop to the module's `asyncExecutor`, the shape `LinkSlashCommandListener` already uses. `compose` opens a modal (`ComponentIds.encode("mail", "compose", recipientName)`) with a paragraph `TextInput` capped at `MailPayload.MAX_MESSAGE_LENGTH`; `onModalInteraction` routes back into the same `DiscordMailService.send` call as `send`. An unresolved Discord user replies `DiscordUserResolver.NOT_LINKED_MESSAGE` before anything else.

- [ ] Implement.
- [ ] Run `./gradlew build` — expect PASS (no unit test; see Task 13)
- [ ] Commit

### Task 10: `NotificationsCommandListener` and `InboxInteractionListener`

**Files:** create `command/NotificationsCommandListener.java`, `command/InboxInteractionListener.java`

`NotificationsCommandListener` handles `/notifications list|read|dismiss|clear|mute|unmute` against the unfiltered `InboxView` and `PreferenceView.muteImmediately`/`unmuteImmediately`. `InboxInteractionListener` handles button and select interactions for **both** surfaces, dispatching on the `ComponentIds` surface to the matching `InboxView`, and replying `InboxMessageFactory.EXPIRED_MESSAGE` when `parse` returns empty. Both defer ephemerally and hop to `asyncExecutor`.

- [ ] Implement.
- [ ] Run `./gradlew build` — expect PASS (no unit test; see Task 13)
- [ ] Commit

### Task 11: `PreferenceInteractionListener`

**Files:** create `command/PreferenceInteractionListener.java`

Handles `/notifications prefs` and every component on its message: the data-type select, the media multi-select, Apply, Discard, and the mute toggle, each routed to the matching `PreferenceView` call and re-rendered with `PreferenceMessageFactory`. Component interactions `deferEdit()` and edit the original ephemeral message rather than posting a new one, so the staged state stays in one place.

- [ ] Implement.
- [ ] Run `./gradlew build` — expect PASS (no unit test; see Task 13)
- [ ] Commit

---

## Wave E

### Task 12: Wire it into `DiscordModule`

**Files:** modify `DiscordModule.java`; delete the temporary fake noted in Task 5 if it was written

Constructs, guarded by `settings.commandsEnabled()`: an `InboxEntryRenderer` over `plugin.notificationService().dataTypeRegistry()`; two `InboxView`s (filtered `MailPayload.DATA_TYPE` / "Mail", and `null` / "Notifications") with the host's `inbox-page-size`; `DiscordMailService` over `new MailSender(plugin.notificationService())`, `new MailNotifier(plugin.sinkRegistry(), plugin.preferences(), logger)`, a recipient resolver matching `MailCommand`'s (online-by-name, else `getOfflinePlayer` accepted only when `hasPlayedBefore()`) and a name lookup over `Bukkit.getOfflinePlayer`; a `PreferenceView` over `plugin.preferences()`, `plugin.sinkRegistry()` and a **module-owned** `PreferenceSessionManager`; the two message factories on `settings.resolvedEmbedColor()`; and the three listeners from Wave D, appended to `eventListeners` before `DiscordBot.start`.

`shutdown` needs no addition: the listeners die with the bot, and the module owns no command map entry and no host registration beyond the sink and link provider it already unregisters.

- [ ] Implement.
- [ ] Run `./gradlew build`
- [ ] Run `./gradlew :platform:discord-adapter:test` (Docker required for this one — the whole module suite) — expect PASS with the new hermetic tests included.
- [ ] Commit

---

## Wave F

### Task 13: Documentation and the manual checklist

**Files:** modify `CLAUDE.md`; modify this plan (tick the checklist as it is run)

- [ ] Add a "Discord slash commands" subsection under "Discord adapter" in `CLAUDE.md` covering: the single-`updateCommands` rule and why; the command table; `commands-enabled`; the reverse `playerFor` lookup and that it makes the commands work on a DiscordSRV-only server; stateless entry indexing; that Discord mail is always escaped plaintext with no `…format.*` gate; the module-owned `PreferenceSessionManager` and the last-Apply-wins consequence; modals used only for prose.
- [ ] Update the module's test count in "Testing gotchas" from the fresh `:platform:discord-adapter:test` output.
- [ ] Add the new classes to the "Untested by automated tests" list: `SlashCommandRegistrar`, `MailCommandListener`, `NotificationsCommandListener`, `InboxInteractionListener`, `PreferenceInteractionListener`.
- [ ] Run `./gradlew build`
- [ ] Commit

**Manual checklist** — needs `:platform:paper-plugin:runServer`, a real bot token, and a Discord account linked with `/notifications link discord`. Not yet run.

- [ ] `/link` still works after this change (the registration move did not drop it)
- [ ] An unlinked Discord user running `/mail list` is told to link, naming `/notifications link discord`
- [ ] `/mail send` to an online player: arrives in `/mail` in game, titled "Mail from <discord user's player name>"
- [ ] `/mail send` opens the compose modal; a multi-line body arrives with its line breaks
- [ ] `/mail send` to an offline player who has joined before: accepted; to a name that never joined:
      rejected (both reported on modal submit, since the recipient is only checked then)
- [ ] A modal body of `<red>hi` arrives as the literal text `<red>hi`, not coloured
- [ ] A modal body of `<click:run_command:/op me>x` arrives literal and is not clickable in game
- [ ] A mail arrival DM carries a *Read mail* button; clicking it opens the first mail as a new
      ephemeral reply and leaves the notice in place
- [ ] Opening an entry, then *Mark as unread*, returns to the listing with the row unread again
- [ ] A `/notifications` entry marked unread is delivered again on the player's next join
- [ ] The recipient's mail arrival notice reaches their preferred media, including a Discord DM
- [ ] `/mail list` shows only mail; `/notifications list` shows everything
- [ ] Prev/Next page through a >1 page inbox; the row select opens a detail; Dismiss removes it
- [ ] `/mail read entry:2` and `/mail read entry:99` — the second names the page's real size
- [ ] `/mail clear` empties the mail inbox and leaves non-mail notifications intact
- [ ] `/notifications prefs`: changing a type's media, pressing Apply, and confirming the change in game
- [ ] Discard reverts the staged change; the pending count in the header tracks it
- [ ] Emptying a type's media stages the mute and the type stops being pushed
- [ ] `/notifications mute` then `/notifications unmute` from Discord, verified in game
- [ ] A listing left for 15+ minutes replies with the expiry message rather than failing silently
- [ ] `commands-enabled: false` leaves `/link` registered and removes the other two
