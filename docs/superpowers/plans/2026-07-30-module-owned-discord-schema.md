# Module-owned Discord link schema — implementation plan

**Goal:** Move the Discord link table, entity, mapper and migration out of `core` and into
`platform:discord-adapter`, which owns its own schema and migrator while reusing the host's connection
pool, session factory and type handlers.
**Spec:** `docs/superpowers/specs/2026-07-30-module-owned-discord-schema-design.md`

Task 1 makes the adapter able to test against a real database. Task 2 gives it its own migrator. Task 3
moves the persistence types and cuts `core`'s dependency on them. Task 4 rewrites `core`'s affected tests.
Task 5 is docs. `./gradlew build` stays green at the end of every task except within Task 3, which is a
single atomic move.

**One-time manual step, needed before running anything against an existing dev database** (see the spec's
"Migrating an existing dev database"): `DELETE FROM schema_version WHERE version = 2;`

---

## Task 1: Testcontainers in the adapter, and a shared database fixture

**Files:**
- modify `platform/discord-adapter/build.gradle.kts`
- test create `platform/discord-adapter/src/test/java/io/github/md5sha256/playernotifications/discord/schema/AbstractDiscordDatabaseTest.java`

**Interfaces produced:**
```java
abstract class AbstractDiscordDatabaseTest {
    protected static final MariaDBContainer CONTAINER;   // started once for the run
    /** A host Database over the container, exactly as the plugin builds it. */
    protected static Database hostDatabase();
    /** A fresh, empty schema name, so each test owns its own database. */
    protected static String freshSchema() throws SQLException;
    protected static Database databaseFor(String schema);
}
```

The fixture deliberately hands out **per-test schemas** rather than truncating a shared one: these tests
assert on DDL and on migration bookkeeping, so a case must start with no tables at all.

- [ ] Write the failing test — a self-check that the fixture works, since everything later depends on it:
```java
class AbstractDiscordDatabaseTestSelfTest extends AbstractDiscordDatabaseTest {

    @Test
    @DisplayName("each schema starts empty and is independent")
    void handsOutEmptyIndependentSchemas() throws Exception {
        String first = freshSchema();
        String second = freshSchema();
        Assertions.assertNotEquals(first, second);

        try (Database database = databaseFor(first);
             SqlSessionWrapper wrapper = database.openSession(true);
             Statement statement = wrapper.session().getConnection().createStatement()) {
            statement.execute("CREATE TABLE marker (id INT PRIMARY KEY)");
        }

        // The second schema must not see the first's table, or migration tests would contaminate.
        try (Database database = databaseFor(second);
             SqlSessionWrapper wrapper = database.openSession(true);
             Statement statement = wrapper.session().getConnection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables"
                             + " WHERE table_schema = '" + second + "' AND table_name = 'marker'")) {
            rs.next();
            Assertions.assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("the host session exposes the connection and configuration the adapter relies on")
    void exposesTheSeamsTheAdapterUses() throws Exception {
        // If either of these ever stops being reachable, every later task's approach is invalid.
        try (Database database = databaseFor(freshSchema());
             SqlSessionWrapper wrapper = database.openSession(true)) {
            Assertions.assertNotNull(wrapper.session().getConnection());
            Assertions.assertNotNull(wrapper.session().getConfiguration());
        }
    }
}
```
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*AbstractDiscordDatabaseTestSelfTest"` —
      expect FAIL: `org.testcontainers` is not on the adapter's test classpath, so the fixture does not
      compile.
- [ ] Implement: add to `platform/discord-adapter/build.gradle.kts` `dependencies`, with a comment saying
      this suite now needs a Docker daemon:
```kotlin
testImplementation("org.testcontainers:testcontainers-mariadb:2.0.1")
testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
```
      (`mariadb-java-client` is `implementation` in `core`, so it does **not** reach the adapter
      transitively — without it the driver is missing at test runtime.)
