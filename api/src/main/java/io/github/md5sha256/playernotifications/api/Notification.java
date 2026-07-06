package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

public record Notification(
        @NotNull String notifKey,
        @NotNull Instant notifScheduledTime,
        @Nullable Instant notifExpiryTime,
        int notifTargetId,
        @NotNull String notifPayload,
        int notifPriority
) {
}
