package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Delivers the verbatim "You have new mail!" arrival notice to whichever media a player prefers for
 * {@link MailPayload#DATA_TYPE}. The notice is <strong>not</strong> a notification — it is never enqueued
 * or stored, so it carries no sender name, count, or message preview; the mail itself is read only through
 * {@code /mail}.
 *
 * <p>Deliberately does not reuse {@link io.github.md5sha256.playernotifications.api.render.RenderingProcessor}:
 * that class renders a stored payload and reports a {@link io.github.md5sha256.playernotifications.api.processor.NotificationDisposition}
 * back to the delivery loop, and the notice has neither a payload nor a disposition to report. The
 * media-resolution and sink-fan-out shape below deliberately mirrors it — drop the mute medium, skip an
 * unregistered sink, catch and log a throwing one — for the same reasons that class does.
 */
public final class MailNotifier {

    private static final Component NOTICE_TITLE = Component.text("You have new mail!");
    private static final Component NOTICE_BODY = Component.text("Use /mail to read it.");
    private static final RenderableNotification NOTICE = new RenderableNotification(NOTICE_TITLE, NOTICE_BODY);

    private final NotificationSinkRegistry sinks;
    private final NotificationPreferences preferences;
    private final Logger logger;

    public MailNotifier(@NotNull NotificationSinkRegistry sinks,
                        @NotNull NotificationPreferences preferences,
                        @NotNull Logger logger) {
        this.sinks = sinks;
        this.preferences = preferences;
        this.logger = logger;
    }

    /**
     * Resolves {@code recipient}'s preferred media for {@link MailPayload#DATA_TYPE}, drops
     * {@link NotificationPreferences#MUTED_MEDIUM}, and delivers the fixed notice to each medium's sink.
     * A sink with no registration is skipped; a sink that throws is caught and logged so it cannot
     * suppress the others.
     *
     * <p>Returns immediately, before resolving any media, when the recipient has the global mute set
     * ({@link NotificationPreferences#isMuted(UUID)}) — a global mute means "do not interrupt me at all",
     * and the mail itself is unaffected: it still lands in the inbox, unread, for {@code /mail} to show.
     */
    public void notifyArrival(@NotNull UUID recipient) {
        if (this.preferences.isMuted(recipient)) {
            this.logger.fine(() -> "Recipient " + recipient + " is muted; skipping the mail arrival notice");
            return;
        }
        Set<String> media = new LinkedHashSet<>(
                this.preferences.preferredMedia(recipient, MailPayload.DATA_TYPE));
        media.remove(NotificationPreferences.MUTED_MEDIUM);
        for (String medium : media) {
            Optional<NotificationSink> sink = this.sinks.getSink(medium);
            if (sink.isEmpty()) {
                this.logger.fine(() -> "No sink registered for medium '" + medium
                        + "'; skipping the mail arrival notice");
                continue;
            }
            deliverSafely(sink.get(), recipient);
        }
    }

    private void deliverSafely(@NotNull NotificationSink sink, @NotNull UUID recipient) {
        try {
            DeliveryResult result = sink.deliver(NOTICE, recipient);
            if (result != DeliveryResult.DELIVERED) {
                this.logger.fine(() -> "Mail arrival notice not delivered via '" + sink.mediumKey()
                        + "' for " + recipient + ": " + result);
            }
        } catch (RuntimeException e) {
            this.logger.warning("Sink '" + sink.mediumKey() + "' threw while delivering the mail arrival "
                    + "notice to " + recipient + "; treating as unreachable: " + e.getMessage());
        }
    }
}
