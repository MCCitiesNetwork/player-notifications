# EssentialsX mail converter Implementation Plan

**Goal:** a feature module that imports a server's existing EssentialsX mailboxes into first-party mail, once, via an op-only `/essmailconvert` command.
**Spec:** `docs/superpowers/specs/2026-08-20-essentials-mail-converter-design.md`

## Task 1: `MailSender` keeps an explicit send time

**Files:**
- modify `platform/paper-plugin/src/main/java/io/github/md5sha256/playernotifications/paper/mail/MailSender.java`
- test `platform/paper-plugin/src/test/java/io/github/md5sha256/playernotifications/paper/mail/MailSenderTest.java`

**Interfaces:**
```java
public @NotNull String send(@NotNull UUID sender, @NotNull String senderName,
                            @NotNull UUID recipient, @NotNull String message);
public @NotNull String send(@NotNull UUID sender, @NotNull String senderName,
                            @NotNull UUID recipient, @NotNull String message,
                            @NotNull Instant sentAt);
```

- [ ] Write the failing test in `MailSenderTest`:
```java
@Test
void storesTheGivenSendTimeAsTheScheduledTime() {
    RecordingService service = new RecordingService();
    Instant sentAt = Instant.parse("2019-04-01T12:00:00Z");
    new MailSender(service).send(SENDER, "Alex", RECIPIENT, "hello", sentAt);
    assertEquals(sentAt, service.last().notifScheduledTime());
}
```
- [ ] Run `./gradlew :platform:paper-plugin:test --tests "*MailSenderTest"` — expect FAIL: no five-argument `send` exists, so the test does not compile.
- [ ] Implement: add the five-argument overload using `sentAt` as the `TypedNotification`'s scheduled time; make the existing four-argument form `return send(sender, senderName, recipient, message, Instant.now());`. Javadoc the new parameter with why it exists (inbox ordering).
- [ ] Run the same command — expect PASS, with the existing `MailSenderTest` cases still passing.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 2: the module skeleton, its manifest and its build wiring

**Files:**
- create `platform/essentials-mail-converter/build.gradle.kts`
- create `platform/essentials-mail-converter/src/main/resources/module-manifest.yml`
- create `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/EssentialsMailConverterModule.java`
- modify `settings.gradle.kts`
- modify `platform/paper-plugin/build.gradle.kts`

**Interfaces:**
```java
public final class EssentialsMailConverterModule extends SimplePluginModule<PlayerNotificationsPlugin>
```

- [ ] This task has no unit test: it is build wiring plus an entry class whose whole contract is
  "mentions no EssentialsX type", which is a property of the compiled class, not of behaviour.
  Verification is the build plus the byte-level check below.
- [ ] Implement `build.gradle.kts`, copied from the retired `platform/essentials-adapter` (git
  `47691c1^`): `plugins { \`paper-adapter\` }`, the `https://repo.essentialsx.net/releases/` repo, and
  `compileOnly("net.essentialsx:EssentialsX:2.21.2")` excluding `org.bukkit:bukkit` and
  `org.spigotmc:spigot-api`.
- [ ] Implement `module-manifest.yml` with kebab-case keys: `module-name: essentials-mail-converter`,
  `entry-class: io.github.md5sha256.playernotifications.essentials.convert.EssentialsMailConverterModule`,
  `author: md5sha256`,
  `expected-plugin-class: io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin`,
  `reloadable: false`.
- [ ] Implement `EssentialsMailConverterModule.initialize` as: if
  `plugin.getServer().getPluginManager().isPluginEnabled("Essentials")` is false, log INFO
  "EssentialsX is not installed; nothing to convert" and return; else call
  `EssentialsMailBinding.register(plugin)` (added in Task 5 — until then, a `// TODO` is *not*
  acceptable, so land this task with the presence check and an empty else branch that logs, and wire
  the call in Task 5).
- [ ] Add `include("platform:essentials-mail-converter")` to `settings.gradle.kts`.
- [ ] Add `featureModules(project(path = ":platform:essentials-mail-converter", configuration = "moduleJar"))`
  to `platform/paper-plugin/build.gradle.kts`.
- [ ] Run `./gradlew :platform:essentials-mail-converter:build`
- [ ] Verify the isolation property against the built class:
  `javap -c -p build/classes/java/main/.../EssentialsMailConverterModule.class | grep -i essentials`
  must show no `com/earth2me` or `net/ess3` reference.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 3: `ImportedMail` and the legacy flattening rules

**Files:**
- create `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/ImportedMail.java`
- test `platform/essentials-mail-converter/src/test/java/io/github/md5sha256/playernotifications/essentials/convert/ImportedMailTest.java`

