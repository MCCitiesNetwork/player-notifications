package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.database.maria.MariaSchemaMigrator;
import io.github.md5sha256.playernotifications.core.database.migration.MigrationStep;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Covers migrating a database that is <em>already at an earlier version</em> up to the current one.
 *
 * <p>{@link AbstractDatabaseTest} migrates an empty schema with the whole default chain in one call, so
 * it never exercises the state every real dev and production database is in: V1 already recorded in
 * {@code schema_version}, so the migrator must apply only what follows it. This class therefore owns its
 * own throwaway schemas rather than sharing the migrated fixture.
 */
class SchemaUpgradeTest extends AbstractDatabaseTest {

    private static final String ROOT_USER = "root";
    private static final String ROOT_PASSWORD = "rootpass";
    private static final Logger LOGGER = Logger.getLogger("test");
    private static final Path MIGRATIONS = Path.of("sql/migrations");

    private static final MigrationStep V1 =
            new MigrationStep(1, "initial schema", "V1__maria_initial_schema.sql");

    /** A schema name unique to each test, so cases cannot see one another's DDL. */
    private String schema;

    @BeforeEach
    void createEmptySchema() throws SQLException {
        this.schema = "upgrade_test_" + Long.toHexString(System.nanoTime());
        try (Connection connection = adminConnection(null);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + this.schema);
        }
    }

    @Test
    @DisplayName("a database already at V1 needs no further core migration")
    void aDatabaseAtV1IsUpToDate() throws Exception {
        // Bring the schema to V1 only, as an existing database would already be.
        MariaSchemaMigrator.migrate(jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS, List.of(V1), LOGGER);
        Assertions.assertEquals(List.of(1), appliedVersions());

        // The full chain must then be a no-op rather than re-running V1.
        MariaSchemaMigrator.migrate(jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS,
                MariaSchemaMigrator.defaultMigrations(), LOGGER);

        Assertions.assertEquals(List.of(1), appliedVersions());
        // Core owns no Discord schema: that table belongs to the Discord adapter's own migrator, tracked
        // in its own discord_schema_version chain. If this ever passes, core has grown a module's table.
        Assertions.assertFalse(tableExists("DiscordAccountLink"));
    }

    @Test
    @DisplayName("re-running the default chain on an up-to-date database is a no-op")
    void reRunningTheChainChangesNothing() throws Exception {
        MariaSchemaMigrator.migrate(jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS,
                MariaSchemaMigrator.defaultMigrations(), LOGGER);
        MariaSchemaMigrator.migrate(jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS,
                MariaSchemaMigrator.defaultMigrations(), LOGGER);

        // A second application would violate schema_version's primary key long before this assertion, so
        // this also guards the "already applied" skip itself.
        Assertions.assertEquals(List.of(1), appliedVersions());
    }

    @Test
    @DisplayName("a database newer than the supported chain is refused")
    void refusesADatabaseFromTheFuture() throws Exception {
        MariaSchemaMigrator.migrate(jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS,
                MariaSchemaMigrator.defaultMigrations(), LOGGER);
        try (Connection connection = adminConnection(this.schema);
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO schema_version (version, description) VALUES (99, 'from the future')");
        }

        // An operator who downgrades the plugin must be told, not silently left on a schema whose columns
        // the running code does not know about.
        Assertions.assertThrows(SQLException.class, () -> MariaSchemaMigrator.migrate(
                jdbcUrl(), ROOT_USER, ROOT_PASSWORD, MIGRATIONS,
                MariaSchemaMigrator.defaultMigrations(), LOGGER));
    }

    private String jdbcUrl() {
        return "jdbc:mariadb://" + CONTAINER.getHost() + ":" + CONTAINER.getFirstMappedPort()
                + "/" + this.schema;
    }

    private Connection adminConnection(String database) throws SQLException {
        String url = "jdbc:mariadb://" + CONTAINER.getHost() + ":" + CONTAINER.getFirstMappedPort()
                + "/" + (database == null ? "" : database);
        return DriverManager.getConnection(url, ROOT_USER, ROOT_PASSWORD);
    }

    private List<Integer> appliedVersions() throws SQLException {
        List<Integer> versions = new ArrayList<>();
        try (Connection connection = adminConnection(this.schema);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
        }
        return versions;
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection connection = adminConnection(this.schema);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables"
                             + " WHERE table_schema = '" + this.schema + "'"
                             + " AND table_name = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
