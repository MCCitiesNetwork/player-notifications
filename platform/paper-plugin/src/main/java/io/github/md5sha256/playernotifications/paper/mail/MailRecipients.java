package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Function;

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
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return new Result.InvalidMessage("Mail cannot be blank.");
        }
        if (trimmed.length() > MailPayload.MAX_MESSAGE_LENGTH) {
            return new Result.InvalidMessage(
                    "Mail must be at most " + MailPayload.MAX_MESSAGE_LENGTH + " characters.");
        }
        UUID recipient = resolver.apply(name);
        if (recipient == null) {
            return new Result.UnknownPlayer(name);
        }
        return new Result.Ok(recipient, trimmed);
    }

    public sealed interface Result {

        record Ok(@NotNull UUID recipient, @NotNull String message) implements Result {
        }

        record UnknownPlayer(@NotNull String name) implements Result {
        }

        record InvalidMessage(@NotNull String reason) implements Result {
        }
    }
}
