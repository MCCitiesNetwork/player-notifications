package io.github.md5sha256.playernotifications.discord.schema;

import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Applies this module's own schema migrations, tracking them in its own {@value #VERSION_TABLE} table.
 *
 * <p><strong>The module owns its schema; the host owns the connection.</strong> Migrations run over a
 * {@link Connection} borrowed from the host's pool via {@link SqlSessionWrapper#session()}, so there is no
 * second pool competing for the operator's {@code max_connections} and no second copy of the database
 * credentials. But the version chain, the scripts and the DDL are entirely this module's: {@code core}
 * contains no Discord-shaped table or migration.
 *
 * <p>A separate version table rather than a namespace column in core's {@code schema_version}, because the
 * latter would mean changing {@code core} in service of a module. The two chains are independent and both
 * legitimately start at V1.
 *
 * <p>Scripts are loaded through <em>this class's</em> class loader, since a module's resources live in the
 * module jar and core's migrator — which uses its own loader — cannot see them.
 */
public final class DiscordSchemaMigrator {

    /** This module's version-tracking table, named for its owner so schema state is self-describing. */
    public static final String VERSION_TABLE = "discord_schema_version";

    private static final List<DiscordMigrationStep> DEFAULT_MIGRATIONS = List.of(
            new DiscordMigrationStep(1, "discord account link", "sql/discord/V1__discord_account_link.sql")
    );

    private static final String BOOTSTRAP_DDL = """
            CREATE TABLE IF NOT EXISTS %s
            (
                version     INT          NOT NULL PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                applied_at  DATETIME     NOT NULL DEFAULT NOW()
            )
            """.formatted(VERSION_TABLE);

    private static final String SELECT_VERSION =
            "SELECT COALESCE(MAX(version), 0) FROM " + VERSION_TABLE;

    private static final String INSERT_VERSION =
            "INSERT INTO " + VERSION_TABLE + " (version, description) VALUES (?, ?)";

    private DiscordSchemaMigrator() {
    }

    public static @NotNull List<DiscordMigrationStep> defaultMigrations() {
        return DEFAULT_MIGRATIONS;
    }

    /** Applies this module's default chain. Idempotent: already-applied versions are skipped. */
    public static void migrate(@NotNull Database database, @NotNull Logger logger)
            throws IOException, SQLException {
        migrate(database, DEFAULT_MIGRATIONS, logger);
    }

    /** Package-private seam so a test can drive an arbitrary chain. */
    static void migrate(@NotNull Database database,
                        @NotNull List<DiscordMigrationStep> steps,
                        @NotNull Logger logger) throws IOException, SQLException {
        // Auto-commit: DDL is implicitly committing in MariaDB anyway, so a transaction around it would be
        // a fiction, and each version row should land as soon as its script has run.
        try (SqlSessionWrapper wrapper = database.openSession(true)) {
            Connection connection = wrapper.session().getConnection();

            try (Statement statement = connection.createStatement()) {
                statement.execute(BOOTSTRAP_DDL);
            }

            int currentVersion;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(SELECT_VERSION)) {
                rs.next();
                currentVersion = rs.getInt(1);
            }

            int maxSupportedVersion = steps.stream()
                    .mapToInt(DiscordMigrationStep::version)
                    .max()
                    .orElse(0);
            if (currentVersion > maxSupportedVersion) {
                throw new SQLException("Discord adapter schema version " + currentVersion
                        + " is newer than the maximum supported version " + maxSupportedVersion
                        + ". Please update the Discord adapter module.");
            }

            for (DiscordMigrationStep step : steps) {
                if (step.version() <= currentVersion) {
                    continue;
                }
                applyStep(connection, step);
                try (PreparedStatement ps = connection.prepareStatement(INSERT_VERSION)) {
                    ps.setInt(1, step.version());
                    ps.setString(2, step.description());
                    ps.executeUpdate();
                }
                logger.info("Applied Discord adapter migration V" + step.version() + ": "
                        + step.description());
            }
        }
    }

    /**
     * Executes one script, statement by statement.
     *
     * <p>Split on {@code ;} and executed individually rather than as one multi-statement batch, because
     * this borrows the host's pool and must not assume {@code allowMultiQueries=true} is on its URL — core's
     * migrator sets that flag on a pool it builds itself. The cost is that a statement containing an
     * embedded {@code ;}, such as a trigger body, cannot be expressed in these scripts.
     *
     * <p>{@code --} line comments are stripped <em>before</em> splitting. Not cosmetic: a {@code ;} inside a
     * comment would otherwise split the script mid-sentence and hand the driver a syntax error, which is
     * precisely what happened the first time this ran against a script whose header comment mentioned
     * {@code ";"}.
     */
    private static void applyStep(@NotNull Connection connection, @NotNull DiscordMigrationStep step)
            throws IOException, SQLException {
        String script = stripLineComments(loadResource(step.resourcePath()));
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    /** Drops whole-line {@code --} comments. MariaDB requires {@code --} to be followed by whitespace. */
    private static @NotNull String stripLineComments(@NotNull String script) {
        return script.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static @NotNull String loadResource(@NotNull String resourcePath) throws IOException {
        // This module's class loader: the script is in the module jar, invisible to core's loader.
        try (InputStream is = DiscordSchemaMigrator.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Discord adapter migration resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
