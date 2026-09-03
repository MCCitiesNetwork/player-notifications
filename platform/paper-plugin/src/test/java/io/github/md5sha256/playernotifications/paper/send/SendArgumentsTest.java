package io.github.md5sha256.playernotifications.paper.send;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SendArguments#parse}'s rules, which are {@code BroadcastArguments}' rules with one flag —
 * content ends at the first recognised flag token, and everything after it must be a flag.
 */
class SendArgumentsTest {

    private static SendArguments parsed(@NotNull String raw) {
        SendArguments.Result result = SendArguments.parse(raw);
        return assertInstanceOf(SendArguments.Result.Parsed.class, result).arguments();
    }

    @Test
    void plainContentIsPersistentByDefault() {
        SendArguments arguments = parsed("Server restarting");
        assertEquals("Server restarting", arguments.content());
        assertFalse(arguments.transientSend());
    }

    @Test
    void transientFlagIsRecognised() {
        SendArguments arguments = parsed("Server restarting --transient");
        assertEquals("Server restarting", arguments.content());
        assertTrue(arguments.transientSend());
    }

    @Test
    void interiorSpacingSurvives() {
        assertEquals("Hello   there  now", parsed("Hello   there  now --transient").content());
    }

    @Test
    void repeatedTransientIsIdempotent() {
        SendArguments arguments = parsed("Ping --transient --transient");
        assertEquals("Ping", arguments.content());
        assertTrue(arguments.transientSend());
    }

    @Test
    void blankInputIsRejected() {
        assertInstanceOf(SendArguments.Result.BlankContent.class, SendArguments.parse("   "));
    }

    @Test
    void flagWithNoContentIsRejected() {
        assertInstanceOf(SendArguments.Result.BlankContent.class, SendArguments.parse("--transient"));
    }

    @Test
    void unrecognisedFlagAfterTheContentIsNamed() {
        SendArguments.Result result = SendArguments.parse("Ping --transient --wat");
        assertEquals("--wat",
                assertInstanceOf(SendArguments.Result.UnrecognisedToken.class, result).token());
    }

    @Test
    void aLoneHyphenInTheContentIsNotAFlag() {
        assertEquals("Costs 5-10 diamonds", parsed("Costs 5-10 diamonds").content());
    }

    @Test
    void miniMessageInTheContentIsUntouched() {
        assertEquals("<red>Down in 5m", parsed("<red>Down in 5m --transient").content());
    }
}
