package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FixedDelayProcessor<T> implements NotificationProcessor<T> {

    private final ScheduledExecutorService scheduler;
    private final NotificationProcessor<T> toInvoke;
    private final Duration delay;

    public FixedDelayProcessor(@NotNull ScheduledExecutorService scheduler,
                               @NotNull NotificationProcessor<T> toInvoke,
                               @NotNull Duration delay) {
        this.scheduler = scheduler;
        this.toInvoke = toInvoke;
        this.delay = delay;
    }

    @Override
    public void receiveNotification(@NonNull T payload, @NotNull List<UUID> targets) {
        this.scheduler.schedule(() -> toInvoke.receiveNotification(payload, targets),
                delay.toMillis(),
                TimeUnit.MICROSECONDS);
    }
}
