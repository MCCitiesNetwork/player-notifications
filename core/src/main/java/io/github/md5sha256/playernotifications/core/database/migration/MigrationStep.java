package io.github.md5sha256.playernotifications.core.database.migration;

/**
 * A single ordered schema migration: its {@code version} number, a human-readable
 * {@code description}, and the classpath-relative {@code resourcePath} of the SQL
 * script to execute.
 */
public record MigrationStep(int version, String description, String resourcePath) {

}
