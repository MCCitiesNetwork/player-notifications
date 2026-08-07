package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;

/**
 * The outcome a {@link NotificationProcessor} reports for a notification: whether it remains eligible
 * for delivery, or has now been seen by this target.
 */
public enum NotificationDisposition {

    /** Keep the notification; it remains eligible for further processing. */
    RETAIN,

    /**
     * Mark the notification seen for this target; it is retained and not offered for delivery again.
     */
    MARK_SEEN;

    /**
     * Combines this disposition with another, favouring {@link #MARK_SEEN}: the result is
     * {@link #MARK_SEEN} when either disposition requests it, otherwise {@link #RETAIN}. Used to fold
     * the results of a processor chain — any processor that reached the player wins.
     */
    @NotNull
    public NotificationDisposition combine(@NotNull NotificationDisposition other) {
        return this == MARK_SEEN || other == MARK_SEEN ? MARK_SEEN : RETAIN;
    }
}
