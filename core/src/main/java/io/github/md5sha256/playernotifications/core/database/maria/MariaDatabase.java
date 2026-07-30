package io.github.md5sha256.playernotifications.core.database.maria;

import io.github.md5sha256.playernotifications.core.DatabaseSettings;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.maria.mapper.MariaDiscordAccountLinkMapper;
import io.github.md5sha256.playernotifications.core.database.maria.mapper.MariaNotificationMapper;
import io.github.md5sha256.playernotifications.core.database.maria.mapper.MariaNotificationTargetMapper;
import io.github.md5sha256.playernotifications.core.database.maria.mapper.MariaPlayerNotificationPreferenceMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * MariaDB implementation of {@link Database}. Owns a pooled data source and the
 * MyBatis {@link SqlSessionFactory}, registers the MariaDB mappers and the
 * {@link UUIDAsBin16Handler} UUID type handler, and delegates schema migration
 * to {@link MariaSchemaMigrator}.
 */
public class MariaDatabase implements Database {

    private final DatabaseSettings settings;
    private final PooledDataSource dataSource;
    private final SqlSessionFactory sessionFactory;
    private final Logger logger;

    public MariaDatabase(@NotNull DatabaseSettings settings, @NotNull Logger logger) {
        this.settings = settings;
        this.dataSource = new PooledDataSource("org.mariadb.jdbc.Driver", "jdbc:" + settings.url(),
                settings.username(), settings.password());
        this.dataSource.setPoolPingEnabled(true);
        this.dataSource.setPoolPingQuery("SELECT 1");
        this.dataSource.setPoolPingConnectionsNotUsedFor(600000);
        this.sessionFactory = buildSessionFactory(this.dataSource);
        this.logger = logger;
    }

    @Override
    public void close() {
        this.dataSource.forceCloseAll();
    }

    @NotNull
    private static SqlSessionFactory buildSessionFactory(@NotNull DataSource dataSource) {
        Environment environment = new Environment("production", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.OTHER, UUIDAsBin16Handler.class);
        configuration.addMapper(MariaNotificationMapper.class);
        configuration.addMapper(MariaNotificationTargetMapper.class);
        configuration.addMapper(MariaPlayerNotificationPreferenceMapper.class);
        configuration.addMapper(MariaDiscordAccountLinkMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    @Override
    public void initializeSchema(@NotNull Path schemaFilesDirectory) throws IOException, SQLException {
        MariaSchemaMigrator.migrate("jdbc:" + this.settings.url(), this.settings.username(), this.settings.password(),
                schemaFilesDirectory, MariaSchemaMigrator.defaultMigrations(), this.logger);
    }

    @Override
    public @NotNull SqlSessionWrapper openSession() {
        return new MariaSqlSession(this.sessionFactory.openSession());
    }

    @Override
    public @NotNull SqlSessionWrapper openSession(boolean autoCommit) {
        return new MariaSqlSession(this.sessionFactory.openSession(autoCommit));
    }

    @Override
    public @NotNull SqlSessionWrapper openSession(@NotNull ExecutorType executorType, boolean autoCommit) {
        return new MariaSqlSession(this.sessionFactory.openSession(executorType, autoCommit));
    }
}
