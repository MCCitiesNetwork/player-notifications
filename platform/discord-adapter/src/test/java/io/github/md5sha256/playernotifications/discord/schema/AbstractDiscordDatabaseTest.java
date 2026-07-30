package io.github.md5sha256.playernotifications.discord.schema;

import io.github.md5sha256.playernotifications.core.DatabaseSettings;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.maria.MariaDatabase;
import org.testcontainers.mariadb.MariaDBContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Shared setup for the adapter's database tests. Boots one throwaway MariaDB container for the whole run
 * and hands each test its own empty schema.
 *
 * <p><strong>Per-test schemas, not a truncated shared one.</strong> These tests assert on DDL and on
 * migration bookkeeping, so a case has to start with no tables at all — the pattern {@code core}'s
 * {@code AbstractDatabaseTest} uses (migrate once, truncate between tests) cannot express that.
 *
 * <p>Needs a running Docker daemon.
 */
abstract class AbstractDiscordDatabaseTest {

    private static final String ROOT_USER = "root";
    private static final String ROOT_PASSWORD = "rootpass";

    protected static final Logger LOGGER = Logger.getLogger("discord-schema-test");

    @SuppressWarnings("resource")
    protected static final MariaDBContainer CONTAINER = new MariaDBContainer("mariadb:11.7")
            .withEnv("MARIADB_ROOT_PASSWORD", ROOT_PASSWORD);

    static {
        CONTAINER.start();
    }

    /** Creates a new empty schema and returns its name. */
    protected static String freshSchema() throws SQLException {
        String schema = "discord_test_" + Long.toHexString(System.nanoTime());
        try (Connection connection = adminConnection(null);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema);
        }
        return schema;
    }

    /**
     * A host {@link Database} over the given schema, built exactly as the plugin builds it — the point
     * being that the adapter reuses this pool, session factory and type-handler registry rather than
     * opening its own.
     */
    protected static Database databaseFor(String schema) {
        // MariaDatabase prepends "jdbc:" to settings.url(), so pass it without the prefix.
        String url = baseUrl(schema).substring("jdbc:".length());
        return new MariaDatabase(new DatabaseSettings(url, ROOT_USER, ROOT_PASSWORD), LOGGER);
    }

    protected static boolean tableExists(String schema, String table) throws SQLException {
        try (Connection connection = adminConnection(schema);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables"
                             + " WHERE table_schema = '" + schema + "' AND table_name = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    /** Versions recorded in the adapter's own version table, in order. */
    protected static List<Integer> appliedVersions(String schema) throws SQLException {
        return versionsFrom(schema, "discord_schema_version");
    }

    /** Versions recorded in core's version table, to prove the two chains are independent. */
    protected static List<Integer> coreAppliedVersions(String schema) throws SQLException {
        return versionsFrom(schema, "schema_version");
    }

    protected static void execute(String schema, String sql) throws SQLException {
        try (Connection connection = adminConnection(schema);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<Integer> versionsFrom(String schema, String table) throws SQLException {
        List<Integer> versions = new ArrayList<>();
        try (Connection connection = adminConnection(schema);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version FROM " + table + " ORDER BY version")) {
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
        }
        return versions;
    }

    private static Connection adminConnection(String schema) throws SQLException {
        return DriverManager.getConnection(baseUrl(schema), ROOT_USER, ROOT_PASSWORD);
    }

    private static String baseUrl(String schema) {
        return "jdbc:mariadb://" + CONTAINER.getHost() + ":" + CONTAINER.getFirstMappedPort()
                + "/" + (schema == null ? "" : schema);
    }
}
