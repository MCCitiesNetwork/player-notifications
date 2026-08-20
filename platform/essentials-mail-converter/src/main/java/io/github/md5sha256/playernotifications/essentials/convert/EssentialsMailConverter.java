package io.github.md5sha256.playernotifications.essentials.convert;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns {@link ImportedMail} into first-party mail, applying the mapping rules from
 * {@code docs/superpowers/specs/2026-08-20-essentials-mail-converter-design.md}.
 *
 * <p>Deliberately free of Bukkit, EssentialsX and the scheduler: this class holds every decision the
 * converter makes, and the classes around it hold none, so the rules can be tested exhaustively without
 * a server. It does blocking JDBC through {@link MailSender} and {@link NotificationService}, so callers
 * must run {@link #convert} off the main thread.
 *
 * <p>Conversion is <strong>not idempotent</strong> — running it twice imports every mail twice. That
 * follows from leaving the EssentialsX copy untouched, which keeps a bad import recoverable; the command
 * layer is where the operator is warned, and {@link #preview} is what they check first.
 */
public final class EssentialsMailConverter {

    private final NotificationService service;
    private final MailSender mailSender;
    private final Logger logger;

    public EssentialsMailConverter(@NotNull NotificationService service, @NotNull MailSender mailSender,
                                   @NotNull Logger logger) {
        this.service = service;
        this.mailSender = mailSender;
        this.logger = logger;
    }

    /** Classifies {@code mail} without writing anything. */
    public @NotNull ConversionReport preview(@NotNull List<ImportedMail> mail) {
        return run(mail, false);
    }

    /** Classifies and imports {@code mail}. Blocking; call off the main thread. */
    public @NotNull ConversionReport convert(@NotNull List<ImportedMail> mail) {
        return run(mail, true);
    }

    /**
     * The single pass both entry points share, so a preview cannot report numbers a conversion would
     * not produce.
     */
    private @NotNull ConversionReport run(@NotNull List<ImportedMail> mail, boolean write) {
        int imported = 0;
        int skippedExpired = 0;
        int skippedBlank = 0;
        int failed = 0;
        for (ImportedMail entry : mail) {
            // EssentialsX would no longer have shown this one, so importing it would resurrect mail the
            // recipient was never going to see.
            if (entry.expired()) {
                skippedExpired++;
                continue;
            }
            // MailPayload rejects a blank message outright, and there is nothing to read anyway.
            if (entry.message().isBlank()) {
                skippedBlank++;
                continue;
            }
            if (!write) {
                imported++;
                continue;
            }
            try {
                // No length check: MailPayload.MAX_MESSAGE_LENGTH is a /mail send input rule, not a
                // storage constraint, and truncating archived correspondence would be silent data loss.
                String key = this.mailSender.send(entry.sender(), entry.senderName(), entry.recipient(),
                        entry.message(), entry.sentAt());
                imported++;
                if (entry.read()) {
                    markSeen(key, entry);
                }
            } catch (RuntimeException ex) {
                failed++;
                this.logger.log(Level.WARNING,
                        "Failed to import an EssentialsX mail for " + entry.recipient(), ex);
            }
        }
        return new ConversionReport(imported, skippedExpired, skippedBlank, failed);
    }

    /**
     * Marks an already-read mail seen, so an archive does not arrive as hundreds of unread entries the
     * recipient read years ago.
     *
     * <p>A failure here is logged but <em>not</em> counted as a failed import: the mail is stored and
     * readable, it merely shows as unread. Re-running the conversion is not a remedy — it would
     * duplicate every mail on the server.
     */
    private void markSeen(@NotNull String key, @NotNull ImportedMail entry) {
        try {
            this.service.markSeen(key, entry.recipient());
        } catch (RuntimeException ex) {
            this.logger.log(Level.WARNING, "Imported mail " + key + " for " + entry.recipient()
                    + " could not be marked read; it will show as unread", ex);
        }
    }
}
