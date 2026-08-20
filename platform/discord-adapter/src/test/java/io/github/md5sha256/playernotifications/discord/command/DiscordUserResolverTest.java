package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.discord.DiscordAccountProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

class DiscordUserResolverTest {

    private static final Logger LOGGER = Logger.getLogger(DiscordUserResolverTest.class.getName());
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static DiscordUserResolver resolverOf(DiscordAccountProvider accounts) {
        return new DiscordUserResolver(accounts, LOGGER);
    }

    private static DiscordAccountProvider answering(UUID player) {
        return new DiscordAccountProvider() {
            @Override
            public String providerKey() {
                return "fake";
            }

            @Override
            public Optional<Long> discordIdFor(UUID playerUuid) {
                return Optional.empty();
            }

            @Override
            public Optional<UUID> playerFor(long discordId) {
                return Optional.ofNullable(player);
            }
        };
    }

    @Test
    void aLinkedDiscordAccountResolvesToItsPlayer() {
        Assertions.assertEquals(Optional.of(PLAYER), resolverOf(answering(PLAYER)).resolve(111L));
    }

    @Test
    void anUnlinkedDiscordAccountResolvesToEmpty() {
        Assertions.assertEquals(Optional.empty(), resolverOf(answering(null)).resolve(111L));
    }

    @Test
    void aFailingLookupIsContainedRatherThanEscapingIntoAnInteraction() {
        // An exception here would leave the interaction with no reply at all, which reads to the player
        // as the bot being dead rather than as their account being unlinked.
        DiscordAccountProvider throwing = new DiscordAccountProvider() {
            @Override
            public String providerKey() {
                return "throwing";
            }

            @Override
            public Optional<Long> discordIdFor(UUID playerUuid) {
                return Optional.empty();
            }

            @Override
            public Optional<UUID> playerFor(long discordId) {
                throw new IllegalStateException("boom");
            }
        };

        Assertions.assertEquals(Optional.empty(), resolverOf(throwing).resolve(111L));
    }

    @Test
    void theNotLinkedReplyNamesTheInGameLinkCommand() {
        Assertions.assertTrue(DiscordUserResolver.NOT_LINKED_MESSAGE.contains("/notifications link discord"),
                "an unlinked player needs to be told exactly what to run");
    }
}
