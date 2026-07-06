package io.github.md5sha256.playernotifications.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

@FunctionalInterface
public interface NotificationProcessor<T> {

    void receiveNotification(@NotNull T payload, @NotNull List<UUID> targets);

    @NotNull
    default NotificationProcessor<T> andThen(@NotNull NotificationProcessor<T> next) {
        return (payload, targets) -> {
            receiveNotification(payload, targets);
            next.receiveNotification(payload, targets);
        };
    }

    @NotNull
    default NotificationProcessor<T> andThenIf(@NotNull NotificationProcessor<T> next,
                                               @NotNull BiPredicate<T, List<UUID>> predicate) {
        return ((payload, targets) -> {
            receiveNotification(payload, targets);
            if (predicate.test(payload, targets)) {
                next.receiveNotification(payload, targets);
            }
        });
    }
}