**Interfaces:**
```java
public record ImportedMail(@NotNull UUID recipient, @NotNull UUID sender, @NotNull String senderName,
                           @NotNull String message, @NotNull Instant sentAt, boolean read,
                           boolean expired) {
    public static final UUID UNKNOWN_SENDER = new UUID(0L, 0L);
    public static final String UNKNOWN_SENDER_NAME = "Unknown";

    public static @NotNull ImportedMail of(@NotNull UUID recipient, boolean legacy, boolean read,
                                           @Nullable String senderName, @Nullable UUID senderId,
                                           long timeSent, long timeExpire, @NotNull String message,
                                           @NotNull Instant now);
    static @NotNull String stripColourCodes(@NotNull String text);
}
```
`of` is deliberately parameterised by the *fields* of an EssentialsX `MailMessage` rather than taking
one, so this class and its test never touch EssentialsX.

- [ ] Write the failing test `ImportedMailTest` covering: a legacy mail's sender becomes
  `UNKNOWN_SENDER`/`"Unknown"` and its message keeps the text with `§6[§rBob§6]§r hi` reduced to
  `[Bob] hi`; a modern mail with a null sender UUID keeps its real `senderName` but takes
  `UNKNOWN_SENDER`; `timeExpire == 0` is never expired; `timeExpire` in the past relative to the
  supplied `now` is `expired`; `timeExpire` in the future is not; `sentAt` equals
  `Instant.ofEpochMilli(timeSent)`; a message with no `§` is returned unchanged; a trailing lone `§`
  does not throw.
