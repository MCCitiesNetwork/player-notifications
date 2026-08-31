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
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Delivers the mail arrival notice — "&lt;sender&gt; has sent you mail!" over a short preview of the
 * message — to whichever media a player prefers for {@link MailPayload#DATA_TYPE}. The notice is
 * <strong>not</strong> a notification: it is never enqueued or stored, and it carries no count.
 *
 * <p>The preview's cost is understood and accepted: the notice fans out to every medium the player
 * chose, Discord DM included, so the first {@value #PREVIEW_LENGTH} characters of private correspondence
 * leave the game with it. The rest of the mail is still read only through {@code /mail}. It is flattened
 * to <strong>plain text</strong> on one line — the stored message is MiniMessage that passed the sender's
 * permission gate, but a preview shows what was written rather than how it was styled, and a one-line
 * notice is not the place to honour a {@code <newline>}.
 *
 * <p>Deliberately does not reuse {@link io.github.md5sha256.playernotifications.api.render.RenderingProcessor}:
 * that class renders a stored payload and reports a {@link io.github.md5sha256.playernotifications.api.processor.NotificationDisposition}
 * back to the delivery loop, and the notice has neither a payload nor a disposition to report. The
 * media-resolution and sink-fan-out shape below deliberately mirrors it — drop the silenced medium, skip an
 * unregistered sink, catch and log a throwing one — for the same reasons that class does.
 */
public final class MailNotifier {

    /** The most characters of the message a notice previews, before the ellipsis. */
    public static final int PREVIEW_LENGTH = 100;

    /** Appended to a preview that had to be cut, so a partial message cannot read as a whole one. */
    private static final String ELLIPSIS = "...";

    /**
     * The first child of every notice body this class builds: a zero-width component contributing no
     * visible text of its own, and the <strong>marker</strong> {@link #isArrivalNotice} matches on.
     * Neither the body nor the title can be the marker any more — both vary from send to send now that
     * one previews the message and the other names the sender. It is a zero-width space rather than an
     * empty component because {@link Component#text(String)} collapses {@code ""} onto the shared
     * {@link Component#empty()} singleton, which any component could match by accident.
     */
    private static final Component NOTICE_MARKER = Component.text("\u200B");

    /** Runs of whitespace, including any newline the message's own markup introduces. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
     * bodied with {@code mail.arrival-preview} behind the marker. Public so that a sink's tests can
     * build a real notice.
     *
     * <p>The name is substituted with {@link MessageContainer#value(String, String)} rather than
     * {@code markup}: it is arbitrary text a player chose, and a {@code '<'} in one must not open a tag
     * in a message its recipient never consented to. A console send arrives here as
     * {@link io.github.md5sha256.playernotifications.paper.mail.MailSender#SERVER_NAME}, so the notice
     * reads "from Server" and matches the "Mail from Server" title {@code /mail} then shows.
     */
    public @NotNull RenderableNotification arrivalNotice(@NotNull String senderName,
                                                        @NotNull String message) {
        return new RenderableNotification(
                this.messages.messageFor(MessageKeys.MAIL_ARRIVAL_NOTICE,
                        MessageContainer.value("sender", senderName)),
                Component.textOfChildren(NOTICE_MARKER,
                        this.messages.messageFor(MessageKeys.MAIL_ARRIVAL_PREVIEW,
                                MessageContainer.value("preview", preview(message)))));
    }

    /**
     * The stored message flattened onto one line of plain text and cut to {@link #PREVIEW_LENGTH}
     * characters, with {@value #ELLIPSIS} appended when anything was dropped.
     *
     * <p>The parse is guarded exactly as {@link MailRenderer} guards its own: MiniMessage
     * <i>throws</i> on a legacy section-sign code, and mail stored before {@code MailFormatting}
     * landed can contain one. Falling back to the literal text shows the player what is waiting for
     * them rather than nothing at all.
     */
    private static @NotNull String preview(@NotNull String message) {
        Component parsed;
        try {
            parsed = MiniMessage.miniMessage().deserialize(message);
        } catch (RuntimeException ex) {
            parsed = Component.text(message);
        }
        String plain = WHITESPACE
                .matcher(PlainTextComponentSerializer.plainText().serialize(parsed))
                .replaceAll(" ")
                .trim();
        return plain.length() <= PREVIEW_LENGTH
                ? plain
                : plain.substring(0, PREVIEW_LENGTH) + ELLIPSIS;
    }

    /**
     * Whether {@code notification} is one of this class's arrival notices, so that a sink can offer an
     * affordance of its own — the Discord adapter puts a "Read mail" button under it.
     *
     * <p>An <strong>identity</strong> check on the marker leading the body, not a comparison of
     * wording: matching the text would mean rewording the notice silently dropped whatever a sink had
     * attached to it, and would decorate any notification that happened to render the same way.
     */
    public static boolean isArrivalNotice(@NotNull RenderableNotification notification) {
        List<Component> children = notification.body().children();
        return !children.isEmpty() && children.get(0) == NOTICE_MARKER;
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
    public void notifyArrival(@NotNull UUID recipient, @NotNull String senderName,
                              @NotNull String message) {
        if (this.preferences.isMuted(recipient)) {
            this.logger.fine(() -> "Recipient " + recipient + " is muted; skipping the mail arrival notice");
            return;
        }
        RenderableNotification notice = arrivalNotice(senderName, message);
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
