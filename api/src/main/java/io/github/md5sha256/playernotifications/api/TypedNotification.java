package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * A notification carrying a typed, not-yet-serialized payload. Mirrors {@link ResolvedNotification}
 * but holds the payload as {@code T}; the service serializes it through the
 * {@link io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer} registered for
 * {@link #notifPayloadType()} when enqueuing.
 *
 * @param <T> the payload type
 */
public record TypedNotification<T>(
        @NotNull String notifKey,
        @NotNull Instant notifScheduledTime,
        @Nullable Instant notifExpiryTime,
        @NotNull NotificationTarget notifTarget,
        @NotNull String notifPayloadType,
        @NotNull T notifPayload,
        int notifPriority
) {
}