package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MailRecipients#resolve} against a map-backed resolver, so the recipient/message rules are
 * testable without a live server — the same device {@code TestNotificationRenderer.usingServerNames()}
 * uses.
 */
class MailRecipientsTest {

    private static final UUID STEVE = UUID.randomUUID();

    private final Function<String, UUID> resolver = name -> "Steve".equals(name) ? STEVE : null;

    @Test
    void knownNameAndNormalMessageResolvesOk() {
        MailRecipients.Result result = MailRecipients.resolve("Steve", "  hello there  ", this.resolver);
        MailRecipients.Result.Ok ok = assertInstanceOf(MailRecipients.Result.Ok.class, result);
        assertEquals(STEVE, ok.recipient());
        assertEquals("hello there", ok.message());
    }

    @Test
    void unknownNameGivesUnknownPlayerNamingIt() {
        MailRecipients.Result result = MailRecipients.resolve("Nobody", "hello", this.resolver);
        MailRecipients.Result.UnknownPlayer unknown =
                assertInstanceOf(MailRecipients.Result.UnknownPlayer.class, result);
        assertEquals("Nobody", unknown.name());
    }

    @Test
    void blankMessageGivesInvalidMessage() {
        MailRecipients.Result result = MailRecipients.resolve("Steve", "   ", this.resolver);
        assertInstanceOf(MailRecipients.Result.InvalidMessage.class, result);
    }

    @Test
    void exactlyMaxLengthMessageIsOk() {
        String message = "a".repeat(MailPayload.MAX_MESSAGE_LENGTH);
        MailRecipients.Result result = MailRecipients.resolve("Steve", message, this.resolver);
        MailRecipients.Result.Ok ok = assertInstanceOf(MailRecipients.Result.Ok.class, result);
        assertEquals(message, ok.message());
    }

    @Test
    void oneCharacterOverMaxLengthIsInvalidAndNotTruncated() {
        String message = "a".repeat(MailPayload.MAX_MESSAGE_LENGTH + 1);
        MailRecipients.Result result = MailRecipients.resolve("Steve", message, this.resolver);
        assertInstanceOf(MailRecipients.Result.InvalidMessage.class, result);
    }

    @Test
    void resolverReceivesTheGivenName() {
        Map<String, UUID> map = Map.of("Alex", UUID.randomUUID());
        MailRecipients.Result result = MailRecipients.resolve("Alex", "hi", map::get);
        assertTrue(result instanceof MailRecipients.Result.Ok);
    }
}
