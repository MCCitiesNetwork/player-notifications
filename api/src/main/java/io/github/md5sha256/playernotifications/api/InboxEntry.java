package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * One notification as it appears in a player's inbox: the stored notification plus that player's read
 * marker.
 *
 * <p>The payload is carried in its stored, undecoded form. Rendering happens on read, through the
 * {@link io.github.md5sha256.playernotifications.api.render.NotificationRenderer} registered for the
 * payload type, so a renderer change affects entries that were stored long before it.
 *
 * @param seenTime when this player saw the notification, or {@code null} while it is unread
 */
public record InboxEntry(@NotNull String notifKey,
                         @NotNull Instant notifScheduledTime,
                         @Nullable Instant notifExpiryTime,
                         @NotNull String notifPayloadType,
                         @NotNull String notifPayload,
                         int notifPriority,
                         @Nullable Instant seenTime) {

    public boolean unread() {
        return this.seenTime == null;
    }
}
