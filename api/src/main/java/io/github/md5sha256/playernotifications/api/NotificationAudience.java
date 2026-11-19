package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who a send reaches, resolved by the {@link NotificationService} implementation at send time.
 */
public sealed interface NotificationAudience {

    /** Exactly these players. */
    record Players(@NotNull Set<UUID> players) implements NotificationAudience {
        public Players {
            players = Set.copyOf(players);
        }
    }

    /**
     * Every player holding {@code permissions}, combined by {@code chain}. {@code includeOffline} also
     * reaches players who are not connected, which needs a permission backend able to answer for them;
     * without one the send fails with {@link OfflineLookupUnavailableException}.
     */
    record WithPermissions(@NotNull List<String> permissions, @NotNull PermissionChain chain,
                           boolean includeOffline) implements NotificationAudience {
        /**
         * Rejects an empty or blank permission list: offline, it would enumerate every player the
         * permission backend has ever seen. A caller bug, so it throws here rather than in a future.
         */
        public WithPermissions {
            permissions = List.copyOf(permissions);
            if (permissions.isEmpty()) {
                throw new IllegalArgumentException("At least one permission is required");
            }
            for (String permission : permissions) {
                if (permission.isBlank()) {
                    throw new IllegalArgumentException("Permissions must not be blank");
                }
            }
        }
    }

    static @NotNull NotificationAudience players(@NotNull Collection<UUID> players) {
        return new Players(Set.copyOf(players));
    }

    static @NotNull NotificationAudience withAnyPermission(boolean includeOffline, @NotNull String... permissions) {
        return new WithPermissions(List.of(permissions), PermissionChain.OR, includeOffline);
    }

    static @NotNull NotificationAudience withAllPermissions(boolean includeOffline, @NotNull String... permissions) {
        return new WithPermissions(List.of(permissions), PermissionChain.AND, includeOffline);
    }
}
