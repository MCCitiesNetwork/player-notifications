package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.inbox.InboxChatRow;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code /mail list} row: {@code #<entry> [Time] [Sender] <content>}.
 *
 * <p>Mail is the one data type whose sender and message are worth their own columns, and neither is
 * reachable from the rendered form: {@code MailRenderer} produces the title "Mail from Steve", so
 * recovering "Steve" from it would mean parsing a display string. The payload is decoded instead,
 * through the {@code decoder} seam — in production {@code InboxEntryRenderer::decodePayload}, which is
 * the same decode the render already performs.
 *
 * <p>The zone and the clock are injected rather than read from the JVM so the whole class is unit
 * testable without a server, which is also why this lives outside {@code InboxRouter}.
 */
public final class MailChatRow implements InboxChatRow {

    /**
     * Longest message preview on a row. A mail can be {@link MailPayload#MAX_MESSAGE_LENGTH}
     * characters, which wraps across several chat lines and takes the columns out of alignment with
     * it — and the row already clicks through to {@code read <entry>} for the whole thing.
     */
    static final int PREVIEW_LENGTH = 40;

    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter THIS_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.ROOT);
    private static final DateTimeFormatter OLDER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);

    private final Function<InboxEntry, Optional<Object>> decoder;
    private final ZoneId zone;
    private final Supplier<Instant> clock;

    public MailChatRow(@NotNull Function<InboxEntry, Optional<Object>> decoder, @NotNull ZoneId zone,
                       @NotNull Supplier<Instant> clock) {
        this.decoder = decoder;
        this.zone = zone;
        this.clock = clock;
    }

    @Override
    public @NotNull Component format(int entry, @NotNull InboxEntry stored,
                                     @NotNull RenderableNotification rendered, boolean unread) {
        NamedTextColor color = unread ? NamedTextColor.WHITE : NamedTextColor.GRAY;
        Component row = Component.text("#" + entry + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text("[" + time(stored.notifScheduledTime()) + "] ", NamedTextColor.GRAY));

        Optional<MailPayload> mail = this.decoder.apply(stored)
                .filter(MailPayload.class::isInstance)
                .map(MailPayload.class::cast);
        if (mail.isEmpty()) {
            // The entry is still counted on the page, so an empty [] column would read as a bug. The
            // rendered title is what every row looked like before mail had a format of its own.
            return row.append(rendered.title().colorIfAbsent(color));
        }

        return row.append(Component.text("[" + mail.get().senderName() + "] ", NamedTextColor.AQUA))
                .append(Component.text(preview(mail.get().message()), color));
    }

    /**
     * The narrowest stamp that is still unambiguous: the time for mail sent today, the day and month
     * within this year, and the year as well beyond it. Mail never expires and an EssentialsX import
     * can be years old, so the year cannot simply be dropped.
     */
    private @NotNull String time(@NotNull Instant sent) {
        ZonedDateTime at = sent.atZone(this.zone);
        LocalDate today = this.clock.get().atZone(this.zone).toLocalDate();
        if (at.toLocalDate().equals(today)) {
            return TODAY.format(at);
        }
        return at.getYear() == today.getYear() ? THIS_YEAR.format(at) : OLDER.format(at);
    }

    /**
     * One line's worth of the message. Line breaks become spaces first: a stored newline would
     * otherwise split the row and leave the remainder without its columns, and it would also let a
     * mail's second line pose as a chat message of its own.
     */
    private static @NotNull String preview(@NotNull String message) {
        String flattened = message.replaceAll("\\R+", " ");
        return flattened.length() <= PREVIEW_LENGTH
                ? flattened
                : flattened.substring(0, PREVIEW_LENGTH - 1) + "…";
    }
}
