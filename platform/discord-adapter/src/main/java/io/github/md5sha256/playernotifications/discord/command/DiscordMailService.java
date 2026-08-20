package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import io.github.md5sha256.playernotifications.paper.mail.MailRecipients;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sending mail from Discord: the rules {@code /mail send} and the compose modal share.
 *
 * <p>Mail sent from Discord is always plain text. It passes through no permission gate — there is no
 * {@code CommandSender} in Discord to check {@code playernotifications.command.mail.format.*} against,
 * and a Discord-side permission model would be a second, divergent gate on the same feature — so the
 * message is escaped with MiniMessage's {@code escapeTags} before it is stored. That is the rule the
 * EssentialsX importer follows for the same reason: text from outside the gate must be able to render
 * only as the literal text it was.
 *
 * <p>Every method blocks on JDBC and on offline-player lookups; call them off the main thread.
 */
public final class DiscordMailService {

    private final MailSender sender;
    private final MailNotifier notifier;
    private final Function<String, UUID> recipientResolver;
    private final Function<UUID, String> senderNames;
    private final Logger logger;

    /**
     * @param recipientResolver a name to a player UUID, or {@code null} for a player this server has
     *                          never seen — the seam that keeps this testable without a server
     * @param senderNames       the Discord user's player name, captured at send time because a mail is
     *                          rendered on read, potentially long after
     */
    public DiscordMailService(@NotNull MailSender sender,
                              @NotNull MailNotifier notifier,
                              @NotNull Function<String, UUID> recipientResolver,
                              @NotNull Function<UUID, String> senderNames,
                              @NotNull Logger logger) {
        this.sender = sender;
        this.notifier = notifier;
        this.recipientResolver = recipientResolver;
        this.senderNames = senderNames;
        this.logger = logger;
    }

    /** Resolves, escapes, enqueues and then fires the arrival notice. */
    public @NotNull SendResult send(@NotNull UUID sender, @NotNull String recipientName,
                                    @NotNull String message) {
        MailRecipients.Result resolved = MailRecipients.resolve(recipientName, message,
                this.recipientResolver, MiniMessage.miniMessage()::escapeTags);

        if (resolved instanceof MailRecipients.Result.UnknownPlayer unknown) {
            return new SendResult.Rejected("No player named '" + unknown.name() + "' has played here.");
        }
        if (resolved instanceof MailRecipients.Result.InvalidMessage invalid) {
            return new SendResult.Rejected(invalid.reason());
        }

        MailRecipients.Result.Ok ok = (MailRecipients.Result.Ok) resolved;
        try {
            this.sender.send(sender, this.senderNames.apply(sender), ok.recipient(), ok.message());
        } catch (RuntimeException exception) {
            this.logger.log(Level.WARNING, "Failed to send Discord-originated mail to " + recipientName,
                    exception);
            return new SendResult.Failed("Your mail could not be sent. Please try again.");
        }

        // Only after the mail is stored: announcing mail that was never enqueued would send the
        // recipient to an empty inbox.
        this.notifier.notifyArrival(ok.recipient());
        return new SendResult.Ok(recipientName);
    }

    /** The outcome of one send. */
    public sealed interface SendResult {

        record Ok(@NotNull String recipientName) implements SendResult {
        }

        /** The sender asked for something that cannot be done; the reason is shown to them verbatim. */
        record Rejected(@NotNull String reason) implements SendResult {
        }

        /** Something broke on our side; the detail is in the log, not in the reply. */
        record Failed(@NotNull String reason) implements SendResult {
        }
    }
}
