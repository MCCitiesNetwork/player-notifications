# Embedded Discord account linking — implementation plan

**Goal:** Ship an own-link-table `DiscordAccountProvider` (`embedded`) plus a self-service
`/discordlink` → Discord `/link <code>` flow, so the Discord adapter works with no DiscordSRV installed.
**Spec:** `docs/superpowers/specs/2026-07-30-embedded-discord-linking-design.md`

Tasks 1–2 are `core`, 3–6 are `platform:discord-adapter` and independently testable, 7 is wiring
(manual verification only), 8 is docs.

---

## Task 1: `DiscordAccountLink` table, entity and mapper in `core`

**Files:**
- create `core/src/main/resources/sql/migrations/V2__discord_account_link.sql`
- create `core/src/main/java/io/github/md5sha256/playernotifications/core/database/entity/DiscordAccountLinkEntity.java`
- create `core/src/main/java/io/github/md5sha256/playernotifications/core/database/mapper/DiscordAccountLinkMapper.java`
- create `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/mapper/MariaDiscordAccountLinkMapper.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/SqlSessionWrapper.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaSqlSession.java`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaDatabase.java:60-62`
- modify `core/src/main/java/io/github/md5sha256/playernotifications/core/database/maria/MariaSchemaMigrator.java:46-48`
- test `core/src/test/java/io/github/md5sha256/playernotifications/DiscordAccountLinkMapperTest.java`

**Interfaces produced:**
```java
public record DiscordAccountLinkEntity(@NotNull UUID playerUuid, long discordId, @NotNull Instant linkedAt) {}

public interface DiscordAccountLinkMapper {
    @Nullable DiscordAccountLinkEntity selectByPlayer(@NotNull UUID playerUuid);
    @Nullable DiscordAccountLinkEntity selectByDiscordId(long discordId);
    int insertLink(@NotNull UUID playerUuid, long discordId, @NotNull Instant linkedAt);
    int deleteByPlayer(@NotNull UUID playerUuid);
    int deleteByDiscordId(long discordId);
}

// SqlSessionWrapper gains:
@NotNull DiscordAccountLinkMapper discordAccountLinkMapper();
```

- [ ] Write the failing test. Copy the container/bootstrap setup from the existing
      `core/src/test/java/.../PlayerNotificationPreferenceTest.java` (same `mariadb:11.7` Testcontainers
      + `initializeSchema` pattern), then:
```java
@Test
void insertsAndReadsBackByBothKeys() {
    UUID player = UUID.randomUUID();
    long discordId = 123456789012345678L;
    Instant linkedAt = Instant.parse("2026-07-30T12:00:00Z");
    try (SqlSessionWrapper wrapper = database.openSession()) {
        assertEquals(1, wrapper.discordAccountLinkMapper().insertLink(player, discordId, linkedAt));
        wrapper.session().commit();
    }
    try (SqlSessionWrapper wrapper = database.openSession()) {
        DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
        DiscordAccountLinkEntity byPlayer = mapper.selectByPlayer(player);
        assertNotNull(byPlayer);
        assertEquals(discordId, byPlayer.discordId());
        assertEquals(player, byPlayer.playerUuid());
        assertEquals(linkedAt, byPlayer.linkedAt());
        DiscordAccountLinkEntity byDiscord = mapper.selectByDiscordId(discordId);
        assertNotNull(byDiscord);
        assertEquals(player, byDiscord.playerUuid());
    }
}

@Test
void selectsReturnNullWhenAbsent() {
    try (SqlSessionWrapper wrapper = database.openSession()) {
        assertNull(wrapper.discordAccountLinkMapper().selectByPlayer(UUID.randomUUID()));
        assertNull(wrapper.discordAccountLinkMapper().selectByDiscordId(1L));
    }
}

@Test
void rejectsASecondPlayerForTheSameDiscordId() {
    long discordId = 222222222222222222L;
    try (SqlSessionWrapper wrapper = database.openSession()) {
        wrapper.discordAccountLinkMapper().insertLink(UUID.randomUUID(), discordId, Instant.now());
        wrapper.session().commit();
    }
    try (SqlSessionWrapper wrapper = database.openSession()) {
        DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
        assertThrows(PersistenceException.class,
                () -> mapper.insertLink(UUID.randomUUID(), discordId, Instant.now()));
    }
}

