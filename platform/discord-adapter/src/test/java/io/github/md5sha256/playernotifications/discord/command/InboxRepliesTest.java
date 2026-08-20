package io.github.md5sha256.playernotifications.discord.command;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InboxRepliesTest {

    @Test
    void anOutOfRangeEntryIsToldWhatThePageActuallyHolds() {
        // The player asked for a row that is not there, which means the page changed under them; the
        // reply has to say what it does hold or they cannot tell what to ask for instead.
        String reply = InboxReplies.outOfRange(9, 3);

        Assertions.assertTrue(reply.contains("9"));
        Assertions.assertTrue(reply.contains("3"));
    }

    @Test
    void anEmptyPageSaysSoRatherThanOfferingRowZero() {
        Assertions.assertFalse(InboxReplies.outOfRange(1, 0).contains("0"),
                "\"it has 0\" reads as a bug; an empty page is its own sentence");
    }

    @Test
    void anOkActionRepliesWithItsOwnMessage() {
        Assertions.assertEquals("Dismissed.",
                InboxReplies.of(new InboxView.ActionResult.Ok("Dismissed.")));
    }

    @Test
    void anOutOfRangeActionRepliesWithTheRangeMessage() {
        Assertions.assertEquals(InboxReplies.outOfRange(4, 2),
                InboxReplies.of(new InboxView.ActionResult.OutOfRange(4, 2)));
    }
}
