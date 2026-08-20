package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Enqueues mail. The programmatic entry point behind {@code /mail send}, deliberately unvalidated:
 * the command owns message-length and recipient checks, so a module calling this directly gets the
 * mail rather than an exception thrown across a module boundary.
 */
public final class MailSender {

    /**
     * The sender UUID a console-sent mail is attributed to. {@code MailPayload.sender} is
     * {@code @NotNull} and the console has no UUID, so it sends as the nil UUID rather than the API
     * record being widened for it; a nil UUID cannot collide with a real player's.
     */
    public static final UUID SERVER_SENDER = new UUID(0, 0);

    /** The sender name a console-sent mail is attributed to: the recipient reads "Mail from Server". */
    public static final String SERVER_NAME = "Server";

    private final NotificationService service;

    public MailSender(@NotNull NotificationService service) {
        this.service = service;
    }

    /**
     * Enqueues one mail from {@code sender} to {@code recipient}. Returns the notification key.
     *
     * <p>{@code notifExpiryTime} is deliberately {@code null}: mail is correspondence, and every other
     * in-tree notification is transient, so mail must not be swept up by the periodic prune task the way
     * they are.
     */
    public @NotNull String send(@NotNull UUID sender, @NotNull String senderName,
                                @NotNull UUID recipient, @NotNull String message) {
        return send(sender, senderName, recipient, message, Instant.now());
    }

    /**
     * Enqueues one mail that was written at {@code sentAt} rather than now. Returns the notification key.
     *
     * <p>Exists for the EssentialsX mail converter, which imports correspondence written long before the
     * import ran. {@code sentAt} becomes the notification's {@code notifScheduledTime}, which is the
     * inbox's primary sort key — stamping imported mail with the import's own timestamp would pile a
     * decade of correspondence on top of genuinely new mail, in whatever order the sweep happened to
     * visit accounts.
     */
    public @NotNull String send(@NotNull UUID sender, @NotNull String senderName,
                                @NotNull UUID recipient, @NotNull String message,
                                @NotNull Instant sentAt) {
        String key = "mail-" + UUID.randomUUID();
        this.service.enqueueNotification(new TypedNotification<>(
                key,
                sentAt,
                null,
                new NotificationTarget(List.of(recipient)),
                MailPayload.DATA_TYPE,
                new MailPayload(sender, senderName, message),
                0), false);
        return key;
    }
}
