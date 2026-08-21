package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The testable half of {@code /mail send}'s argument handling: resolving a recipient name to a UUID and
 * validating the message, without touching {@code Bukkit.getOfflinePlayer} or any other server-only API.
 * The command supplies the resolver, the same seam {@code TestNotificationRenderer.usingServerNames()}
 * uses for name lookups.
 */
public final class MailRecipients {

    private MailRecipients() {
    }

    /**
     * Resolves {@code name} and validates {@code message} against
     * {@link MailPayload#MAX_MESSAGE_LENGTH}. {@code resolver} returns {@code null} for an unknown name —
     * in production, an online player by name, else {@code Bukkit.getOfflinePlayer(name)} only when
     * {@code hasPlayedBefore()}, else {@code null}.
     *
     * <p>The message is validated (trimmed, non-blank, length-checked) before the name is resolved, so an
     * over-length message is rejected the same way regardless of whether the recipient exists.
     */
    @NotNull
    public static Result resolve(@NotNull String name, @NotNull String message,
                                 @NotNull Function<String, UUID> resolver) {
        return resolve(name, message, resolver, UnaryOperator.identity());
    }

    /**
     * As {@link #resolve(String, String, Function)}, additionally passing the trimmed message through
     * {@code formatter} — in production {@code MailFormatting.sanitize}, which turns the typed text into
     * the canonical MiniMessage document stored in the payload.
     *
     * <p>The formatter runs <b>after</b> the length check, so the limit bounds what the sender typed
     * rather than what it serialises to; a heavily-tagged message must not be rejected for the size of
     * its own escaping. A formatter that returns blank — {@code "<red>"} carries no readable text — is
     * rejected as a blank message, since {@code MailPayload} refuses one.
     */
    @NotNull
    public static Result resolve(@NotNull String name, @NotNull String message,
                                 @NotNull Function<String, UUID> resolver,
                                 @NotNull UnaryOperator<String> formatter) {
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return new Result.BlankMessage();
        }
        if (trimmed.length() > MailPayload.MAX_MESSAGE_LENGTH) {
            return new Result.MessageTooLong(MailPayload.MAX_MESSAGE_LENGTH);
        }
        String formatted = formatter.apply(trimmed);
        if (formatted.isBlank()) {
            return new Result.BlankMessage();
        }
        UUID recipient = resolver.apply(name);
        if (recipient == null) {
            return new Result.UnknownPlayer(name);
        }
        return new Result.Ok(recipient, formatted);
    }

    public sealed interface Result {

        record Ok(@NotNull UUID recipient, @NotNull String message) implements Result {
        }

        record UnknownPlayer(@NotNull String name) implements Result {
        }

        /**
          * The message was empty, or the formatter reduced it to nothing readable.
          *
          * <p>These cases carry values rather than sentences so that the wording lives in
          * {@code messages.yml} alongside every other player-facing string. It also makes this class's
          * tests assert on a case rather than on prose, so rewording a reply no longer fails a rule test.
          */
        record BlankMessage() implements Result {
        }

        /** The typed message was longer than {@code maxLength} characters and was not truncated. */
        record MessageTooLong(int maxLength) implements Result {
        }
    }
}
