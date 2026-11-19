package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.NotificationAudience;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves a permission audience to players. Supplied by the host, because {@code core} cannot ask the
 * server about permissions. Blocking; always called off the main thread.
 */
@FunctionalInterface
public interface AudienceResolver {

    /**
     * @throws io.github.md5sha256.playernotifications.api.OfflineLookupUnavailableException if offline
     *         players were asked for and no backend can answer for them
     */
    @NotNull Set<UUID> resolve(@NotNull NotificationAudience.WithPermissions audience);
}
