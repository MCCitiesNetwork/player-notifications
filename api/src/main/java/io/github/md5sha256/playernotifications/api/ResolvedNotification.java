package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

public record ResolvedNotification(
        @NotNull String notifKey,
        @NotNull Instant notifScheduledTime,
        @Nullable Instant notifExpiryTime,
        @NotNull NotificationTarget notifTarget,
        @NotNull String notifPayload,
        int notifPriority
) {
}
