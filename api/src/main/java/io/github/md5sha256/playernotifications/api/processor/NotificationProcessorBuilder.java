package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Fluent builder that composes {@link NotificationProcessor}s into a single chain, invoked in the
 * order the steps are added. Intended to be read top-to-bottom ("chop-down" style):
 *
 * <pre>{@code
 * NotificationProcessor<String> processor = NotificationProcessorBuilder.<String>builder()
 *         .andThen(logProcessor)
 *         .andThenIf(mailProcessor, (payload, targets) -> !targets.isEmpty())
 *         .andThen(metricsProcessor)
 *         .build();
 * }</pre>
 *
 * <p>The builder is mutable and not thread-safe; build the chain once during setup and share the
 * resulting {@link NotificationProcessor}. An empty builder {@link #processor() builds} into a no-op
 * processor.
 *
 * @param <T> the payload type the composed processors accept
 */
public final class NotificationProcessorBuilder<T> {

    private NotificationProcessor<T> chain = (payload, targets) -> {
    };

    private NotificationProcessorBuilder() {
    }

    /**
     * Starts a new, empty chain.
     */
    @NotNull
    public static <T> NotificationProcessorBuilder<T> builder() {
        return new NotificationProcessorBuilder<>();
    }

    /**
     * Appends a processor that always runs after the steps added so far.
     *
     * @return this builder, for chaining
     */
    @NotNull
    public NotificationProcessorBuilder<T> andThen(@NotNull NotificationProcessor<T> next) {
        NotificationProcessor<T> preceding = this.chain;
        this.chain = (payload, targets) -> {
            preceding.receiveNotification(payload, targets);
            next.receiveNotification(payload, targets);
        };
        return this;
    }

    /**
     * Appends a processor that runs after the steps added so far only when {@code predicate} accepts
     * the payload and targets.
     *
     * @return this builder, for chaining
     */
    @NotNull
    public NotificationProcessorBuilder<T> andThenIf(@NotNull NotificationProcessor<T> next,
                                                     @NotNull BiPredicate<T, List<UUID>> predicate) {
        NotificationProcessor<T> preceding = this.chain;
        this.chain = (payload, targets) -> {
            preceding.receiveNotification(payload, targets);
            if (predicate.test(payload, targets)) {
                next.receiveNotification(payload, targets);
            }
        };
        return this;
    }

    /**
     * Builds the composed processor from the steps added so far. The builder may continue to be used
     * afterwards; further steps do not affect an already-built processor.
     */
    @NotNull
    public NotificationProcessor<T> processor() {
        return this.chain;
    }
}
