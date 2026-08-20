package io.github.md5sha256.playernotifications.discord.command;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class ComponentIdsTest {

    @Test
    void anIdRoundTripsWithNoArguments() {
        ComponentIds.Parsed parsed = ComponentIds.parse(
                        ComponentIds.encode(ComponentIds.SURFACE_PREFS, "apply"))
                .orElseThrow();

        Assertions.assertEquals(ComponentIds.SURFACE_PREFS, parsed.surface());
        Assertions.assertEquals("apply", parsed.action());
        Assertions.assertEquals(List.of(), parsed.args());
    }

    @Test
    void anIdRoundTripsWithSeveralArguments() {
        ComponentIds.Parsed parsed = ComponentIds.parse(
                        ComponentIds.encode(ComponentIds.SURFACE_INBOX, "page", "2", "mail", "x"))
                .orElseThrow();

        Assertions.assertEquals(ComponentIds.SURFACE_INBOX, parsed.surface());
        Assertions.assertEquals("page", parsed.action());
        Assertions.assertEquals(List.of("2", "mail", "x"), parsed.args());
    }

    @Test
    void anIdFromAnotherPluginIsNotOurs() {
        Assertions.assertEquals(Optional.empty(), ComponentIds.parse("somethingelse:button:3"));
        Assertions.assertEquals(Optional.empty(), ComponentIds.parse(""));
    }

    @Test
    void anIdWithNoActionIsMalformed() {
        Assertions.assertEquals(Optional.empty(), ComponentIds.parse("pn|inbox"));
    }

    @Test
    void argumentsAreAddressedByPosition() {
        ComponentIds.Parsed parsed =
                ComponentIds.parse(ComponentIds.encode(ComponentIds.SURFACE_INBOX, "read", "3", "mail-1"))
                        .orElseThrow();

        Assertions.assertEquals(Optional.of("mail-1"), parsed.arg(1));
        Assertions.assertEquals(Optional.empty(), parsed.arg(7));
        Assertions.assertEquals(3, parsed.intArg(0).orElseThrow());
        Assertions.assertTrue(parsed.intArg(1).isEmpty(), "a non-numeric argument is not an int");
        Assertions.assertTrue(parsed.intArg(7).isEmpty(), "an absent argument is not an int");
    }

    @Test
    void anArgumentContainingTheDelimiterIsRejected() {
        // Notification keys are "mail-<uuid>" and registry data types; none contains the delimiter, so
        // rejecting is safe and cheaper than an escaping scheme nothing would exercise.
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ComponentIds.encode(ComponentIds.SURFACE_INBOX, "read", "a|b"));
    }

    @Test
    void anIdTooLongForDiscordIsRejected() {
        // Discord caps a custom id at 100 characters and rejects the whole message otherwise, which
        // would surface as a failed interaction rather than as a missing button.
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ComponentIds.encode(ComponentIds.SURFACE_INBOX, "read", "x".repeat(120)));
    }
}
