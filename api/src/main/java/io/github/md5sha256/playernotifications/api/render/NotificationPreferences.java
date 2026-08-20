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
     * The reserved medium encoding an explicit <em>silence</em> of one {@code dataType} — a standing
     * "never push this type to me", distinct from the temporary, player-level mute of
     * {@link #isMuted(UUID)}. Stored as a row rather than as zero rows, because zero rows already
     * means "has expressed no preference" and the two must stay distinguishable. It is not a
     * registered sink: {@link RenderingProcessor} filters it out and delivers nothing, leaving the
     * notification unread in the player's inbox.
     */
    String SILENCED_MEDIUM = "none";

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

    /**
     * Whether the player has muted every notification. A mute is a temporary, player-level suspension
     * of delivery: a muted player is still enqueued to, and still reads their notifications through the
     * inbox; nothing is pushed to them. Orthogonal to the per-{@code dataType} media set — which is
     * where a lasting, per-type <em>silence</em> is expressed ({@link #SILENCED_MEDIUM}) — so unmuting
     * restores exactly the preferences the player had before, since a mute never touches a preference
     * row. The default implementation always returns
     * {@code false}, so existing implementations (including lambdas) keep compiling unchanged.
     */
    default boolean isMuted(@NotNull UUID player) {
        return false;
    }

}
