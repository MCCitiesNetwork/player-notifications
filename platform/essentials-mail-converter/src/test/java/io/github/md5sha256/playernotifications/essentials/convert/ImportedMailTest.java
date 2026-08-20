package io.github.md5sha256.playernotifications.essentials.convert;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/**
 * Covers {@link ImportedMail#of}, which is the whole of the EssentialsX-to-payload mapping that can be
 * tested without EssentialsX on the classpath. A mistake in the colour-code stripping would silently
 * mangle every legacy mail on a server, so it is pinned here rather than left to the manual checklist.
 */
class ImportedMailTest {

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    @DisplayName("a legacy mail has no recoverable sender and is stripped of colour codes")
    void legacyMail() {
        ImportedMail mail = ImportedMail.of(RECIPIENT, true, false, null, null,
                1000L, 0L, "§6[§rBob§6]§r hi", NOW);

        Assertions.assertEquals(ImportedMail.UNKNOWN_SENDER, mail.sender());
        Assertions.assertEquals(ImportedMail.UNKNOWN_SENDER_NAME, mail.senderName());
        Assertions.assertEquals("[Bob] hi", mail.message());
    }

    @Test
    @DisplayName("a legacy mail's own sender name is ignored, since the name is inside the message")
    void legacyMailIgnoresSenderName() {
        ImportedMail mail = ImportedMail.of(RECIPIENT, true, false, "Bob", UUID.randomUUID(),
                1000L, 0L, "hi", NOW);

        Assertions.assertEquals(ImportedMail.UNKNOWN_SENDER_NAME, mail.senderName());
        Assertions.assertEquals(ImportedMail.UNKNOWN_SENDER, mail.sender());
    }

    @Test
    @DisplayName("a modern mail keeps its sender name and UUID")
    void modernMail() {
        UUID senderId = UUID.randomUUID();
        ImportedMail mail = ImportedMail.of(RECIPIENT, false, true, "Alex", senderId,
                1000L, 0L, "hello", NOW);

        Assertions.assertEquals(senderId, mail.sender());
        Assertions.assertEquals("Alex", mail.senderName());
        Assertions.assertEquals("hello", mail.message());
        Assertions.assertTrue(mail.read());
    }

    @Test
    @DisplayName("a modern mail with no sender UUID falls back to the nil sentinel but keeps its name")
    void modernMailWithoutSenderUuid() {
        ImportedMail mail = ImportedMail.of(RECIPIENT, false, false, "Console", null,
                1000L, 0L, "hello", NOW);

        Assertions.assertEquals(ImportedMail.UNKNOWN_SENDER, mail.sender());
        Assertions.assertEquals("Console", mail.senderName());
    }

    @Test
    @DisplayName("a modern mail with a blank sender name falls back to the unknown label")
    void modernMailWithBlankSenderName() {
        ImportedMail mail = ImportedMail.of(RECIPIENT, false, false, "  ", null,
                1000L, 0L, "hello", NOW);

        Assertions.assertEquals(ImportedMail.UNKNOWN_SENDER_NAME, mail.senderName());
    }

    @Test
    @DisplayName("the send time is the epoch millisecond EssentialsX recorded")
    void sendTime() {
        ImportedMail mail = ImportedMail.of(RECIPIENT, false, false, "Alex", null,
                1554120000000L, 0L, "hello", NOW);

        Assertions.assertEquals(Instant.ofEpochMilli(1554120000000L), mail.sentAt());
    }

    @Test
    @DisplayName("a zero expiry never expires, a past one does, a future one does not")
    void expiry() {
        long now = NOW.toEpochMilli();
        Assertions.assertFalse(
                ImportedMail.of(RECIPIENT, false, false, "A", null, 1L, 0L, "m", NOW).expired());
        Assertions.assertTrue(
                ImportedMail.of(RECIPIENT, false, false, "A", null, 1L, now - 1L, "m", NOW).expired());
        Assertions.assertFalse(
                ImportedMail.of(RECIPIENT, false, false, "A", null, 1L, now + 1L, "m", NOW).expired());
    }

    @Test
    @DisplayName("a message with no colour codes survives untouched")
    void noColourCodes() {
        Assertions.assertEquals("plain text", ImportedMail.stripColourCodes("plain text"));
    }

    @Test
    @DisplayName("a trailing lone section sign is dropped rather than throwing")
    void trailingSectionSign() {
        Assertions.assertEquals("hi", ImportedMail.stripColourCodes("hi§"));
    }

    @Test
    @DisplayName("a blank message is carried through rather than rejected")
    void blankMessageIsCarried() {
        ImportedMail mail = ImportedMail.of(RECIPIENT, false, false, "A", null, 1L, 0L, "   ", NOW);

        Assertions.assertEquals("   ", mail.message());
    }
}
