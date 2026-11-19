package io.github.md5sha256.playernotifications.paper.broadcast;

import io.github.md5sha256.playernotifications.api.NotificationAudience;
import io.github.md5sha256.playernotifications.api.OfflineLookupUnavailableException;
import io.github.md5sha256.playernotifications.core.AudienceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the API's permission audiences through the same online and offline audiences
 * {@code /broadcast --perm [--offline]} uses.
 */
public final class PermissionAudienceResolver implements AudienceResolver {

    private final BroadcastAudience online;
    private final @Nullable BroadcastAudience offline;

    /**
     * @param online  online permission holders; marshals onto the main thread itself
     * @param offline offline permission holders, or {@code null} when no backend can answer (no LuckPerms)
     */
    public PermissionAudienceResolver(@NotNull BroadcastAudience online, @Nullable BroadcastAudience offline) {
        this.online = online;
        this.offline = offline;
    }

    @Override
    public @NotNull Set<UUID> resolve(@NotNull NotificationAudience.WithPermissions audience) {
        // The api enum and the command's enum carry the same two constants; the command's stays so the
        // broadcast tests are not churned for a rename.
        BroadcastArguments.Chain chain = BroadcastArguments.Chain.valueOf(audience.chain().name());
        Set<UUID> players = new LinkedHashSet<>(this.online.resolve(audience.permissions(), chain));
        if (audience.includeOffline()) {
            if (this.offline == null) {
                throw new OfflineLookupUnavailableException("Offline permission lookup requires LuckPerms");
            }
            players.addAll(this.offline.resolve(audience.permissions(), chain));
        }
        return players;
    }
}
