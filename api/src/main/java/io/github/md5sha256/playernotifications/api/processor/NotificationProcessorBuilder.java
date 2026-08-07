package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Fluent builder that composes {@link NotificationProcessor}s into a single chain, invoked in the
 * order the steps are added. Each processor handles one target player; intended to be read
 * top-to-bottom ("chop-down" style):
 *
 * <pre>{@code
 * NotificationProcessor<String> processor = NotificationProcessorBuilder.<String>builder()
 *         .andThen(logProcessor)
 *         .andThenIf(mailProcessor, (payload, target) -> isOffline(target))
 *         .andThen(metricsProcessor)
 *         .processor();
 * }</pre>
 *
 * <p>The composed processor folds each step's {@link NotificationDisposition} together: the chain
 * flags the notification for deletion if any step that runs returns
 * {@link NotificationDisposition#DELETE} (see {@link NotificationDisposition#combine}).
 *
 * <p>The builder is mutable and not thread-safe; build the chain once during setup and share the
 * resulting {@link NotificationProcessor}. An empty builder {@link #processor() builds} into a no-op
 * processor that {@link NotificationDisposition#RETAIN retains} the notification.
 *
 * @param <T> the payload type the composed processors accept
 */
public final class NotificationProcessorBuilder<T> {

    private NotificationProcessor<T> chain = (payload, target) -> NotificationDisposition.RETAIN;

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
     * Appends a processor that always runs after the steps added so far. The notification is flagged
     * for deletion if either the preceding chain or {@code next} requests it.
     *
     * @return this builder, for chaining
     */
    @NotNull
    public NotificationProcessorBuilder<T> andThen(@NotNull NotificationProcessor<T> next) {
        NotificationProcessor<T> preceding = this.chain;
        this.chain = (payload, target) -> {
            NotificationDisposition disposition = preceding.receiveNotification(payload, target);
            return disposition.combine(next.receiveNotification(payload, target));
        };
        return this;
    }

    /**
     * Appends a processor that runs after the steps added so far only when {@code predicate} accepts
     * the payload and target. When it is skipped, only the preceding chain's disposition applies.
     *
     * @return this builder, for chaining
     */
    @NotNull
    public NotificationProcessorBuilder<T> andThenIf(@NotNull NotificationProcessor<T> next,
                                                     @NotNull BiPredicate<T, UUID> predicate) {
        NotificationProcessor<T> preceding = this.chain;
        this.chain = (payload, target) -> {
            NotificationDisposition disposition = preceding.receiveNotification(payload, target);
            if (predicate.test(payload, target)) {
                return disposition.combine(next.receiveNotification(payload, target));
            }
            return disposition;
        };
        return this;
    }

    /**
     * Terminates the chain with a fixed disposition: builds the composed processor and appends a
     * final step that forces the given disposition (folded in as any other step).
     */
    @NotNull
    public NotificationProcessor<T> onComplete(@NotNull NotificationDisposition disposition) {
        return andThen((unused, target) -> disposition).chain;
    }

    /**
     * Terminates the chain, forcing the notification to be marked for deletion.
     */
    @NotNull
    public NotificationProcessor<T> deleteOnComplete() {
        return onComplete(NotificationDisposition.MARK_SEEN);
    }

    /**
     * Terminates the chain, forcing the notification to be retained.
     */
    @NotNull
    public NotificationProcessor<T> retainOnComplete() {
        return onComplete(NotificationDisposition.RETAIN);
    }

    /**
     * Returns the composed processor from the steps added so far. The builder may continue to be
     * used afterwards; further steps do not affect an already-returned processor.
     */
    @NotNull
    public NotificationProcessor<T> processor() {
        return this.chain;
    }
}
