package io.github.md5sha256.playernotifications.discord.schema;

import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

/**
 * Checks the shared fixture itself. Everything in this package depends on per-test schema isolation and on
 * the two {@code SqlSession} seams the adapter reuses, so a silent regression in either would make later
 * tests assert nothing.
 */
class AbstractDiscordDatabaseTestSelfTest extends AbstractDiscordDatabaseTest {

    @Test
    @DisplayName("each schema starts empty and is independent of the others")
    void handsOutEmptyIndependentSchemas() throws Exception {
        String first = freshSchema();
        String second = freshSchema();
        Assertions.assertNotEquals(first, second);

        try (Database database = databaseFor(first);
             SqlSessionWrapper wrapper = database.openSession(true);
             Statement statement = wrapper.session().getConnection().createStatement()) {
            statement.execute("CREATE TABLE marker (id INT PRIMARY KEY)");
        }

        // Migration tests assert on which tables exist, so leakage between schemas would be fatal to them.
        Assertions.assertTrue(tableExists(first, "marker"));
        Assertions.assertFalse(tableExists(second, "marker"));
    }

    @Test
    @DisplayName("the host session exposes the connection and configuration the adapter relies on")
    void exposesTheSeamsTheAdapterUses() throws Exception {
        // If either stops being reachable, the whole "reuse the host's connection logic" approach is void.
        try (Database database = databaseFor(freshSchema());
             SqlSessionWrapper wrapper = database.openSession(true)) {
            Assertions.assertNotNull(wrapper.session().getConnection());
            Assertions.assertNotNull(wrapper.session().getConfiguration());
        }
    }

    @Test
    @DisplayName("the host configuration already carries the UUID type handler")
    void reusesTheHostsUuidTypeHandler() throws Exception {
        // This is why the adapter's mapper can take a UUID parameter without registering anything.
        try (Database database = databaseFor(freshSchema());
             SqlSessionWrapper wrapper = database.openSession(true)) {
            Assertions.assertTrue(wrapper.session().getConfiguration()
                    .getTypeHandlerRegistry().hasTypeHandler(java.util.UUID.class));
        }
    }
}
