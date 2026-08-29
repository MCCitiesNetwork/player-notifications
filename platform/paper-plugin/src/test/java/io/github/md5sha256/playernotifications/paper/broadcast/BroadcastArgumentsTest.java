package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Rejected, without pinning which of the three rejection cases — the specific tests do that. */
    private static void assertInvalid(@NotNull String raw) {
        assertFalse(BroadcastArguments.parse(raw) instanceof BroadcastArguments.Result.Parsed,
                raw + " should have been rejected");
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
        BroadcastArguments.Result.FlagMissingValue missing =
                assertInstanceOf(BroadcastArguments.Result.FlagMissingValue.class, result);
        // The reply must name the flag, so the case carries it rather than a prebuilt sentence.
        assertEquals("--perm", missing.flag());
    }

    @Test
    void permFollowedByAnotherFlagIsInvalid() {
        assertInvalid("hi --perm --perm a");
    }

    @Test
    void junkTokenAfterFlagsIsInvalidNamingIt() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --perm a junk");
        BroadcastArguments.Result.UnrecognisedToken unrecognised =
                assertInstanceOf(BroadcastArguments.Result.UnrecognisedToken.class, result);
        assertEquals("junk", unrecognised.token());
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
        BroadcastArguments.Result.UnrecognisedToken unrecognised =
                assertInstanceOf(BroadcastArguments.Result.UnrecognisedToken.class, result);
        assertEquals("junk", unrecognised.token());
    }

    @Test
    void blankContentIsItsOwnRejectionCase() {
        assertInstanceOf(BroadcastArguments.Result.BlankContent.class,
                BroadcastArguments.parse("   "));
    }

    // --- Task 1: --chain, --persistent, --offline, --limit ---

    @Test
    void chainDefaultsToOrWhenAbsent() {
        assertEquals(BroadcastArguments.Chain.OR, parsed("hi --perm a").chain());
    }

    @Test
    void chainAndIsParsed() {
        assertEquals(BroadcastArguments.Chain.AND, parsed("hi --perm a --chain and").chain());
    }

    @Test
    void chainValueIsCaseInsensitive() {
        assertEquals(BroadcastArguments.Chain.OR, parsed("hi --perm a --chain OR").chain());
    }

    @Test
    void unknownChainValueIsRejectedNamingTheValue() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --chain sideways");
        BroadcastArguments.Result.UnknownChainValue unknown = assertInstanceOf(
                BroadcastArguments.Result.UnknownChainValue.class, result);
        assertEquals("sideways", unknown.value());
    }

    @Test
    void chainAsFinalTokenIsMissingValue() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --chain");
        assertEquals("--chain",
                assertInstanceOf(BroadcastArguments.Result.FlagMissingValue.class, result).flag());
    }

    @Test
    void persistentFlagIsParsed() {
        assertTrue(parsed("hi --persistent").persistent());
    }

    @Test
    void persistentDefaultsToFalse() {
        assertFalse(parsed("hi").persistent());
    }

    @Test
    void offlineWithoutPersistentIsRejected() {
        assertInstanceOf(BroadcastArguments.Result.OfflineRequiresPersistent.class,
                BroadcastArguments.parse("hi --offline --perm a"));
    }

    @Test
    void offlineWithoutPermissionIsRejected() {
        assertInstanceOf(BroadcastArguments.Result.OfflineRequiresPermission.class,
                BroadcastArguments.parse("hi --offline --persistent"));
    }

    @Test
    void offlineWithPersistentAndPermissionIsAccepted() {
        BroadcastArguments arguments = parsed("hi --offline --persistent --perm a");
        assertTrue(arguments.offline());
        assertTrue(arguments.persistent());
        assertEquals(List.of("a"), arguments.permissions());
    }

    @Test
    void limitIsParsed() {
        assertEquals(50, parsed("hi --limit 50").limit());
    }

    @Test
    void absentLimitIsNullMeaningUnlimited() {
        assertNull(parsed("hi").limit());
    }

    @Test
    void zeroLimitIsRejectedRatherThanMeaningUnlimited() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --limit 0");
        assertEquals("0",
                assertInstanceOf(BroadcastArguments.Result.InvalidLimitValue.class, result).value());
    }

    @Test
    void negativeLimitIsRejected() {
        assertInstanceOf(BroadcastArguments.Result.InvalidLimitValue.class,
                BroadcastArguments.parse("hi --limit -1"));
    }

    @Test
    void nonIntegerLimitIsRejectedNamingTheValue() {
        BroadcastArguments.Result result = BroadcastArguments.parse("hi --limit lots");
        assertEquals("lots",
                assertInstanceOf(BroadcastArguments.Result.InvalidLimitValue.class, result).value());
    }

    @Test
    void limitAsFinalTokenIsMissingValue() {
        assertEquals("--limit", assertInstanceOf(
                BroadcastArguments.Result.FlagMissingValue.class,
                BroadcastArguments.parse("hi --limit")).flag());
    }

    @Test
    void newFlagsCombineInAnyOrder() {
        BroadcastArguments arguments =
                parsed("hi --limit 5 --bypass --perm a --chain and --persistent --offline");
        assertEquals("hi", arguments.content());
        assertEquals(BroadcastArguments.Chain.AND, arguments.chain());
        assertEquals(5, arguments.limit());
        assertTrue(arguments.bypass());
        assertTrue(arguments.persistent());
        assertTrue(arguments.offline());
    }

    @Test
    void repeatedLimitTakesTheLastOccurrence() {
        assertEquals(9, parsed("hi --limit 3 --limit 9").limit());
    }

    @Test
    void contentEndsAtTheFirstNewFlagToken() {
        assertEquals("hi", parsed("hi --persistent").content());
    }

    @Test
    void aBareNewFlagTokenCannotAppearInContent() {
        assertInvalid("--offline");
    }
}
