package io.github.md5sha256.playernotifications.discord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The adapter between the host's {@link io.github.md5sha256.playernotifications.api.link.AccountLinkProvider}
 * contract and {@link DiscordLinkFlow}, which holds all the actual logic.
 */
class DiscordAccountLinkProviderTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private FakeDiscordAccountLinkStore store;
    private DiscordLinkFlow flow;
    private DiscordAccountLinkProvider provider;

    @BeforeEach
    void setUp() {
        this.store = new FakeDiscordAccountLinkStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-30T00:00:00Z"));
        LinkCodeService codes = new LinkCodeService(Duration.ofMinutes(10), clock);
        this.flow = new DiscordLinkFlow(this.store, codes,
                Logger.getLogger(DiscordAccountLinkProviderTest.class.getName()));
        this.provider = new DiscordAccountLinkProvider(this.flow);
    }

    private static @NotNull String plain(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("registers under the 'discord' provider key")
    void hasDiscordProviderKey() {
        Assertions.assertEquals("discord", this.provider.providerKey());
    }

    @Test
    @DisplayName("names itself Discord in player-facing text")
    void displaysAsDiscord() {
        Assertions.assertEquals("Discord", plain(this.provider.displayName()));
    }

    @Test
    @DisplayName("begin delegates to the flow and issues a code")
    void beginDelegates() {
        String reply = plain(this.provider.begin(PLAYER));

        // The flow's begin() is the only thing that issues a code, so its wording proves delegation.
        Assertions.assertTrue(reply.contains("Your Discord link code is"), reply);
    }

    @Test
    @DisplayName("status delegates to the flow and reports the stored link")
    void statusDelegates() {
        this.store.link(PLAYER, 12345L);

        String reply = plain(this.provider.status(PLAYER));

        Assertions.assertTrue(reply.contains("12345"), reply);
    }

    @Test
    @DisplayName("unlink delegates to the flow and removes the stored link")
    void unlinkDelegates() {
        this.store.link(PLAYER, 12345L);

        String reply = plain(this.provider.unlink(PLAYER));

        Assertions.assertTrue(reply.contains("unlinked"), reply);
        Assertions.assertTrue(this.store.discordIdFor(PLAYER).isEmpty());
    }

    @Test
    @DisplayName("unlink on an unlinked player says so rather than failing")
    void unlinkWithoutLinkIsExplained() {
        Assertions.assertTrue(plain(this.provider.unlink(PLAYER)).contains("not linked"));
    }
}
