package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public record NotificationTarget(@NotNull List<UUID> playerUUIDs) {
}