- [ ] Run `./gradlew :platform:essentials-mail-converter:test --tests "*ImportedMailTest"` — expect FAIL: `ImportedMail` does not exist.
- [ ] Implement `ImportedMail`. `stripColourCodes` removes `§` followed by any single character
  (matching Bukkit's legacy code alphabet loosely on purpose: an unrecognised code is still noise).
  `of` blanks nothing and rejects nothing — filtering is the converter's job, so the record's own
  compact constructor must not reject a blank message the way `MailPayload` does.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 4: `EssentialsMailConverter` — the mapping rules

**Files:**
- create `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/EssentialsMailConverter.java`
- create `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/ConversionReport.java`
- test `platform/essentials-mail-converter/src/test/java/io/github/md5sha256/playernotifications/essentials/convert/EssentialsMailConverterTest.java`

**Interfaces:**
```java
public record ConversionReport(int imported, int skippedExpired, int skippedBlank, int failed) {
    public int total();
}

public final class EssentialsMailConverter {
    public EssentialsMailConverter(@NotNull NotificationService service, @NotNull MailSender mailSender,
                                   @NotNull Logger logger);
    public @NotNull ConversionReport preview(@NotNull List<ImportedMail> mail);
    public @NotNull ConversionReport convert(@NotNull List<ImportedMail> mail);
}
```

- [ ] Write the failing test `EssentialsMailConverterTest` against a recording `NotificationService`
  (following `MailSenderTest`'s existing fake), covering: an expired mail is skipped and counted in
  `skippedExpired` with nothing enqueued; a blank message is skipped into `skippedBlank`; a message
  longer than `MailPayload.MAX_MESSAGE_LENGTH` is enqueued **whole**, character for character; a
  `read` mail is enqueued and then `markSeen(key, recipient)` is called with the key the send
  returned; an unread mail is enqueued and `markSeen` is not called; `notifScheduledTime` equals the
  mail's `sentAt`; `notifExpiryTime` is null even when the source mail had a future expiry; an
  `enqueueNotification` that throws increments `failed` and the following mail is still imported; a
  `markSeen` that throws leaves the mail counted in `imported`, not in `failed`; and `preview`
  produces the same counts as `convert` while enqueueing nothing.
- [ ] Run `./gradlew :platform:essentials-mail-converter:test --tests "*EssentialsMailConverterTest"` — expect FAIL: `EssentialsMailConverter` does not exist.
- [ ] Implement, sharing one classification method between `preview` and `convert` so the two cannot
  report different numbers.
- [ ] Run the same command — expect PASS
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 5: the EssentialsX-facing half and the command

**Files:**
- create `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/EssentialsMailBinding.java`
- create `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/EssentialsMailReader.java`
- create `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/ConvertMailCommand.java`
- modify `platform/essentials-mail-converter/src/main/java/io/github/md5sha256/playernotifications/essentials/convert/EssentialsMailConverterModule.java`

**Interfaces:**
```java
final class EssentialsMailBinding {
    static void register(@NotNull PlayerNotificationsPlugin plugin);   // no EssentialsX type in the signature
}

final class EssentialsMailReader {
    EssentialsMailReader(@NotNull Plugin plugin, @NotNull IEssentials essentials, @NotNull Logger logger);
    void readAsync(@NotNull Consumer<List<ImportedMail>> onComplete);  // chunked, main thread, 100 users/tick
}

public final class ConvertMailCommand {
    static final String PERMISSION = "essentialsmailconverter.command.convert";
    static @NotNull LiteralCommandNode<CommandSourceStack> create(
            @NotNull EssentialsMailReader reader, @NotNull EssentialsMailConverter converter,
            @NotNull Executor asyncExecutor);
}
```

- [ ] **No unit test — this task is the live-server exception.** Every class here needs either an
  EssentialsX `IUserMap`, the Bukkit scheduler or a Brigadier registrar. The logic that *can* be
  tested was pushed into `ImportedMail` and `EssentialsMailConverter` in Tasks 3 and 4 precisely so
  this task holds nothing but wiring. Manual verification is the checklist in Task 6.
- [ ] Implement `EssentialsMailReader`: `getUsers().getAllUserUUIDs()` into an `ArrayList`, then a
  repeating task (`runTaskTimer`, period 1 tick) taking `USERS_PER_TICK = 100` per run,
  `loadUncachedUser(uuid)`, `getMailMessages()`, mapping through `ImportedMail.of` with
  `Instant.now()` as the expiry reference; each user wrapped in try/catch logging a `WARNING` naming
  the UUID and continuing; on exhaustion cancel the task and invoke `onComplete`.
- [ ] Implement `ConvertMailCommand` with `requires(src -> src.getSender().hasPermission(PERMISSION))`,
  literals `preview` and `confirm`, an `AtomicBoolean` in-flight guard refusing a concurrent run, and
  a bare-command reply naming both subcommands and stating that `confirm` is **not** idempotent.
  Both subcommands: `reader.readAsync(mail -> asyncExecutor.execute(() -> { … report … }))`, so the
  EssentialsX read is on the main thread and the JDBC write is not.
- [ ] Implement `EssentialsMailBinding.register`: cast the `Essentials` plugin to `IEssentials`,
  build reader + `EssentialsMailConverter`, and
  `plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
  event.registrar().register(ConvertMailCommand.create(...), DESCRIPTION));`
- [ ] Replace the Task 2 placeholder branch in `EssentialsMailConverterModule` with the
  `EssentialsMailBinding.register(plugin)` call.
- [ ] Run `./gradlew :platform:essentials-mail-converter:build`
- [ ] Re-run the Task 2 `javap` isolation check on `EssentialsMailConverterModule.class` — it must
  still show no EssentialsX reference now that the binding call is wired in.
- [ ] Run `./gradlew build`
- [ ] Commit

## Task 6: manual verification on a live server

**Files:** none — this task produces a checked-off checklist, not code.

Run `./gradlew :platform:paper-plugin:runServer`, which builds and installs the new module through
`installFeatureModules`. EssentialsX must be dropped into `platform/paper-plugin/run/plugins/` by
hand; it is deliberately not added to `downloadPlugins`, since the host does not depend on it.

- [ ] **Without EssentialsX installed**, the server starts, the host enables cleanly, the log shows
  "EssentialsX is not installed; nothing to convert", and `/essmailconvert` does not exist. (This is
  the `NoClassDefFoundError` isolation check — a regression here takes down the whole plugin.)
- [ ] **With EssentialsX installed**, the module registers and `/essmailconvert` tab-completes
  `preview` and `confirm`.
- [ ] A non-op player gets no such command / no permission.
- [ ] Send yourself Essentials mail with `/mail send <you> hello`, then `/essmailconvert preview` —
  reports 1 to import, and `/mail` (first-party) still shows nothing.
- [ ] `/essmailconvert confirm` — reports 1 imported, and `/mail` now shows it with the right sender
  name and body.
- [ ] The imported mail is **unread** in `/mail` if it was unread in Essentials, and **read** if it
  had been read there.
- [ ] The Essentials copy is still present in `/essentials:mail`.
- [ ] Running `confirm` twice produces two copies — confirming the documented non-idempotency rather
  than a silent surprise.
- [ ] Run from the console: the report appears in the log.
- [ ] Record the outcome in `CLAUDE.md` under "Current state" — Task 7.

## Task 7: documentation

**Files:** modify `CLAUDE.md`

- [ ] Add an "EssentialsX mail converter" subsection under the module documentation covering: what it
  is, that it is a one-shot migration and not an integration, the non-idempotency, the mapping rules
  table in brief, the `MailSender` overload, and the class-isolation rule for the entry class.
- [ ] Update the Overview's module list, "Build & run" (the new `:platform:essentials-mail-converter`
  test task, which needs **no** Docker daemon), and `settings.gradle.kts`'s include list.
- [ ] Update the test baseline counts in "Testing gotchas" with fresh `./gradlew test` output.
- [ ] Update "Current state" with the verified/unverified split from Task 6.
- [ ] Run `./gradlew build`
- [ ] Commit
