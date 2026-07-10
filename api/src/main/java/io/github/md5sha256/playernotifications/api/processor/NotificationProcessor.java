package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Receives a decoded notification payload and the players it targets.
 *
 * <p>Processors are composed into chains with {@link NotificationProcessorBuilder}.
 */
@FunctionalInterface
public interface NotificationProcessor<T> {

    void receiveNotification(@NotNull T payload, @NotNull List<UUID> targets);

}
