package io.github.md5sha256.playernotifications.discord.schema;

import io.github.md5sha256.playernotifications.core.database.Database;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Convenience base for this package's database tests. All state lives in {@link DiscordSchemaTestSupport},
 * which is public so tests in other packages share the same container; these are thin delegates so a test
 * class in this package can call them unqualified.
 */
abstract class AbstractDiscordDatabaseTest {

    protected static final Logger LOGGER = DiscordSchemaTestSupport.LOGGER;

    protected static String freshSchema() throws SQLException {
        return DiscordSchemaTestSupport.freshSchema();
    }

    protected static Database databaseFor(String schema) {
        return DiscordSchemaTestSupport.databaseFor(schema);
    }

    protected static boolean tableExists(String schema, String table) throws SQLException {
        return DiscordSchemaTestSupport.tableExists(schema, table);
    }

    protected static List<Integer> appliedVersions(String schema) throws SQLException {
        return DiscordSchemaTestSupport.appliedVersions(schema);
    }

    protected static List<Integer> coreAppliedVersions(String schema) throws SQLException {
        return DiscordSchemaTestSupport.coreAppliedVersions(schema);
    }

    protected static void execute(String schema, String sql) throws SQLException {
        DiscordSchemaTestSupport.execute(schema, sql);
    }
}
