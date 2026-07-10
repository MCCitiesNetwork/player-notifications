package io.github.md5sha256.playernotifications.api.processor;

import org.jetbrains.annotations.NotNull;

/**
 * The outcome a {@link NotificationProcessor} reports for a notification: whether it should be kept
 * or marked for deletion once processing completes.
 */
public enum NotificationDisposition {

    /** Keep the notification; it remains eligible for further processing. */
    RETAIN,

    /** Mark the notification for deletion; it should be removed after processing. */
    DELETE;

    /**
     * Combines this disposition with another, favouring deletion: the result is {@link #DELETE} when
     * either disposition requests it, otherwise {@link #RETAIN}. Used to fold the results of a
     * processor chain — any processor that flags deletion wins.
     */
    @NotNull
    public NotificationDisposition combine(@NotNull NotificationDisposition other) {
        return this == DELETE || other == DELETE ? DELETE : RETAIN;
    }
}
