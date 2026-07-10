package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FixedDelayProcessor<T> implements NotificationProcessor<T> {

    private final ScheduledExecutorService scheduler;
    private final NotificationProcessor<T> toInvoke;
    private final Duration delay;
    private final NotificationDisposition disposition;

    public FixedDelayProcessor(@NotNull ScheduledExecutorService scheduler,
                               @NotNull NotificationProcessor<T> toInvoke,
                               @NotNull Duration delay) {
        this(scheduler, toInvoke, delay, NotificationDisposition.RETAIN);
    }

    public FixedDelayProcessor(@NotNull ScheduledExecutorService scheduler,
                               @NotNull NotificationProcessor<T> toInvoke,
                               @NotNull Duration delay,
                               @NotNull NotificationDisposition disposition) {
        this.scheduler = scheduler;
        this.toInvoke = toInvoke;
        this.delay = delay;
        this.disposition = disposition;
    }

    @Override
    public @NotNull NotificationDisposition receiveNotification(@NonNull T payload,
                                                                @NotNull UUID target) {
        this.scheduler.schedule(() -> toInvoke.receiveNotification(payload, target),
                delay.toMillis(),
                TimeUnit.MICROSECONDS);
        // The wrapped processor runs later, so its disposition cannot propagate synchronously; the
        // notification is retained and any deletion must be handled by the delayed processor itself.
        return this.disposition;
    }
}
