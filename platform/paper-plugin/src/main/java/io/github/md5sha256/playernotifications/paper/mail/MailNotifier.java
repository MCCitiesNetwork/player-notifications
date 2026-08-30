package io.github.md5sha256.playernotifications.paper.mail;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Delivers the mail arrival notice — "You were sent mail from &lt;sender&gt;" — to whichever media a
 * player prefers for {@link MailPayload#DATA_TYPE}. The notice is <strong>not</strong> a notification: it
 * is never enqueued or stored, and it carries the sender's name and nothing else. There is deliberately
 * no count and no preview of the message, which would put private correspondence onto a medium the player
 * chose for notices; the mail itself is read only through {@code /mail}.
 *
 * <p>Deliberately does not reuse {@link io.github.md5sha256.playernotifications.api.render.RenderingProcessor}:
 * that class renders a stored payload and reports a {@link io.github.md5sha256.playernotifications.api.processor.NotificationDisposition}
 * back to the delivery loop, and the notice has neither a payload nor a disposition to report. The
 * media-resolution and sink-fan-out shape below deliberately mirrors it — drop the silenced medium, skip an
 * unregistered sink, catch and log a throwing one — for the same reasons that class does.
 */
public final class MailNotifier {

    /**
     * The notice's body, shared by every notice this class builds and never rendered from
     * {@code messages.yml} — it is the <strong>marker</strong> {@link #isArrivalNotice} matches on.
     * A configurable body would be re-rendered into a fresh instance by {@code /notifications reload},
     * and every button attached after that reload would vanish with nothing logged.
     */
    private static final Component NOTICE_BODY = Component.text("Use /mail to read it.");

    private final NotificationSinkRegistry sinks;
    private final NotificationPreferences preferences;
    private final MessageContainer messages;
    private final Logger logger;

    public MailNotifier(@NotNull NotificationSinkRegistry sinks,
                        @NotNull NotificationPreferences preferences,
                        @NotNull MessageContainer messages,
                        @NotNull Logger logger) {
        this.sinks = sinks;
        this.preferences = preferences;
        this.messages = messages;
        this.logger = logger;
    }

    /**
     * The notice announcing mail from {@code senderName}, titled from {@code mail.arrival-notice} and
     * bodied with the shared marker. Public so that a sink's tests can build a real notice.
     *
     * <p>The name is substituted with {@link MessageContainer#value(String, String)} rather than
     * {@code markup}: it is arbitrary text a player chose, and a {@code '<'} in one must not open a tag
     * in a message its recipient never consented to. A console send arrives here as
     * {@link io.github.md5sha256.playernotifications.paper.mail.MailSender#SERVER_NAME}, so the notice
     * reads "from Server" and matches the "Mail from Server" title {@code /mail} then shows.
     */
    public @NotNull RenderableNotification arrivalNotice(@NotNull String senderName) {
        return new RenderableNotification(
                this.messages.messageFor(MessageKeys.MAIL_ARRIVAL_NOTICE,
                        MessageContainer.value("sender", senderName)),
                NOTICE_BODY);
    }

    /**
     * Whether {@code notification} is one of this class's arrival notices, so that a sink can offer an
     * affordance of its own — the Discord adapter puts a "Read mail" button under it.
     *
     * <p>An <strong>identity</strong> check on the body, not a comparison of wording: matching the text
     * would mean rewording the notice silently dropped whatever a sink had attached to it, and would
     * decorate any notification that happened to render the same way. The body rather than the whole
     * notification because the title now names the sender and so differs from send to send.
     */
    public static boolean isArrivalNotice(@NotNull RenderableNotification notification) {
        return notification.body() == NOTICE_BODY;
    }

    /**
     * Resolves {@code recipient}'s preferred media for {@link MailPayload#DATA_TYPE}, drops
     * {@link NotificationPreferences#SILENCED_MEDIUM}, and delivers the notice to each medium's sink.
     * A sink with no registration is skipped; a sink that throws is caught and logged so it cannot
     * suppress the others.
     *
     * <p>Returns immediately, before resolving any media, when the recipient has the global mute set
     * ({@link NotificationPreferences#isMuted(UUID)}) — a global mute means "do not interrupt me at all",
     * and the mail itself is unaffected: it still lands in the inbox, unread, for {@code /mail} to show.
     */
    public void notifyArrival(@NotNull UUID recipient, @NotNull String senderName) {
        if (this.preferences.isMuted(recipient)) {
            this.logger.fine(() -> "Recipient " + recipient + " is muted; skipping the mail arrival notice");
            return;
        }
        RenderableNotification notice = arrivalNotice(senderName);
        Set<String> media = new LinkedHashSet<>(
                this.preferences.preferredMedia(recipient, MailPayload.DATA_TYPE));
        media.remove(NotificationPreferences.SILENCED_MEDIUM);
        for (String medium : media) {
            Optional<NotificationSink> sink = this.sinks.getSink(medium);
            if (sink.isEmpty()) {
                this.logger.fine(() -> "No sink registered for medium '" + medium
                        + "'; skipping the mail arrival notice");
                continue;
            }
            deliverSafely(sink.get(), notice, recipient);
        }
    }

    private void deliverSafely(@NotNull NotificationSink sink, @NotNull RenderableNotification notice,
                               @NotNull UUID recipient) {
        try {
            DeliveryResult result = sink.deliver(notice, recipient);
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