@Test
void deletesByEitherKey() {
    UUID playerA = UUID.randomUUID();
    UUID playerB = UUID.randomUUID();
    try (SqlSessionWrapper wrapper = database.openSession()) {
        wrapper.discordAccountLinkMapper().insertLink(playerA, 333L, Instant.now());
        wrapper.discordAccountLinkMapper().insertLink(playerB, 444L, Instant.now());
        wrapper.session().commit();
    }
    try (SqlSessionWrapper wrapper = database.openSession()) {
        assertEquals(1, wrapper.discordAccountLinkMapper().deleteByPlayer(playerA));
        assertEquals(1, wrapper.discordAccountLinkMapper().deleteByDiscordId(444L));
        assertEquals(0, wrapper.discordAccountLinkMapper().deleteByPlayer(UUID.randomUUID()));
        wrapper.session().commit();
    }
    try (SqlSessionWrapper wrapper = database.openSession()) {
        assertNull(wrapper.discordAccountLinkMapper().selectByPlayer(playerA));
        assertNull(wrapper.discordAccountLinkMapper().selectByPlayer(playerB));
    }
}
```
- [ ] Run `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.DiscordAccountLinkMapperTest"` —
      expect FAIL: `DiscordAccountLinkEntity`, the mapper, and `SqlSessionWrapper#discordAccountLinkMapper`
      do not exist, so the test does not compile.
- [ ] Implement `V2__discord_account_link.sql`:
```sql
CREATE TABLE IF NOT EXISTS DiscordAccountLink
(
    playerUuid BINARY(16) NOT NULL PRIMARY KEY,
    discordId  BIGINT     NOT NULL,
    linkedAt   DATETIME   NOT NULL,
    UNIQUE KEY uk_discord_account_link_discord_id (discordId)
);
```
- [ ] Implement: add `new MigrationStep(2, "discord account link", "V2__discord_account_link.sql")` to
      `MariaSchemaMigrator.DEFAULT_MIGRATIONS`.
