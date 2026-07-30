package io.github.md5sha256.playernotifications.discord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the one piece of {@link DiscordLinkCommand} that is testable without a server: deciding which
 * known-commands keys belong to this module.
 *
 * <p>Worth its own test because the cost of getting it wrong is asymmetric — a key wrongly matched removes
 * <em>another plugin's</em> command from a running server during our shutdown.
 */
class DiscordLinkCommandTest {

    @Test
    @DisplayName("the literal and its alias match, with or without a plugin namespace")
    void matchesOurOwnKeys() {
        Assertions.assertTrue(DiscordLinkCommand.isOurs("discordlink"));
        Assertions.assertTrue(DiscordLinkCommand.isOurs("dlink"));
        // Paper registers a plugin-namespaced form alongside the bare one, and both must go.
        Assertions.assertTrue(DiscordLinkCommand.isOurs("playernotifications:discordlink"));
        Assertions.assertTrue(DiscordLinkCommand.isOurs("playernotifications:dlink"));
    }

    @Test
    @DisplayName("matching ignores case")
    void matchingIsCaseInsensitive() {
        Assertions.assertTrue(DiscordLinkCommand.isOurs("DiscordLink"));
        Assertions.assertTrue(DiscordLinkCommand.isOurs("PlayerNotifications:DISCORDLINK"));
    }

    @Test
    @DisplayName("another plugin's commands are never matched")
    void doesNotMatchOtherCommands() {
        Assertions.assertFalse(DiscordLinkCommand.isOurs("notifications"));
        Assertions.assertFalse(DiscordLinkCommand.isOurs("link"));
        Assertions.assertFalse(DiscordLinkCommand.isOurs("discord"));
        Assertions.assertFalse(DiscordLinkCommand.isOurs("discordsrv:discord"));
        Assertions.assertFalse(DiscordLinkCommand.isOurs(""));
    }

    @Test
    @DisplayName("a command whose name merely contains ours is not matched")
    void doesNotMatchOnSubstrings() {
        // "unregister everything that looks a bit like ours" would take out a third-party command.
        Assertions.assertFalse(DiscordLinkCommand.isOurs("discordlinker"));
        Assertions.assertFalse(DiscordLinkCommand.isOurs("mydiscordlink"));
        Assertions.assertFalse(DiscordLinkCommand.isOurs("other:discordlink-admin"));
    }
}
