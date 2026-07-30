package io.github.md5sha256.playernotifications.discord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

class EmbeddedDiscordAccountProviderTest {

    private static final long DISCORD_ID = 987654321098765432L;

    @Test
    void reportsItsConfigKeyAndIsAlwaysAvailable() {
        EmbeddedDiscordAccountProvider provider =
                new EmbeddedDiscordAccountProvider(new FakeDiscordAccountLinkStore());

        Assertions.assertEquals("embedded", provider.providerKey());
        Assertions.assertEquals(EmbeddedDiscordAccountProvider.PROVIDER_KEY, provider.providerKey());
        // The database is a hard dependency of the host, so unlike DiscordSRV this provider is never
        // "not installed" — it must not be skipped un-queried.
        Assertions.assertTrue(provider.isAvailable());
    }

    @Test
    void resolvesALinkedPlayer() {
        FakeDiscordAccountLinkStore store = new FakeDiscordAccountLinkStore();
        UUID player = UUID.randomUUID();
        store.link(player, DISCORD_ID);

        Assertions.assertEquals(Optional.of(DISCORD_ID),
                new EmbeddedDiscordAccountProvider(store).discordIdFor(player));
    }

    @Test
    void returnsEmptyForAnUnlinkedPlayer() {
        Assertions.assertEquals(Optional.empty(),
                new EmbeddedDiscordAccountProvider(new FakeDiscordAccountLinkStore())
                        .discordIdFor(UUID.randomUUID()));
    }

    @Test
    void linkingADiscordIdAlreadyHeldByAnotherPlayerMovesIt() {
        FakeDiscordAccountLinkStore store = new FakeDiscordAccountLinkStore();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        store.link(first, DISCORD_ID);
        store.link(second, DISCORD_ID);

        Assertions.assertEquals(Optional.empty(), store.discordIdFor(first));
        Assertions.assertEquals(Optional.of(DISCORD_ID), store.discordIdFor(second));
        Assertions.assertEquals(Optional.of(second), store.playerFor(DISCORD_ID));
    }

    @Test
    void relinkingAPlayerToANewDiscordIdReleasesTheOldOne() {
        FakeDiscordAccountLinkStore store = new FakeDiscordAccountLinkStore();
        UUID player = UUID.randomUUID();

        store.link(player, DISCORD_ID);
        store.link(player, DISCORD_ID + 1);

        Assertions.assertEquals(Optional.empty(), store.playerFor(DISCORD_ID));
        Assertions.assertEquals(Optional.of(player), store.playerFor(DISCORD_ID + 1));
        Assertions.assertEquals(Optional.of(DISCORD_ID + 1), store.discordIdFor(player));
    }

    @Test
    void unlinkReportsWhetherARowExisted() {
        FakeDiscordAccountLinkStore store = new FakeDiscordAccountLinkStore();
        UUID player = UUID.randomUUID();
        store.link(player, DISCORD_ID);

        Assertions.assertTrue(store.unlink(player));
        Assertions.assertFalse(store.unlink(player));
        Assertions.assertEquals(Optional.empty(), store.discordIdFor(player));
    }
}
