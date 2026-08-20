package io.github.md5sha256.playernotifications.essentials.convert;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One EssentialsX mail, flattened into a form that mentions no EssentialsX type.
 *
 * <p>The flattening happens as early as possible on purpose. Everything downstream of this record —
 * {@link EssentialsMailConverter} and its whole rule set — is then a plain unit under test, and the
 * only classes that need EssentialsX on the classpath are the reader and the binding, neither of which
 * holds a decision worth testing. {@link #of} is parameterised by the <em>fields</em> of an EssentialsX
 * {@code MailMessage} rather than taking one for exactly that reason.
 *
 * <p>This record deliberately validates almost nothing: a blank message is carried through rather than
 * rejected, because deciding what to do with it is the converter's job and a record that threw here
 * would abort a sweep over somebody's whole userdata folder.
 *
 * @param recipient the player whose mailbox this was read from
 * @param sender    the sending player's UUID, or {@link #UNKNOWN_SENDER} when it cannot be recovered
 * @param senderName the sending player's name, or {@link #UNKNOWN_SENDER_NAME}
 * @param message   the message text, already stripped of legacy colour codes
 * @param sentAt    when EssentialsX recorded the mail as sent
 * @param read      whether the recipient had already read it in EssentialsX
 * @param expired   whether EssentialsX would have stopped showing it
 */
public record ImportedMail(@NotNull UUID recipient, @NotNull UUID sender, @NotNull String senderName,
                           @NotNull String message, @NotNull Instant sentAt, boolean read,
                           boolean expired) {

    /**
     * The sender UUID used when the real one cannot be recovered — the nil UUID, which cannot collide
     * with a real account. {@code MailPayload.sender} is non-null, and legacy mail and console mail
     * both arrive without a sender id. Nothing displays it: {@code MailRenderer} shows only
     * {@code senderName}. It is kept so a future reply command can recognise "cannot reply".
     */
    public static final UUID UNKNOWN_SENDER = new UUID(0L, 0L);

    /** The sender name shown when EssentialsX did not record one. */
    public static final String UNKNOWN_SENDER_NAME = "Unknown";

    public ImportedMail {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(sentAt, "sentAt");
    }

    /**
     * Maps one EssentialsX mail message's fields onto this record.
     *
     * <p>A <em>legacy</em> mail is the awkward case: its message text <em>is</em> the entire formatted
     * line, sender prefix and colour codes included, and there is no separate sender field to recover —
     * so its sender is unknown by construction, and any {@code senderName} EssentialsX happens to carry
     * is ignored rather than trusted.
     *
     * @param now the instant expiry is judged against, passed in rather than read from the clock so the
     *            rule is testable
     */
    public static @NotNull ImportedMail of(@NotNull UUID recipient, boolean legacy, boolean read,
                                           @Nullable String senderName, @Nullable UUID senderId,
                                           long timeSent, long timeExpire, @NotNull String message,
                                           @NotNull Instant now) {
        UUID sender = legacy || senderId == null ? UNKNOWN_SENDER : senderId;
        String name;
        if (legacy || senderName == null || senderName.isBlank()) {
            name = UNKNOWN_SENDER_NAME;
        } else {
            name = senderName;
        }
        boolean expired = timeExpire != 0L && now.toEpochMilli() >= timeExpire;
        return new ImportedMail(recipient, sender, name, stripColourCodes(message),
                Instant.ofEpochMilli(timeSent), read, expired);
    }

    /**
     * Removes legacy {@code §} formatting codes.
     *
     * <p>Stripping rather than converting to a {@code Component} is deliberate: {@code MailRenderer}
     * builds bodies with {@code Component.text(...)} specifically so stored text cannot be interpreted
     * as formatting, and converting on import would smuggle formatting into a channel designed to
     * reject it. The alternative — leaving the codes in — would display raw {@code §} noise.
     *
     * <p>Any character following {@code §} is dropped, not just the recognised code alphabet: an
     * unrecognised code is still noise, and a trailing lone {@code §} simply disappears.
     */
    static @NotNull String stripColourCodes(@NotNull String text) {
        int marker = text.indexOf('§');
        if (marker < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;   // also skip the code character, if there is one
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
