package io.github.md5sha256.playernotifications.core;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Connection settings for the notifications database, deserialized from
 * configuration via Configurate.
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
@ConfigSerializable
public record DatabaseSettings(
        @Setting("url")
        @Required
        @NotNull String url,
        @Setting("username")
        @NotNull String username,
        @Setting("password")
        @NotNull String password
) {
}
