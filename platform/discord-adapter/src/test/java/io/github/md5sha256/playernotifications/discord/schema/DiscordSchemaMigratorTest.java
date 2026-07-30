package io.github.md5sha256.playernotifications.discord.schema;

import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.maria.MariaSchemaMigrator;
import io.github.md5sha256.playernotifications.core.database.migration.MigrationStep;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

class DiscordSchemaMigratorTest extends AbstractDiscordDatabaseTest {

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
    @DisplayName("re-running the chain applies nothing")
    void isIdempotent() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            DiscordSchemaMigrator.migrate(database, LOGGER);
            DiscordSchemaMigrator.migrate(database, LOGGER);

            // Re-inserting version 1 would fail on the primary key long before this assertion, so this
            // guards the "already applied" skip itself.
            Assertions.assertEquals(List.of(1), appliedVersions(schema));
        }
    }

    @Test
    @DisplayName("a recorded version above the known chain is refused")
    void refusesADowngrade() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            DiscordSchemaMigrator.migrate(database, LOGGER);
            execute(schema, "INSERT INTO " + DiscordSchemaMigrator.VERSION_TABLE
                    + " (version, description) VALUES (99, 'from the future')");

            // An operator who downgrades the module must be told, not silently left on a schema whose
            // columns the running code does not know about.
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

            Assertions.assertTrue(tableExists(schema, "Notification"));
            Assertions.assertTrue(tableExists(schema, "DiscordAccountLink"));
            // Two separate tables, two chains, both legitimately starting at 1 with no collision. Core's
            // expectation is derived from its own chain rather than hardcoded, so this asserts the
            // independence property itself and not core's current length.
            Assertions.assertEquals(List.of(1), appliedVersions(schema));
            Assertions.assertEquals(coreVersions(), coreAppliedVersions(schema));
        }
    }

    @Test
    @DisplayName("migrating in the other order also works")
    void orderAgainstCoreDoesNotMatter() throws Exception {
        String schema = freshSchema();
        try (Database database = databaseFor(schema)) {
            DiscordSchemaMigrator.migrate(database, LOGGER);
            database.initializeSchema(Path.of("sql/migrations"));

            Assertions.assertEquals(List.of(1), appliedVersions(schema));
            Assertions.assertEquals(coreVersions(), coreAppliedVersions(schema));
        }
    }

    /** Core's own chain, so this class asserts chain independence without pinning core's length. */
    private static List<Integer> coreVersions() {
        return MariaSchemaMigrator.defaultMigrations().stream().map(MigrationStep::version).toList();
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

    @Test
    @DisplayName("an existing DiscordAccountLink table is adopted rather than failing")
    void adoptsAnExistingTable() throws Exception {
        String schema = freshSchema();
        // This is the documented upgrade path for a database that ran the earlier core-owned V2: the table
        // already exists, and the module's own migrator must take ownership of it without erroring.
        execute(schema, """
                CREATE TABLE DiscordAccountLink
                (
                    playerUuid BINARY(16) NOT NULL PRIMARY KEY,
                    discordId  BIGINT     NOT NULL,
                    linkedAt   DATETIME   NOT NULL,
                    UNIQUE KEY uk_discord_account_link_discord_id (discordId)
                )
                """);

        try (Database database = databaseFor(schema)) {
            Assertions.assertDoesNotThrow(() -> DiscordSchemaMigrator.migrate(database, LOGGER));
            Assertions.assertEquals(List.of(1), appliedVersions(schema));
        }
    }
}