- [ ] Implement the entity, the neutral mapper, and `MariaDiscordAccountLinkMapper` — `@Select` with
      `@ConstructorArgs` for `playerUuid`/`discordId`/`linkedAt` (mirror
      `MariaPlayerNotificationPreferenceMapper`'s style), plain `@Insert`, two `@Delete`s.
- [ ] Implement: `SqlSessionWrapper#discordAccountLinkMapper()`, the `MariaSqlSession` override, and
      `configuration.addMapper(MariaDiscordAccountLinkMapper.class)` in `MariaDatabase.buildSessionFactory`.
- [ ] Run the same test command — expect PASS (4 tests).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 2: verify V2 applies on top of an existing V1 database

**Files:** test `core/src/test/java/io/github/md5sha256/playernotifications/DiscordAccountLinkMapperTest.java`
(add a nested class or a second test method — no production change expected)

This exists as its own task because it can fail while Task 1 passes: Task 1's container starts empty and
runs both migrations at once, so it never exercises the "V1 already recorded as applied" path that every
real dev database is in.

- [ ] Write the failing test:
```java
@Test
void appliesV2OnTopOfADatabaseAlreadyAtV1() throws Exception {
    // A fresh schema migrated to V1 only, as an existing dev database would be.
    MariaSchemaMigrator.migrate(jdbcUrl, username, password, Path.of("sql/migrations"),
            List.of(new MigrationStep(1, "initial schema", "V1__maria_initial_schema.sql")), LOGGER);
    // Then the full default chain, which must add V2 without retrying V1.
    MariaSchemaMigrator.migrate(jdbcUrl, username, password, Path.of("sql/migrations"),
            MariaSchemaMigrator.defaultMigrations(), LOGGER);
    try (SqlSessionWrapper wrapper = database.openSession()) {
        assertNull(wrapper.discordAccountLinkMapper().selectByPlayer(UUID.randomUUID()));
    }
}
```
      Point it at a **second, separate** database/schema from the other tests in the class (create one with
      `CREATE DATABASE` over the container's admin connection, or use a second container) so the
      already-migrated shared fixture does not make the V1-only step a no-op.
- [ ] Run `./gradlew :core:test --tests "io.github.md5sha256.playernotifications.DiscordAccountLinkMapperTest"` —
      expect FAIL if V2's DDL is not re-runnable against a V1 database or the migrator mis-sequences;
      expect PASS immediately if Task 1 is correct, in which case this task is a regression guard and
      needs no production change. **Do not skip it on that basis** — record the pass.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 3: `DiscordAccountLinkStore` and `EmbeddedDiscordAccountProvider`

**Files:**
- create `platform/discord-adapter/src/main/java/.../discord/DiscordAccountLinkStore.java`
- create `platform/discord-adapter/src/main/java/.../discord/DatabaseDiscordAccountLinkStore.java`
- create `platform/discord-adapter/src/main/java/.../discord/EmbeddedDiscordAccountProvider.java`
- test `platform/discord-adapter/src/test/java/.../discord/EmbeddedDiscordAccountProviderTest.java`

**Interfaces produced:**
```java
public interface DiscordAccountLinkStore {
    @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid);
    @NotNull Optional<UUID> playerFor(long discordId);
    /** Upsert: clears any row for this player AND any row for this Discord id, then inserts. */
    void link(@NotNull UUID playerUuid, long discordId);
    /** @return whether a row was removed */
    boolean unlink(@NotNull UUID playerUuid);
}

public final class DatabaseDiscordAccountLinkStore implements DiscordAccountLinkStore {
    public DatabaseDiscordAccountLinkStore(@NotNull Database database, @NotNull Clock clock) { … }
}

public final class EmbeddedDiscordAccountProvider implements DiscordAccountProvider {
    public static final String PROVIDER_KEY = "embedded";
    public EmbeddedDiscordAccountProvider(@NotNull DiscordAccountLinkStore store) { … }
}
```

- [ ] Write the failing test:
```java
final class EmbeddedDiscordAccountProviderTest {

    /** Minimal in-memory store; reused by DiscordLinkFlowTest in Task 5. */
    static final class FakeStore implements DiscordAccountLinkStore { … }

    @Test
    void reportsItsKeyAndIsAlwaysAvailable() {
        EmbeddedDiscordAccountProvider provider = new EmbeddedDiscordAccountProvider(new FakeStore());
        assertEquals("embedded", provider.providerKey());
        assertTrue(provider.isAvailable());
    }

    @Test
    void resolvesALinkedPlayer() {
        FakeStore store = new FakeStore();
        UUID player = UUID.randomUUID();
        store.link(player, 987654321098765432L);
        assertEquals(Optional.of(987654321098765432L),
                new EmbeddedDiscordAccountProvider(store).discordIdFor(player));
    }

    @Test
    void returnsEmptyForAnUnlinkedPlayer() {
        assertEquals(Optional.empty(),
                new EmbeddedDiscordAccountProvider(new FakeStore()).discordIdFor(UUID.randomUUID()));
    }

    @Test
    void linkReplacesBothSides() {
        FakeStore store = new FakeStore();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.link(first, 555L);
        store.link(second, 555L);
        assertEquals(Optional.empty(), store.discordIdFor(first));
        assertEquals(Optional.of(555L), store.discordIdFor(second));
        store.link(second, 666L);
        assertEquals(Optional.empty(), store.playerFor(555L));
        assertEquals(Optional.of(second), store.playerFor(666L));
    }
}
```
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*EmbeddedDiscordAccountProviderTest"` —
      expect FAIL: neither the store interface nor the provider exists.
- [ ] Implement `DiscordAccountLinkStore`, `EmbeddedDiscordAccountProvider` (a one-line delegation plus
      `providerKey`), and `DatabaseDiscordAccountLinkStore`. Each store method opens one
      `database.openSession()`, uses `discordAccountLinkMapper()`, and commits via `wrapper.session().commit()`;
      `link` does `deleteByPlayer` + `deleteByDiscordId` + `insertLink` in that single session so the
      replacement is one transaction.
- [ ] Run the same command — expect PASS (4 tests).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 4: `LinkCodeService`

**Files:**
- create `platform/discord-adapter/src/main/java/.../discord/LinkCodeService.java`
- test `platform/discord-adapter/src/test/java/.../discord/LinkCodeServiceTest.java`

**Interfaces produced:**
```java
public final class LinkCodeService {
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;

    public LinkCodeService(@NotNull Duration ttl, @NotNull Clock clock) { … }

    /** Issues a code, replacing any outstanding code for this player. */
    public @NotNull String issue(@NotNull UUID playerUuid);
    /** Consumes the code and returns its player, or empty if unknown or expired. */
    public @NotNull Optional<UUID> redeem(@NotNull String code);
    /** Drops any outstanding code for this player. */
    public void cancel(@NotNull UUID playerUuid);
    /** Whether this player has a live, unredeemed code. */
    public boolean hasOutstandingCode(@NotNull UUID playerUuid);
    public @NotNull Duration ttl();
}
```

- [ ] Write the failing test (a `MutableClock` helper: `Clock` subclass over a mutable `Instant`):
```java
@Test
void issuesACodeFromTheRestrictedAlphabet() {
    String code = service.issue(UUID.randomUUID());
    assertEquals(LinkCodeService.CODE_LENGTH, code.length());
    for (char c : code.toCharArray()) {
        assertTrue(LinkCodeService.ALPHABET.indexOf(c) >= 0, "unexpected character " + c);
    }
}

@Test
void redeemsOnceAndConsumes() {
    UUID player = UUID.randomUUID();
    String code = service.issue(player);
    assertEquals(Optional.of(player), service.redeem(code));
    assertEquals(Optional.empty(), service.redeem(code));
    assertFalse(service.hasOutstandingCode(player));
}

@Test
void isCaseInsensitiveOnRedeem() {
    UUID player = UUID.randomUUID();
    String code = service.issue(player);
    assertEquals(Optional.of(player), service.redeem(code.toLowerCase(Locale.ROOT)));
}

@Test
void rejectsAnUnknownCode() {
    assertEquals(Optional.empty(), service.redeem("ZZZZZZ"));
}

@Test
void rejectsAnExpiredCode() {
    UUID player = UUID.randomUUID();
    String code = service.issue(player);
    clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    assertEquals(Optional.empty(), service.redeem(code));
    assertFalse(service.hasOutstandingCode(player));
}

@Test
void reissuingInvalidatesThePreviousCode() {
    UUID player = UUID.randomUUID();
    String first = service.issue(player);
    String second = service.issue(player);
    assertNotEquals(first, second);
    assertEquals(Optional.empty(), service.redeem(first));
    assertEquals(Optional.of(player), service.redeem(second));
}

@Test
void cancelInvalidatesAnOutstandingCode() {
    UUID player = UUID.randomUUID();
    String code = service.issue(player);
    service.cancel(player);
    assertFalse(service.hasOutstandingCode(player));
    assertEquals(Optional.empty(), service.redeem(code));
}
```
      (`service` is built in `@BeforeEach` with `Duration.ofMinutes(10)` and the mutable clock.)
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*LinkCodeServiceTest"` — expect FAIL:
      `LinkCodeService` does not exist.
- [ ] Implement: a `ConcurrentHashMap<String, PendingCode>` (`record PendingCode(UUID playerUuid, Instant expiresAt)`)
      plus a `ConcurrentHashMap<UUID, String>` reverse index so `issue` can evict the previous code and
      `cancel`/`hasOutstandingCode` are O(1). `issue` uppercases and uses `SecureRandom`, retrying on the
      (vanishingly unlikely) collision with a live code; `redeem` uppercases and trims its argument, removes
      the entry, and returns empty if `expiresAt` is not after `clock.instant()`. Sweep expired entries on
      each `issue`.
- [ ] Run the same command — expect PASS (7 tests).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 5: `DiscordLinkFlow` — every decision and message

**Files:**
- create `platform/discord-adapter/src/main/java/.../discord/DiscordLinkFlow.java`
- test `platform/discord-adapter/src/test/java/.../discord/DiscordLinkFlowTest.java`

**Interfaces produced:**
```java
public final class DiscordLinkFlow {

    public enum RedeemResult { LINKED, UNKNOWN_CODE, ALREADY_LINKED_TO_THIS_ACCOUNT, FAILED }

    public DiscordLinkFlow(@NotNull DiscordAccountLinkStore store,
                           @NotNull LinkCodeService codes,
                           @NotNull Logger logger) { … }

    /** The chat reply to a bare {@code /discordlink}. */
    public @NotNull Component begin(@NotNull UUID playerUuid);
    public @NotNull Component status(@NotNull UUID playerUuid);
    public @NotNull Component unlink(@NotNull UUID playerUuid);
    /** Called from the Discord side; {@code discordId} is the redeeming user. */
    public @NotNull RedeemResult redeem(@NotNull String code, long discordId);
}
```
`EXPIRED` from the spec's table is **folded into `UNKNOWN_CODE`**: `LinkCodeService#redeem` removes an
expired entry and returns empty, so the flow cannot distinguish the two without leaking expiry state out
of the code service. The two replies are therefore one message naming both causes — "not valid or
expired". Update the spec's table to match when this task lands.

- [ ] Write the failing test, reusing `EmbeddedDiscordAccountProviderTest.FakeStore` and a real
      `LinkCodeService` over a mutable clock. Assert on plain text via
      `PlainTextComponentSerializer.plainText().serialize(component)`:
```java
@Test
void beginIssuesACodeAndNamesItInTheMessage() {
    UUID player = UUID.randomUUID();
    String text = plain(flow.begin(player));
    assertTrue(codes.hasOutstandingCode(player));
    assertTrue(text.contains("/link"), text);
    assertTrue(text.contains("10 minutes"), text);
}

@Test
void beginRefusesWhenAlreadyLinked() {
    UUID player = UUID.randomUUID();
    store.link(player, 111L);
    String text = plain(flow.begin(player));
    assertFalse(codes.hasOutstandingCode(player));
    assertTrue(text.contains("already"), text);
    assertTrue(text.contains("unlink"), text);
}

@Test
void statusReportsTheLinkedIdOrItsAbsence() {
    UUID linked = UUID.randomUUID();
    store.link(linked, 222L);
    assertTrue(plain(flow.status(linked)).contains("222"));
    assertTrue(plain(flow.status(UUID.randomUUID())).toLowerCase(Locale.ROOT).contains("not linked"));
}

@Test
void statusMentionsAnOutstandingCode() {
    UUID player = UUID.randomUUID();
    flow.begin(player);
    assertTrue(plain(flow.status(player)).toLowerCase(Locale.ROOT).contains("code"));
}

@Test
void unlinkRemovesTheLinkAndCancelsAnyCode() {
    UUID player = UUID.randomUUID();
    store.link(player, 333L);
    assertTrue(plain(flow.unlink(player)).toLowerCase(Locale.ROOT).contains("unlinked"));
    assertEquals(Optional.empty(), store.discordIdFor(player));
    assertTrue(plain(flow.unlink(player)).toLowerCase(Locale.ROOT).contains("not linked"));
}

@Test
void redeemLinksAValidCode() {
    UUID player = UUID.randomUUID();
    String code = codes.issue(player);
    assertEquals(RedeemResult.LINKED, flow.redeem(code, 444L));
    assertEquals(Optional.of(444L), store.discordIdFor(player));
}

@Test
void redeemRejectsAnUnknownOrExpiredCode() {
    assertEquals(RedeemResult.UNKNOWN_CODE, flow.redeem("ZZZZZZ", 444L));
    String code = codes.issue(UUID.randomUUID());
    clock.advance(Duration.ofMinutes(11));
    assertEquals(RedeemResult.UNKNOWN_CODE, flow.redeem(code, 444L));
}

@Test
void redeemReportsAnExistingIdenticalLink() {
    UUID player = UUID.randomUUID();
    store.link(player, 555L);
    String code = codes.issue(player);   // issued directly, bypassing begin()'s already-linked guard
    assertEquals(RedeemResult.ALREADY_LINKED_TO_THIS_ACCOUNT, flow.redeem(code, 555L));
}

@Test
void redeemReportsFailureWhenTheStoreThrows() {
    DiscordLinkFlow failing = new DiscordLinkFlow(new ThrowingStore(), codes, LOGGER);
    assertEquals(RedeemResult.FAILED, failing.redeem(codes.issue(UUID.randomUUID()), 666L));
}

@Test
void statusAndUnlinkSurviveAThrowingStore() {
    DiscordLinkFlow failing = new DiscordLinkFlow(new ThrowingStore(), codes, LOGGER);
    UUID player = UUID.randomUUID();
    assertDoesNotThrow(() -> failing.status(player));
    assertDoesNotThrow(() -> failing.unlink(player));
    assertDoesNotThrow(() -> failing.begin(player));
}
```
      `ThrowingStore` is a `DiscordAccountLinkStore` whose every method throws
      `new PersistenceException("boom")`.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*DiscordLinkFlowTest"` — expect FAIL:
      `DiscordLinkFlow` does not exist.
- [ ] Implement `DiscordLinkFlow`. Every method wraps its store calls in `try`/`catch (RuntimeException)`,
      logging at `WARNING` and returning an error `Component` / `FAILED`. `begin` checks
      `store.discordIdFor` first and does **not** issue a code when already linked. `unlink` calls
      `codes.cancel` then `store.unlink`. `redeem` resolves the code, then compares
      `store.discordIdFor(player)` against the redeeming id to distinguish `ALREADY_LINKED_TO_THIS_ACCOUNT`
      from `LINKED`, and otherwise calls `store.link`.
- [ ] Run the same command — expect PASS (10 tests).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 6: availability reporting on the provider chain, and the new settings key

**Files:**
- modify `platform/discord-adapter/src/main/java/.../discord/ChainedDiscordAccountProvider.java`
- modify `platform/discord-adapter/src/main/java/.../discord/DiscordSettings.java`
- modify `platform/discord-adapter/src/main/resources/discord.yml`
- test `platform/discord-adapter/src/test/java/.../discord/ChainedDiscordAccountProviderTest.java` (extend)
- test `platform/discord-adapter/src/test/java/.../discord/DiscordSettingsTest.java` (extend)

**Interfaces produced:**
```java
// ChainedDiscordAccountProvider
public void reportAvailability();
public @NotNull List<String> delegateKeys();

// DiscordSettings — new component, after deliveryTimeoutSeconds
@Setting("link-code-expiry-seconds") long linkCodeExpirySeconds
public static final long DEFAULT_LINK_CODE_EXPIRY_SECONDS = 600L;
public @NotNull Duration resolvedLinkCodeExpiry();
public boolean usesEmbeddedProvider();   // linkProviders contains EmbeddedDiscordAccountProvider.PROVIDER_KEY
```

- [ ] Write the failing test:
```java
// ChainedDiscordAccountProviderTest
@Test
void reportAvailabilityWarnsWhenNoProviderIsAvailable() {
    List<LogRecord> records = new ArrayList<>();
    Logger logger = captureInto(records);          // existing helper style in this test class
    DiscordAccountProviderRegistry registry = new DiscordAccountProviderRegistry();
    registry.register(unavailableProvider("discordsrv"));
    ChainedDiscordAccountProvider chain =
            ChainedDiscordAccountProvider.of(List.of("discordsrv"), registry, logger);
    chain.reportAvailability();
    assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
            && r.getMessage().contains("discord-dm")));
}

@Test
void reportAvailabilityDoesNotWarnWhenOneIsAvailable() { … assertNoWarning … }

// DiscordSettingsTest
@Test
void clampsANonPositiveLinkCodeExpiry() {
    assertEquals(Duration.ofSeconds(DiscordSettings.DEFAULT_LINK_CODE_EXPIRY_SECONDS),
            settingsWithLinkCodeExpiry(0).resolvedLinkCodeExpiry());
}

@Test
void detectsTheEmbeddedProvider() {
    assertTrue(settingsWithProviders(List.of("embedded", "discordsrv")).usesEmbeddedProvider());
    assertFalse(settingsWithProviders(List.of("discordsrv")).usesEmbeddedProvider());
}
```
      If `DiscordSettingsTest` / a log-capturing helper does not already exist in this module, create it in
      the same shape as the existing tests there.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*ChainedDiscordAccountProviderTest" --tests "*DiscordSettingsTest"` —
      expect FAIL: `reportAvailability`, `linkCodeExpirySeconds`, `usesEmbeddedProvider` do not exist.
      Note that adding the record component breaks every existing `new DiscordSettings(...)` call in the
      module's tests — fix those call sites in this task.
- [ ] Implement `reportAvailability` (one `INFO` per delegate with its key and availability; one `WARNING`
      naming `DiscordMedia.DM` when none are available) and `delegateKeys`.
- [ ] Implement the `DiscordSettings` component, its clamp in the canonical constructor,
      `resolvedLinkCodeExpiry`, and `usesEmbeddedProvider`.
- [ ] Update `discord.yml`: change the `link-providers` default to `embedded` then `discordsrv`, document
      the `embedded` key and that it needs no other plugin, add the commented `link-code-expiry-seconds: 600`
      with an explanation, and note that `/discordlink` is only registered when `embedded` is listed.
- [ ] Run the same command — expect PASS.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 7: wire it up — `DiscordBot` listeners, the JDA slash command, `/discordlink`, `DiscordModule`

**Files:**
- modify `platform/discord-adapter/src/main/java/.../discord/DiscordBot.java`
- create `platform/discord-adapter/src/main/java/.../discord/LinkSlashCommandListener.java`
- create `platform/discord-adapter/src/main/java/.../discord/DiscordLinkCommand.java`
- modify `platform/discord-adapter/src/main/java/.../discord/DiscordModule.java`

**Interfaces produced:**
```java
// DiscordBot — replaces the existing single-argument start
public static @NotNull DiscordBot start(@NotNull String botToken, @NotNull Object... eventListeners);

public final class LinkSlashCommandListener extends ListenerAdapter {
    public static final String COMMAND_NAME = "link";
    public static final String CODE_OPTION = "code";
    public LinkSlashCommandListener(@NotNull DiscordLinkFlow flow,
                                    @NotNull Executor asyncExecutor,
                                    @NotNull Logger logger) { … }
}

public final class DiscordLinkCommand {
    public static final String PERMISSION = "playernotifications.discord.link";
    public static final String DESCRIPTION = "Link your Minecraft account to Discord";
    public static @NotNull LiteralCommandNode<CommandSourceStack> create(@NotNull DiscordLinkFlow flow,
                                                                        @NotNull Executor asyncExecutor);
}
```

**This task has no automated test** — every piece needs a live server, a real bot token, and a Discord
account. It is the exception the `implement` skill allows; verify by hand with the checklist below.

- [ ] Implement `DiscordBot.start(String, Object...)`, calling `JDABuilder#addEventListeners` before
      `build()`. Update the existing single-argument call site in `DiscordModule`.
- [ ] Implement `LinkSlashCommandListener`:
      - `onReady` → `event.getJDA().updateCommands().addCommands(Commands.slash(COMMAND_NAME, "Link your Minecraft account")
        .addOption(OptionType.STRING, CODE_OPTION, "The code from /discordlink in game", true))` with the
        interaction contexts including the bot-DM context, queued with a failure callback logging at
        `WARNING`.
      - **Verify the contexts API against JDA 6.5.0 before writing it** — JDA 5 used
        `setGuildOnly(boolean)` and JDA 6 uses `setContexts(InteractionContextType...)`. Check with
        `unzip -p platform/discord-adapter/build/libs/*-all.jar` or the dependency jar, or just let the
        compiler decide; do not guess.
      - `onSlashCommandInteraction` → ignore other command names; `event.deferReply(true).queue()`; then
        `asyncExecutor.execute(...)` to call `flow.redeem(code, event.getUser().getIdLong())` and reply
        through `event.getHook().sendMessage(...)`, mapping each `RedeemResult` to the string from the
        spec's table.
- [ ] Implement `DiscordLinkCommand`: a `notifications`-style Brigadier literal `discordlink`, gated on
      `PERMISSION`, player-only with the same "players only" guard shape as `NotificationsCommand.run`;
      bare → `flow.begin`, `status` → `flow.status`, `unlink` → `flow.unlink`. Each dispatches onto
      `asyncExecutor` (the flow does blocking JDBC) and sends the returned `Component` back to the player.
- [ ] Implement the `DiscordModule.initialize` wiring, after the existing bot start and before the sink
      registration:
      - `DiscordAccountLinkStore store = new DatabaseDiscordAccountLinkStore(plugin.database(), Clock.systemUTC());`
      - `providers.register(new EmbeddedDiscordAccountProvider(store));` alongside the existing DiscordSRV
        registration.
      - `accounts.reportAvailability();` after the chain is built.
      - Only when `settings.usesEmbeddedProvider()`:
        - `LinkCodeService codes = new LinkCodeService(settings.resolvedLinkCodeExpiry(), Clock.systemUTC());`
        - `DiscordLinkFlow flow = new DiscordLinkFlow(store, codes, logger);`
        - pass `new LinkSlashCommandListener(flow, asyncExecutor, logger)` to `DiscordBot.start`
          (so move the bot start below the flow construction),
        - register the permission:
          `pm.addPermission(new Permission(DiscordLinkCommand.PERMISSION, "…", PermissionDefault.TRUE))`
          guarded by a `pm.getPermission(...) == null` check,
        - `plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
          event.registrar().register(DiscordLinkCommand.create(flow, asyncExecutor), DiscordLinkCommand.DESCRIPTION, List.of("dlink")));`
        - log at `INFO` that `/discordlink` is available.
      - `asyncExecutor` is `runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)`.
      - In `shutdown`, unregister the command **and** remove the permission, so a module stop/start cycle
        does not leave a `/discordlink` behind that dispatches into a dead flow and a shut-down bot:
        - `DiscordLinkCommand.unregister()` — Paper's Brigadier registrar exposes no unregister, so this
          goes through `Bukkit.getCommandMap()`: `unregister` the `Command` for `discordlink` and drop the
          literal, its `dlink` alias, and their plugin-namespaced forms from
          `CommandMap#getKnownCommands()`. **Verify the available API before writing it** — if the known-commands
          map is not reachable on this Paper version, fall back to leaving the node registered but having
          `DiscordLinkFlow` guard on a `volatile boolean shutdown` flag and reply "Discord linking is not
          currently available", and say so in the commit message.
        - `Bukkit.getOnlinePlayers().forEach(Player::updateCommands)` afterwards, or online clients keep
          suggesting a command the server no longer knows.
        - `pm.removePermission(DiscordLinkCommand.PERMISSION)`.
      - Guard both on having actually registered them (`settings.usesEmbeddedProvider()` was true), and make
        `shutdown` idempotent — it already tolerates a null bot.
- [ ] Run `./gradlew build` and `./gradlew :platform:discord-adapter:shadowJar`
- [ ] **Verify the relocation set against the built jar** (CLAUDE.md requires this after any change to the
      shaded module): `unzip -l platform/discord-adapter/build/libs/*-all.jar | grep -v "io/github/md5sha256"`
      and confirm nothing but metadata and the module's own resources appears outside that prefix. No
      dependency was added in this task, so a change here would be a surprise worth investigating.
- [ ] **Manual verification** with `./gradlew :platform:paper-plugin:runServer` (needs a reachable MariaDB
      per `database.yml` and a real bot token in `run/plugins/PlayerNotifications/modules/discord.yml`):
      1. Start with `link-providers: [embedded]` and **DiscordSRV deleted** from `run/plugins/`. Confirm
         the module starts, logs the availability report with `embedded: available`, logs no warning, and
         logs that `/discordlink` is available.
      2. Confirm the server log shows migration `V2: discord account link` applied on first start and not
         on the second.
      3. In game, run `/discordlink`; confirm a 6-character code and the `/link` instruction.
      4. In Discord, DM the bot `/link <code>`; confirm an ephemeral "Linked to <player>" reply and a row
         in `DiscordAccountLink`.
      5. `/discordlink status` shows the Discord id; a second `/discordlink` refuses with "already linked".
      6. `/notifications test` delivers a DM (set `discord-dm` as a preferred medium first via
         `/notifications media`). This is the whole point of the feature.
      7. `/discordlink unlink`, then `/notifications test` again — confirm the notification is not
         delivered by `discord-dm` and the `UNSUPPORTED` warning appears.
      8. Redeem a code twice; confirm the second attempt says the code is not valid.
      9. Wait past `link-code-expiry-seconds` (drop it to 60 for this check) and redeem; confirm the
         not-valid reply.
      10. Set `link-providers: [discordsrv]`, restart, and confirm `/discordlink` is **not** registered and
          the availability report warns that `discord-dm` is undeliverable (DiscordSRV still absent).
      11. With `embedded` configured again, stop the server and confirm the module's `shutdown` runs without
          an exception and logs no complaint about the command or the permission.
- [ ] Commit

---

## Task 8: documentation

**Files:**
- modify `CLAUDE.md`
- modify `docs/superpowers/specs/2026-07-30-embedded-discord-linking-design.md`
- modify `docs/superpowers/specs/2026-07-29-discord-adapter-design.md`

- [ ] Update the spec: fold `EXPIRED` into `UNKNOWN_CODE` in the `/link` reply table (see Task 5), and
      mark the status as implemented.
- [ ] Note in the discord adapter design that its "DiscordSRV-only, no in-game link flow" limitation is
      superseded by the new spec.
- [ ] Update `CLAUDE.md`:
      - "Discord adapter": `link-providers` now defaults to `[embedded, discordsrv]`; describe
        `EmbeddedDiscordAccountProvider`, the `/discordlink` command and the Discord `/link` slash command;
        note that DiscordSRV is now genuinely optional and that `DiscordBot` takes event listeners and so
        is no longer send-only; add `link-code-expiry-seconds` to the `discord.yml` key list; extend the
        "Untested by automated tests" list with `LinkSlashCommandListener`, `DiscordLinkCommand` and
        `DatabaseDiscordAccountLinkStore`.
      - "Player commands": add a note that `/discordlink` exists, is owned by the Discord module rather than
        the host, and registers its permission programmatically because the module jar has no plugin
        descriptor.
      - "Persistence layer": add `DiscordAccountLink` to the schema list, add the `DiscordAccountLinkMapper`
        trio to the mapper lists, and update the migration note — `DEFAULT_MIGRATIONS` now has two steps, so
        the "currently a single step … earlier migrations were collapsed" sentence is stale.
      - "Testing gotchas": update the test-count baseline from the actual fresh run.
      - "Current state": remove "Discord account linking is DiscordSRV-only, and there is no in-game link
        flow" and replace it with what is still open (no admin link management, no rate limiting, codes not
        persisted, the `core`-owns-a-Discord-table compromise).
- [ ] Run `./gradlew build` and the full `./gradlew test`; count results with a `*.xml` glob, not `TEST-*.xml`.
- [ ] Commit