package io.github.md5sha256.playernotifications.discord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

class DiscordLinkFlowTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long DISCORD_ID = 123456789012345678L;

    private static final Logger LOGGER = Logger.getLogger(DiscordLinkFlowTest.class.getName());

    static {
        // The failure-path tests deliberately provoke logged warnings; keep them out of the build output.
        LOGGER.setLevel(Level.OFF);
    }

    /** A store whose every operation fails, standing in for a database outage. */
    private static final class ThrowingStore implements DiscordAccountLinkStore {

        @Override
        public Optional<Long> discordIdFor(UUID playerUuid) {
            throw new PersistenceException("boom");
        }

        @Override
        public Optional<UUID> playerFor(long discordId) {
            throw new PersistenceException("boom");
        }

        @Override
        public void link(UUID playerUuid, long discordId) {
            throw new PersistenceException("boom");
        }

        @Override
        public boolean unlink(UUID playerUuid) {
            throw new PersistenceException("boom");
        }
    }

    private MutableClock clock;
    private FakeDiscordAccountLinkStore store;
    private LinkCodeService codes;
    private DiscordLinkFlow flow;

    @BeforeEach
    void setUp() {
        this.clock = MutableClock.atEpoch();
        this.store = new FakeDiscordAccountLinkStore();
        this.codes = new LinkCodeService(TTL, this.clock);
        this.flow = new DiscordLinkFlow(this.store, this.codes, LOGGER);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String lower(Component component) {
        return plain(component).toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("begin issues a code and tells the player how to redeem it")
    void beginIssuesACodeAndExplainsIt() {
        UUID player = UUID.randomUUID();

        String text = plain(this.flow.begin(player));

        Assertions.assertTrue(this.codes.hasOutstandingCode(player));
        Assertions.assertTrue(text.contains("/link"), text);
        Assertions.assertTrue(text.contains("10 minutes"), text);
    }

    @Test
    @DisplayName("begin refuses when the player is already linked")
    void beginRefusesWhenAlreadyLinked() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        String text = lower(this.flow.begin(player));

        // Re-linking silently would move a player's notifications to a new account with no confirmation
        // that the old one was dropped.
        Assertions.assertFalse(this.codes.hasOutstandingCode(player), "no code should be issued");
        Assertions.assertTrue(text.contains("already"), text);
        Assertions.assertTrue(text.contains("unlink"), text);
    }

    @Test
    @DisplayName("status reports the linked Discord id")
    void statusReportsTheLinkedId() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        Assertions.assertTrue(plain(this.flow.status(player)).contains(Long.toString(DISCORD_ID)));
    }

    @Test
    @DisplayName("status reports the absence of a link")
    void statusReportsNoLink() {
        Assertions.assertTrue(lower(this.flow.status(UUID.randomUUID())).contains("not linked"));
    }

    @Test
    @DisplayName("status mentions an outstanding code")
    void statusMentionsAnOutstandingCode() {
        UUID player = UUID.randomUUID();
        this.flow.begin(player);

        Assertions.assertTrue(lower(this.flow.status(player)).contains("code"));
    }

    @Test
    @DisplayName("unlink removes the link and then reports there is none")
    void unlinkRemovesTheLink() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        Assertions.assertTrue(lower(this.flow.unlink(player)).contains("unlinked"));
        Assertions.assertEquals(Optional.empty(), this.store.discordIdFor(player));
        Assertions.assertTrue(lower(this.flow.unlink(player)).contains("not linked"));
    }

    @Test
    @DisplayName("unlink cancels an outstanding code")
    void unlinkCancelsAnOutstandingCode() {
        UUID player = UUID.randomUUID();
        this.flow.begin(player);

        this.flow.unlink(player);

        Assertions.assertFalse(this.codes.hasOutstandingCode(player));
    }

    @Test
    @DisplayName("redeeming a valid code writes the link")
    void redeemLinksAValidCode() {
        UUID player = UUID.randomUUID();
        String code = this.codes.issue(player);

        Assertions.assertEquals(DiscordLinkFlow.RedeemResult.LINKED, this.flow.redeem(code, DISCORD_ID));
        Assertions.assertEquals(Optional.of(DISCORD_ID), this.store.discordIdFor(player));
    }

    @Test
    @DisplayName("an unknown code is rejected")
    void redeemRejectsAnUnknownCode() {
        Assertions.assertEquals(DiscordLinkFlow.RedeemResult.UNKNOWN_CODE,
                this.flow.redeem("ZZZZZZ", DISCORD_ID));
    }

    @Test
    @DisplayName("an expired code is rejected")
    void redeemRejectsAnExpiredCode() {
        String code = this.codes.issue(UUID.randomUUID());
        this.clock.advance(TTL.plusSeconds(1));

        Assertions.assertEquals(DiscordLinkFlow.RedeemResult.UNKNOWN_CODE,
                this.flow.redeem(code, DISCORD_ID));
    }

    @Test
    @DisplayName("redeeming for the account already linked reports it rather than rewriting")
    void redeemReportsAnExistingIdenticalLink() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);
        // Issued directly, bypassing begin()'s already-linked guard, which is the only way to reach this.
        String code = this.codes.issue(player);

        Assertions.assertEquals(DiscordLinkFlow.RedeemResult.ALREADY_LINKED_TO_THIS_ACCOUNT,
                this.flow.redeem(code, DISCORD_ID));
        Assertions.assertEquals(Optional.of(DISCORD_ID), this.store.discordIdFor(player));
    }

    @Test
    @DisplayName("redeeming for a different account replaces the existing link")
    void redeemReplacesALinkToADifferentAccount() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);
        String code = this.codes.issue(player);

        Assertions.assertEquals(DiscordLinkFlow.RedeemResult.LINKED,
                this.flow.redeem(code, DISCORD_ID + 1));
        Assertions.assertEquals(Optional.of(DISCORD_ID + 1), this.store.discordIdFor(player));
    }

    @Test
    @DisplayName("a database failure during redemption is reported, not propagated")
    void redeemReportsFailureWhenTheStoreThrows() {
        DiscordLinkFlow failing = new DiscordLinkFlow(new ThrowingStore(), this.codes, LOGGER);
        String code = this.codes.issue(UUID.randomUUID());

        Assertions.assertEquals(DiscordLinkFlow.RedeemResult.FAILED, failing.redeem(code, DISCORD_ID));
    }

    @Test
    @DisplayName("every command path survives a database failure")
    void commandsSurviveAThrowingStore() {
        DiscordLinkFlow failing = new DiscordLinkFlow(new ThrowingStore(), this.codes, LOGGER);
        UUID player = UUID.randomUUID();

        // A command and an interaction both need a reply, so nothing here may escape to the caller.
        Assertions.assertDoesNotThrow(() -> failing.begin(player));
        Assertions.assertDoesNotThrow(() -> failing.status(player));
        Assertions.assertDoesNotThrow(() -> failing.unlink(player));
    }
}