- [ ] Implement `AbstractDiscordDatabaseTest`: a static `MariaDBContainer("mariadb:11.7")` with
      `MARIADB_ROOT_PASSWORD`, started in a static initialiser (mirroring
      `core`'s `AbstractDatabaseTest`); `freshSchema()` issues `CREATE DATABASE upgrade_<nanoTime>` over a
      root connection; `databaseFor(schema)` builds a `MariaDatabase` from a `DatabaseSettings` whose url
      is the container's host/port plus that schema, with the `jdbc:` prefix stripped.
- [ ] Run the same command — expect PASS (2 tests).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 2: `DiscordSchemaMigrator`

**Files:**
- create `platform/discord-adapter/src/main/resources/sql/discord/V1__discord_account_link.sql`
- create `platform/discord-adapter/src/main/java/.../discord/schema/DiscordMigrationStep.java`
- create `platform/discord-adapter/src/main/java/.../discord/schema/DiscordSchemaMigrator.java`
- test create `platform/discord-adapter/src/test/java/.../discord/schema/DiscordSchemaMigratorTest.java`

**Interfaces produced:**
```java
public record DiscordMigrationStep(int version, @NotNull String description, @NotNull String resourcePath) {}

public final class DiscordSchemaMigrator {
    public static final String VERSION_TABLE = "discord_schema_version";
    public static @NotNull List<DiscordMigrationStep> defaultMigrations();
    public static void migrate(@NotNull Database database, @NotNull Logger logger)
            throws IOException, SQLException;
    /** Package-private seam so a test can drive an arbitrary chain. */
    static void migrate(@NotNull Database database, @NotNull List<DiscordMigrationStep> steps,
                        @NotNull Logger logger) throws IOException, SQLException;
}
```

The DDL is copied verbatim from `core/src/main/resources/sql/migrations/V2__discord_account_link.sql`,
with its leading comment updated: the "lives in core because a module cannot own a migration" paragraph is
now wrong and is replaced by a note that the module owns this table.

- [ ] Write the failing test:
```java
class DiscordSchemaMigratorTest extends AbstractDiscordDatabaseTest {

    private static final Logger LOGGER = Logger.getLogger("discord-migrator-test");

    @Test
    @DisplayName("a fresh database gets the table and a recorded V1")
    void migratesAFreshDatabase() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            DiscordSchemaMigrator.migrate(database, LOGGER);
            Assertions.assertTrue(tableExists(schema, "DiscordAccountLink"));
            Assertions.assertEquals(List.of(1), appliedVersions(schema));
        }
    }

    @Test
    @DisplayName("re-running applies nothing")
    void isIdempotent() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            DiscordSchemaMigrator.migrate(database, LOGGER);
            DiscordSchemaMigrator.migrate(database, LOGGER);
            // A second insert of version 1 would fail on the primary key before reaching this.
            Assertions.assertEquals(List.of(1), appliedVersions(schema));
        }
    }

    @Test
    @DisplayName("a version newer than the known chain is refused")
    void refusesADowngrade() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            DiscordSchemaMigrator.migrate(database, LOGGER);
            execute(schema, "INSERT INTO " + DiscordSchemaMigrator.VERSION_TABLE
                    + " (version, description) VALUES (99, 'from the future')");
            Assertions.assertThrows(SQLException.class,
                    () -> DiscordSchemaMigrator.migrate(database, LOGGER));
        }
    }

    @Test
    @DisplayName("the module's chain is independent of core's")
    void doesNotInterfereWithCoresChain() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            // Core's migrations first, exactly as the host runs them on enable.
            database.initializeSchema(Path.of("sql/migrations"));
            DiscordSchemaMigrator.migrate(database, LOGGER);

            // Two tables, two independent chains both starting at 1.
            Assertions.assertTrue(tableExists(schema, "Notification"));
            Assertions.assertTrue(tableExists(schema, "DiscordAccountLink"));
            Assertions.assertEquals(List.of(1), appliedVersions(schema));
            Assertions.assertEquals(List.of(1), coreAppliedVersions(schema));
        }
    }

    @Test
    @DisplayName("a missing script resource is reported with its path")
    void reportsAMissingScript() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            List<DiscordMigrationStep> broken =
                    List.of(new DiscordMigrationStep(1, "absent", "sql/discord/V404__nope.sql"));
            IOException failure = Assertions.assertThrows(IOException.class,
                    () -> DiscordSchemaMigrator.migrate(database, broken, LOGGER));
            Assertions.assertTrue(failure.getMessage().contains("V404__nope.sql"), failure.getMessage());
        }
    }
}
```
      Add `appliedVersions(schema)` (reads `discord_schema_version`), `coreAppliedVersions(schema)` (reads
      `schema_version`), `tableExists(schema, table)` and `execute(schema, sql)` helpers to
      `AbstractDiscordDatabaseTest`, since Task 3's tests need them too.
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*DiscordSchemaMigratorTest"` — expect FAIL:
      `DiscordSchemaMigrator` and `DiscordMigrationStep` do not exist.
- [ ] Implement `DiscordMigrationStep`, then `DiscordSchemaMigrator` per the spec's "Behaviour": open
      `database.openSession(true)`, take `session().getConnection()`, `CREATE TABLE IF NOT EXISTS` the
      version table, read `COALESCE(MAX(version), 0)`, refuse a current version above the highest known
      step, then for each pending step load the resource through
      `DiscordSchemaMigrator.class.getClassLoader()`, split on `;`, execute each non-blank statement, and
      insert the version row. Log each applied step at `INFO`.
- [ ] Run the same command — expect PASS (5 tests).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 3: move the entity and mappers, and cut `core`'s Discord dependency

This is one atomic commit: `core` stops compiling the moment `SqlSessionWrapper#discordAccountLinkMapper`
goes, and the adapter's store stops compiling until it resolves its mapper the new way. Splitting it would
leave a commit that does not build.

**Files:**
- create `platform/discord-adapter/src/main/java/.../discord/schema/DiscordAccountLinkEntity.java` (moved)
- create `platform/discord-adapter/src/main/java/.../discord/schema/DiscordAccountLinkMapper.java` (moved)
- create `platform/discord-adapter/src/main/java/.../discord/schema/MariaDiscordAccountLinkMapper.java` (moved)
- delete `core/src/main/java/.../core/database/entity/DiscordAccountLinkEntity.java`
- delete `core/src/main/java/.../core/database/mapper/DiscordAccountLinkMapper.java`
- delete `core/src/main/java/.../core/database/maria/mapper/MariaDiscordAccountLinkMapper.java`
- delete `core/src/main/resources/sql/migrations/V2__discord_account_link.sql`
- modify `core/src/main/java/.../core/database/SqlSessionWrapper.java` — drop `discordAccountLinkMapper()`
- modify `core/src/main/java/.../core/database/maria/MariaSqlSession.java` — drop the override
- modify `core/src/main/java/.../core/database/maria/MariaDatabase.java` — drop the `addMapper` line
- modify `core/src/main/java/.../core/database/maria/MariaSchemaMigrator.java` — drop the V2 step
- modify `core/src/test/java/.../core/database/AbstractDatabaseTest.java` — drop the `DiscordAccountLink` truncate
- move `core/src/test/java/.../core/database/DiscordAccountLinkMapperTest.java` → `platform/discord-adapter/src/test/java/.../discord/schema/DiscordAccountLinkMapperTest.java`
- modify `platform/discord-adapter/src/main/java/.../discord/DatabaseDiscordAccountLinkStore.java`
- test create `platform/discord-adapter/src/test/java/.../discord/DatabaseDiscordAccountLinkStoreTest.java`

**Interfaces produced:**
```java
// DatabaseDiscordAccountLinkStore — constructor unchanged; internal resolution changes
private @NotNull DiscordAccountLinkMapper mapper(@NotNull SqlSessionWrapper wrapper);
```

- [ ] Write the failing test — the store against a real database, which nothing covered before:
```java
class DatabaseDiscordAccountLinkStoreTest extends AbstractDiscordDatabaseTest {

    private static final long DISCORD_ID = 123456789012345678L;

    private Database database;
    private DatabaseDiscordAccountLinkStore store;

    @BeforeEach
    void setUp() throws Exception {
        this.database = databaseFor(freshSchema());
        DiscordSchemaMigrator.migrate(this.database, Logger.getLogger("store-test"));
        this.store = new DatabaseDiscordAccountLinkStore(this.database, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    @Test
    @DisplayName("a link round-trips under both keys")
    void linkRoundTrips() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        Assertions.assertEquals(Optional.of(DISCORD_ID), this.store.discordIdFor(player));
        Assertions.assertEquals(Optional.of(player), this.store.playerFor(DISCORD_ID));
    }

    @Test
    @DisplayName("an unlinked player and an unlinked Discord id both resolve to empty")
    void absentLinksResolveToEmpty() {
        Assertions.assertEquals(Optional.empty(), this.store.discordIdFor(UUID.randomUUID()));
        Assertions.assertEquals(Optional.empty(), this.store.playerFor(DISCORD_ID));
    }

    @Test
    @DisplayName("link replaces a Discord id held by another player")
    void linkReplacesTheDiscordIdSide() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        this.store.link(first, DISCORD_ID);

        // Against the real unique index: without clearing both sides this fails on the constraint.
        this.store.link(second, DISCORD_ID);

        Assertions.assertEquals(Optional.empty(), this.store.discordIdFor(first));
        Assertions.assertEquals(Optional.of(second), this.store.playerFor(DISCORD_ID));
    }

    @Test
    @DisplayName("link replaces a player's previous Discord id")
    void linkReplacesThePlayerSide() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        this.store.link(player, DISCORD_ID + 1);

        Assertions.assertEquals(Optional.empty(), this.store.playerFor(DISCORD_ID));
        Assertions.assertEquals(Optional.of(DISCORD_ID + 1), this.store.discordIdFor(player));
    }

    @Test
    @DisplayName("unlink reports whether a row existed")
    void unlinkReportsWhetherARowExisted() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        Assertions.assertTrue(this.store.unlink(player));
        Assertions.assertFalse(this.store.unlink(player));
    }

    @Test
    @DisplayName("repeated use does not fail on duplicate mapper registration")
    void survivesRepeatedUse() {
        // addMapper throws if the type is already bound, and the store is used many times per session.
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            this.store.link(player, DISCORD_ID + i);
            Assertions.assertEquals(Optional.of(DISCORD_ID + i), this.store.discordIdFor(player));
        }
    }
}
```
- [ ] Run `./gradlew :platform:discord-adapter:test --tests "*DatabaseDiscordAccountLinkStoreTest"` —
      expect FAIL: `DiscordSchemaMigrator.migrate` creates the table, but the store still calls the
      `core` accessor, so the schema the test migrates and the mapper the store uses disagree; once the
      `core` types are deleted it fails to compile.
- [ ] Implement: `git mv` the three persistence types into
      `platform/discord-adapter/.../discord/schema/`, change their `package` declarations and the mapper's
      import of the entity. Update the entity's javadoc — the "lives in core because a module cannot own a
      migration" paragraph is now false and is replaced by a note that the module owns this table.
- [ ] Implement: delete core's V2 script, remove the `MigrationStep(2, …)` entry, the
      `discordAccountLinkMapper()` declaration and override, the `addMapper` line, and the
      `TRUNCATE TABLE DiscordAccountLink` line.
- [ ] Implement: in `DatabaseDiscordAccountLinkStore`, replace each
      `wrapper.discordAccountLinkMapper()` with `mapper(wrapper)`, where:
```java
private @NotNull DiscordAccountLinkMapper mapper(@NotNull SqlSessionWrapper wrapper) {
    Configuration configuration = wrapper.session().getConfiguration();
    // hasMapper first: addMapper throws when the type is already bound, and this runs on every call.
    if (!configuration.hasMapper(MariaDiscordAccountLinkMapper.class)) {
        configuration.addMapper(MariaDiscordAccountLinkMapper.class);
    }
    return wrapper.session().getMapper(MariaDiscordAccountLinkMapper.class);
}
```
- [ ] Implement: move `DiscordAccountLinkMapperTest` into the adapter, rebase it on
      `AbstractDiscordDatabaseTest` + `DiscordSchemaMigrator` instead of core's fixture, and resolve its
      mapper the same way the store does. Assertions stay as they are.
- [ ] Run `./gradlew :platform:discord-adapter:test` and `./gradlew :core:test` — expect PASS for both.
- [ ] Verify `core` is Discord-free: `grep -ri discord core/src` must return nothing.
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 4: retarget `core`'s schema-upgrade test

**Files:** modify `core/src/test/java/.../core/database/SchemaUpgradeTest.java`

Its subject is core's migrator, which still exists and still deserves cover; only the V2-specific case is
stale now that core's chain is one step again.

- [ ] Write the failing test — replace `appliesV2OnTopOfADatabaseAlreadyAtV1` with:
```java
@Test
@DisplayName("a database already at V1 needs no further core migration")
void aDatabaseAtV1IsUpToDate() throws Exception {
    MariaSchemaMigrator.migrate(jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS, List.of(V1), LOGGER);
    Assertions.assertEquals(List.of(1), appliedVersions());

    MariaSchemaMigrator.migrate(jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS,
            MariaSchemaMigrator.defaultMigrations(), LOGGER);

    Assertions.assertEquals(List.of(1), appliedVersions());
    // Core owns no Discord schema: that table now belongs to the Discord adapter's own migrator.
    Assertions.assertFalse(tableExists("DiscordAccountLink"));
}
```
      Keep `reRunningTheChainChangesNothing` (updating its expectation from `List.of(1, 2)` to
      `List.of(1)`) and `refusesADatabaseFromTheFuture` unchanged.
- [ ] Run `./gradlew :core:test --tests "*SchemaUpgradeTest"` — expect FAIL before Task 3's deletions are
      in place and PASS after; if Task 3 is already committed, expect the *old* assertions
      (`List.of(1, 2)`) to fail, which is the signal that this task is needed.
- [ ] Implement the edits above.
- [ ] Run the same command — expect PASS (3 tests).
- [ ] Run `./gradlew build`
- [ ] Commit

---

## Task 5: documentation

**Files:** modify `CLAUDE.md`, `docs/superpowers/specs/2026-07-30-embedded-discord-linking-design.md`,
`docs/superpowers/specs/2026-07-30-module-owned-discord-schema-design.md`

- [ ] Mark the new spec implemented.
- [ ] In the embedded-linking spec: strike the "A Discord-side table lives in `core`" known limitation and
      the "The link table lives in `core`, as migration V2" rationale, pointing both at the new spec. Note
      that the claim "a feature module cannot own a migration today" was too strong — it cannot join
      core's hardcoded lists, but it can run its own DDL and version table over the host's pool.
- [ ] In `CLAUDE.md`:
      - "Persistence layer": remove `DiscordAccountLink` from the schema list, remove
        `DiscordAccountLinkEntity`/`DiscordAccountLinkMapper` from the entity and mapper lists, and revert
        the migrator note to a single step, `V1__maria_initial_schema.sql`.
      - "Module architecture" → `platform:discord-adapter`: state that it owns its own schema
        (`sql/discord/V1__discord_account_link.sql`), its own migrator and version table
        (`discord_schema_version`), and its own entity/mapper, while reusing the host's pool, session
        factory and `UUID` type handler through `plugin.database()`.
      - "Discord adapter": add how the mapper is registered (`session().getConfiguration()`, guarded by
        `hasMapper`) and that migration failure fails module startup. Remove
        `DatabaseDiscordAccountLinkStore` from the "Untested by automated tests" list — it is now covered.
      - "Module system": note that a module *can* own a migration by running its own DDL and version table
        over the host's `Database`, which is what the Discord adapter does; what it cannot do is add to
        `MariaSchemaMigrator.DEFAULT_MIGRATIONS` or `MariaDatabase`'s mapper list.
      - "Build & run" and "Testing gotchas": `:platform:discord-adapter:test` now needs a running Docker
        daemon, like `:core:test`.
      - "Testing gotchas": update the baseline counts from the actual fresh run.
      - "Current state": drop the "link table is Discord-shaped schema in `core`" bullet.
- [ ] Run `./gradlew build` and `./gradlew test`; count with a `*.xml` glob, not `TEST-*.xml`.
- [ ] Commit
