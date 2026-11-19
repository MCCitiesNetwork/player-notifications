package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;

/**
 * An audience asked for offline players, but the server has no permission backend able to answer for a
 * player who is not connected (LuckPerms, today). Thrown into the returned future rather than degrading
 * to online-only silently, so a caller that wants that fallback takes it on purpose.
 */
public class OfflineLookupUnavailableException extends RuntimeException {

    public OfflineLookupUnavailableException(@NotNull String message) {
        super(message);
    }
}
