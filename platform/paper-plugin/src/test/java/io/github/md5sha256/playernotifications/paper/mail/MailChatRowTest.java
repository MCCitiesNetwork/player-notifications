package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The {@code /mail list} row format: {@code #<entry> [Time] [Sender] <content>}.
 *
 * <p>Unit-testable without a server because the zone, the clock and the payload decode are all
 * injected — the same device {@code TestNotificationRenderer} uses for its name lookup.
 */
class MailChatRowTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    /** "Now" for every test: a fixed afternoon, so "today" and "this year" are decidable. */
    private static final Instant NOW = at(2026, 8, 21, 18, 30);

    private static final UUID SENDER = UUID.randomUUID();

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC);
    }

    private static InboxEntry entry(Instant sent) {
        return new InboxEntry("mail-1", sent, null, MailPayload.DATA_TYPE, "{}", 0, null);
    }

    private static RenderableNotification rendered() {
        return new RenderableNotification(Component.text("Mail from Steve"), Component.text("ignored"));
    }

    /** A decoder that always yields this mail. */
    private static Function<InboxEntry, Optional<Object>> decoding(String senderName, String message) {
        return e -> Optional.of(new MailPayload(SENDER, senderName, message));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static MailChatRow row(Function<InboxEntry, Optional<Object>> decoder) {
        return new MailChatRow(decoder, ZONE, () -> NOW);
    }

    @Test
    void mailSentTodayShowsTheTimeOfDay() {
        Component formatted = row(decoding("Steve", "Are you around later to trade?"))
                .format(1, entry(at(2026, 8, 21, 14, 3)), rendered(), true);

        Assertions.assertEquals("#1 [14:03] [Steve] Are you around later to trade?", plain(formatted));
    }

    @Test
    void mailSentEarlierThisYearShowsTheDayAndMonth() {
        Component formatted = row(decoding("Alex", "left you the diamonds at spawn"))
                .format(2, entry(at(2026, 8, 19, 9, 41)), rendered(), false);

        Assertions.assertEquals("#2 [19 Aug] [Alex] left you the diamonds at spawn", plain(formatted));
    }

    @Test
    void mailFromAnEarlierYearCarriesTheYear() {
        // Mail never expires and an EssentialsX import can be years old, so the year cannot be dropped
        // for anything outside the current one.
        Component formatted = row(decoding("Server", "Welcome to the server!"))
                .format(3, entry(at(2021, 3, 2, 22, 17)), rendered(), false);

        Assertions.assertEquals("#3 [2 Mar 2021] [Server] Welcome to the server!", plain(formatted));
    }

    @Test
    void aLongMessageIsTruncatedToOneLineWithAnEllipsis() {
        String message = "x".repeat(MailChatRow.PREVIEW_LENGTH + 20);

        Component formatted = row(decoding("Steve", message))
                .format(1, entry(NOW), rendered(), true);

        String expected = "#1 [18:30] [Steve] " + "x".repeat(MailChatRow.PREVIEW_LENGTH - 1) + "…";
        Assertions.assertEquals(expected, plain(formatted));
    }

    @Test
    void aMessageExactlyAtTheLimitIsNotTruncated() {
        String message = "y".repeat(MailChatRow.PREVIEW_LENGTH);

        Component formatted = row(decoding("Steve", message))
                .format(1, entry(NOW), rendered(), true);

        Assertions.assertEquals("#1 [18:30] [Steve] " + message, plain(formatted));
    }

    @Test
    void aNewlineInTheMessageIsFlattenedSoTheRowStaysOneLine() {
        Component formatted = row(decoding("Steve", "first\nsecond"))
                .format(1, entry(NOW), rendered(), true);

        Assertions.assertEquals("#1 [18:30] [Steve] first second", plain(formatted));
    }

    @Test
    void anUndecodablePayloadFallsBackToTheRenderedTitle() {
        // Better a row that reads like the old format than one with empty [] columns: the entry is
        // still counted in the page, so hiding what it is would read as a bug.
        Component formatted = row(e -> Optional.empty())
                .format(4, entry(NOW), rendered(), true);

        Assertions.assertEquals("#4 [18:30] Mail from Steve", plain(formatted));
    }

    @Test
    void aPayloadThatIsNotMailFallsBackToTheRenderedTitle() {
        Component formatted = row(e -> Optional.of("not a mail payload"))
                .format(4, entry(NOW), rendered(), true);

        Assertions.assertEquals("#4 [18:30] Mail from Steve", plain(formatted));
    }
}
