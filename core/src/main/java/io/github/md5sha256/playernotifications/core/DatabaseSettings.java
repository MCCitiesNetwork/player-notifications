package io.github.md5sha256.playernotifications.core;

import org.jetbrains.annotations.NotNull;

/**
 * Connection settings for the notifications database.
 *
 * <p>The {@link #url()} is stored without the {@code jdbc:} scheme prefix (for
 * example {@code mariadb://localhost:3306/player_notifications}); the vendor
 * {@link io.github.md5sha256.playernotifications.core.database.Database Database}
 * implementation prepends it when opening connections.
 *
 * @param url      the JDBC url, without the leading {@code jdbc:} prefix
 * @param username the database username
 * @param password the database password
 */
public record DatabaseSettings(
        @NotNull String url,
        @NotNull String username,
        @NotNull String password
) {
}
