package io.github.md5sha256.playernotifications.api.render;

import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves the set of media a player wants to receive rendered notifications through. Set-valued:
 * multi-medium delivery (e.g. {@code chat} + {@code discord}) is the first-class case, not an edge
 * case.
 */
public interface NotificationPreferences {

    /**
     * Returns the media keys the given player currently prefers, with no notification-category context.
     * Implementations are expected to fall back to some configured default set when the player has
     * expressed no preference, so a player is never silently cut off from all notifications.
     */
    @NotNull Set<String> preferredMedia(@NotNull UUID player);

    /**
     * Returns the media keys the given player prefers for the given notification dataType. The default
     * implementation ignores the dataType and delegates to {@link #preferredMedia(UUID)}, so existing
     * single-argument implementations (including lambdas) keep compiling unchanged.
     */
    @NotNull
    default Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType) {
        return preferredMedia(player);
    }

}
