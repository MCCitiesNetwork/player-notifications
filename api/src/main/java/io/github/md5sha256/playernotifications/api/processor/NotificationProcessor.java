package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Receives a decoded notification payload for a single target player (audience) and reports whether
 * the notification should be retained or marked for deletion afterwards. The caller is responsible
 * for invoking the processor once per target in the notification's audience.
 *
 * <p>Processors are composed into chains with {@link NotificationProcessorBuilder}, which folds the
 * chain's dispositions together (see {@link NotificationDisposition#combine}).
 */
@FunctionalInterface
public interface NotificationProcessor<T> {

    /**
     * Processes the notification for a single target player.
     *
     * @return {@link NotificationDisposition#DELETE} to flag the notification for deletion, or
     *         {@link NotificationDisposition#RETAIN} to keep it
     */
    @NotNull
    NotificationDisposition receiveNotification(@NotNull T payload, @NotNull UUID target);

}
