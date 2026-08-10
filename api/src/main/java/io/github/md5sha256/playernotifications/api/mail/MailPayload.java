package io.github.md5sha256.playernotifications.api.mail;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * The payload for the first-party {@code mail} data type: a single message from one player to
 * another, stored and read through the ordinary inbox machinery.
 *
 * <p>{@code senderName} is stored rather than looked up at render time. A {@code NotificationRenderer}
 * runs on read, potentially long after the send, and {@code Bukkit.getOfflinePlayer(uuid).getName()}
 * is a blocking lookup that returns {@code null} for a player the server has never seen. Storing the
 * name the sender had at send time is both cheaper and more truthful — the mail says who wrote it, not
 * who owns that UUID today. The {@code sender} UUID is kept alongside for a future reply command and
 * for admin tooling.
 *
 * @param sender     the sending player's UUID
 * @param senderName the sending player's name at the time the mail was sent
 * @param message    the mail's message text
 */
public record MailPayload(@NotNull UUID sender, @NotNull String senderName, @NotNull String message) {

    /** The registry data type mail is stored under. */
    public static final String DATA_TYPE = "mail";

    /** Longest message accepted by {@code /mail send}. */
    public static final int MAX_MESSAGE_LENGTH = 256;

    public MailPayload {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(message, "message");
        if (senderName.isBlank()) {
            throw new IllegalArgumentException("senderName must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
