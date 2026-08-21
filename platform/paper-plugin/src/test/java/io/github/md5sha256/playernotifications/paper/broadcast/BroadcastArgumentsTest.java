package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BroadcastArguments#parse}'s flag-splitting rules — see the class javadoc for why the first
 * flag token, not the last, decides where the content ends.
 */
class BroadcastArgumentsTest {

    private static BroadcastArguments parsed(@NotNull String raw) {
        BroadcastArguments.Result result = BroadcastArguments.parse(raw);
        return assertInstanceOf(BroadcastArguments.Result.Parsed.class, result).arguments();
    }

    private static void assertInvalid(@NotNull String raw) {
        assertInstanceOf(BroadcastArguments.Result.Invalid.class, BroadcastArguments.parse(raw));
    }

    @Test
    void plainContentWithNoFlags() {
        BroadcastArguments arguments = parsed("hello world");
        assertEquals("hello world", arguments.content());
        assertTrue(arguments.permissions().isEmpty());
        assertFalse(arguments.bypass());
    }

    @Test
    void singlePermission() {
        BroadcastArguments arguments = parsed("hi --perm a.b");
        assertEquals("hi", arguments.content());
        assertEquals(List.of("a.b"), arguments.permissions());
    }

    @Test
    void multiplePermissions() {
        BroadcastArguments arguments = parsed("hi --perm a --perm b");
        assertEquals(List.of("a", "b"), arguments.permissions());
    }

    @Test
    void duplicatePermissionsAreDeduplicatedPreservingOrder() {
        BroadcastArguments arguments = parsed("hi --perm a --perm a");
        assertEquals(List.of("a"), arguments.permissions());
    }

    @Test
    void permAsFinalTokenIsInvalidMentioningPerm() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --perm");
        BroadcastArguments.Result.Invalid invalid =
                assertInstanceOf(BroadcastArguments.Result.Invalid.class, result);
        assertTrue(invalid.message().contains("--perm"), invalid.message());
    }

    @Test
    void permFollowedByAnotherFlagIsInvalid() {
        assertInvalid("hi --perm --perm a");
    }

    @Test
    void junkTokenAfterFlagsIsInvalidNamingIt() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --perm a junk");
        BroadcastArguments.Result.Invalid invalid =
                assertInstanceOf(BroadcastArguments.Result.Invalid.class, result);
        assertTrue(invalid.message().contains("junk"), invalid.message());
    }

    @Test
    void blankContentBeforeFlagsIsInvalid() {
        assertInvalid("--perm a");
    }

    @Test
    void allWhitespaceIsInvalid() {
        assertInvalid("   ");
    }

    @Test
    void interiorSpacingInContentSurvivesTrailingTrimmed() {
        BroadcastArguments arguments = parsed("hi   there  --perm  a");
        assertEquals("hi   there", arguments.content());
    }

    @Test
    void bareBypassFlag() {
        BroadcastArguments arguments = parsed("hi --bypass");
        assertEquals("hi", arguments.content());
        assertTrue(arguments.permissions().isEmpty());
        assertTrue(arguments.bypass());
    }

    @Test
    void bypassBeforePerm() {
        BroadcastArguments arguments = parsed("hi --bypass --perm a");
        assertEquals("hi", arguments.content());
        assertEquals(List.of("a"), arguments.permissions());
        assertTrue(arguments.bypass());
    }

    @Test
    void permBeforeBypass() {
        BroadcastArguments arguments = parsed("hi --perm a --bypass");
        assertEquals("hi", arguments.content());
        assertEquals(List.of("a"), arguments.permissions());
        assertTrue(arguments.bypass());
    }

    @Test
    void repeatedBypassIsIdempotentNotAnError() {
        BroadcastArguments arguments = parsed("hi --bypass --bypass");
        assertTrue(arguments.bypass());
    }

    @Test
    void noFlagsMeansBypassFalse() {
        BroadcastArguments arguments = parsed("hi");
        assertFalse(arguments.bypass());
    }

    @Test
    void bypassAsFirstTokenLeavesBlankContent() {
        assertInvalid("--bypass hi");
    }

    @Test
    void junkAfterBypassIsInvalidNamingIt() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --bypass junk");
        BroadcastArguments.Result.Invalid invalid =
                assertInstanceOf(BroadcastArguments.Result.Invalid.class, result);
        assertTrue(invalid.message().contains("junk"), invalid.message());
    }
}
