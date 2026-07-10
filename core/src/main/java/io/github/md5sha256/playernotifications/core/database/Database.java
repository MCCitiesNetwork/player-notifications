package io.github.md5sha256.playernotifications.core.database;

import org.apache.ibatis.session.ExecutorType;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Vendor-neutral handle to the notifications datastore.
 *
 * <p>Implementations own the underlying connection pool and the MyBatis
 * {@code SqlSessionFactory}; callers obtain a short-lived
 * {@link SqlSessionWrapper} per unit of work and close it (ideally via
 * try-with-resources) when done.
 */
public interface Database extends Closeable {

    /**
     * Opens a new session with the default executor type and auto-commit disabled.
     * Callers must {@link org.apache.ibatis.session.SqlSession#commit() commit}
     * explicitly to persist writes.
     */
    @NotNull SqlSessionWrapper openSession();

    @NotNull SqlSessionWrapper openSession(boolean autoCommit);

    @NotNull SqlSessionWrapper openSession(@NotNull ExecutorType executorType, boolean autoCommit);

    /**
     * Applies any pending schema migrations found under the given resource
     * directory. Idempotent: already-applied versions are skipped.
     *
     * @param schemaFilesDirectory classpath-relative directory holding the
     *                             {@code V*.sql} migration scripts
     */
    void initializeSchema(@NotNull Path schemaFilesDirectory) throws IOException, SQLException;

}
