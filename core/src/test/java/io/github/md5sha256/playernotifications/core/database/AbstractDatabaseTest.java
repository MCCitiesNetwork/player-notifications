package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.DatabaseSettings;
import io.github.md5sha256.playernotifications.core.DefaultNotificationService;
import io.github.md5sha256.playernotifications.core.database.maria.MariaDatabase;
import io.github.md5sha256.playernotifications.core.database.maria.MariaSchemaMigrator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.mariadb.MariaDBContainer;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Shared setup for database tests. Boots a single throwaway MariaDB container for
 * the whole test run, migrates the schema once (as root, to avoid privilege
 * issues), and truncates the data tables before every test so cases stay isolated.
 */
abstract class AbstractDatabaseTest {

    private static final String ROOT_PASSWORD = "rootpass";

    @SuppressWarnings("resource")
    protected static final MariaDBContainer CONTAINER = new MariaDBContainer("mariadb:11.7")
            .withEnv("MARIADB_ROOT_PASSWORD", ROOT_PASSWORD);

    static {
        CONTAINER.start();
    }

    protected static Database database;
    protected static DefaultNotificationService service;

    private static volatile boolean migrated;

    @BeforeAll
    static void initDatabase() throws IOException, SQLException {
        if (!migrated) {
            // Run migrations as root to avoid privilege issues with DDL statements.
            String baseJdbcUrl = CONTAINER.getJdbcUrl();
            MariaSchemaMigrator.migrate(baseJdbcUrl, "root", ROOT_PASSWORD,
                    Path.of("sql/migrations"), MariaSchemaMigrator.defaultMigrations(), Logger.getLogger("test"));
            migrated = true;
        }

        // MariaDatabase prepends "jdbc:" to settings.url(), so strip the jdbc: prefix.
        String url = CONTAINER.getJdbcUrl().substring("jdbc:".length());
        DatabaseSettings settings = new DatabaseSettings(url, CONTAINER.getUsername(), CONTAINER.getPassword());
        database = new MariaDatabase(settings, Logger.getLogger("test"));
        service = new DefaultNotificationService(database);
    }

    private static String truncateUrl;

    @BeforeEach
    void truncateTables() throws SQLException {
        if (truncateUrl == null) {
            String baseJdbcUrl = CONTAINER.getJdbcUrl();
            truncateUrl = baseJdbcUrl + (baseJdbcUrl.contains("?") ? "&" : "?") + "allowMultiQueries=true";
        }
        try (Connection conn = DriverManager.getConnection(truncateUrl, "root", ROOT_PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    SET FOREIGN_KEY_CHECKS = 0;
                    TRUNCATE TABLE Notification;
                    TRUNCATE TABLE NotificationTarget;
                    TRUNCATE TABLE PlayerNotificationPreference;
                    TRUNCATE TABLE DiscordAccountLink;
                    SET FOREIGN_KEY_CHECKS = 1;
                    """);
        }
    }
}
