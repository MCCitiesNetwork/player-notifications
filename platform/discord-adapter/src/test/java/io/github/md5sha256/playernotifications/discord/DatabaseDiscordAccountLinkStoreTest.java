package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.discord.schema.DiscordSchemaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The link store against a real MariaDB. Previously listed as untested: its two-sided {@code link}
 * replacement is exactly the behaviour only a real unique index can verify, since a fake that simply
 * accepts the write cannot tell a correct implementation from one that would hit the constraint.
 */
class DatabaseDiscordAccountLinkStoreTest {

    private static final long DISCORD_ID = 123456789012345678L;

    private Database database;
    private DatabaseDiscordAccountLinkStore store;

    @BeforeEach
    void setUp() throws Exception {
        this.database = DiscordSchemaTestSupport.migratedDatabase();
        this.store = new DatabaseDiscordAccountLinkStore(this.database, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception {
        this.database.close();
    }

    @Test
    @DisplayName("a link round-trips under both keys")
    void linkRoundTrips() {
        UUID player = UUID.randomUUID();

        this.store.link(player, DISCORD_ID);

        Assertions.assertEquals(Optional.of(DISCORD_ID), this.store.discordIdFor(player));
        Assertions.assertEquals(Optional.of(player), this.store.playerFor(DISCORD_ID));
    }

    @Test
    @DisplayName("an absent link resolves to empty from either side")
    void absentLinksResolveToEmpty() {
        Assertions.assertEquals(Optional.empty(), this.store.discordIdFor(UUID.randomUUID()));
        Assertions.assertEquals(Optional.empty(), this.store.playerFor(DISCORD_ID));
    }

    @Test
    @DisplayName("link moves a Discord id held by another player")
    void linkReplacesTheDiscordIdSide() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        this.store.link(first, DISCORD_ID);

        // Against the real unique index: clearing only the player side would fail on the constraint here.
        this.store.link(second, DISCORD_ID);

        Assertions.assertEquals(Optional.empty(), this.store.discordIdFor(first));
        Assertions.assertEquals(Optional.of(second), this.store.playerFor(DISCORD_ID));
    }

    @Test
    @DisplayName("link replaces a player's previous Discord id")
    void linkReplacesThePlayerSide() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        // Clearing only the discordId side would fail on the primary key here.
        this.store.link(player, DISCORD_ID + 1);

        Assertions.assertEquals(Optional.empty(), this.store.playerFor(DISCORD_ID));
        Assertions.assertEquals(Optional.of(DISCORD_ID + 1), this.store.discordIdFor(player));
    }

    @Test
    @DisplayName("re-linking the same pair is harmless")
    void relinkingTheSamePairIsIdempotent() {
        UUID player = UUID.randomUUID();

        this.store.link(player, DISCORD_ID);
        this.store.link(player, DISCORD_ID);

        Assertions.assertEquals(Optional.of(DISCORD_ID), this.store.discordIdFor(player));
    }

    @Test
    @DisplayName("unlink reports whether a row existed")
    void unlinkReportsWhetherARowExisted() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        Assertions.assertTrue(this.store.unlink(player));
        Assertions.assertFalse(this.store.unlink(player));
        Assertions.assertEquals(Optional.empty(), this.store.discordIdFor(player));
    }

    @Test
    @DisplayName("a write is committed, not left in an open transaction")
    void writesAreCommitted() {
        UUID player = UUID.randomUUID();
        this.store.link(player, DISCORD_ID);

        // A fresh store over the same database reads its own session, so an uncommitted write is invisible.
        DatabaseDiscordAccountLinkStore other =
                new DatabaseDiscordAccountLinkStore(this.database, Clock.systemUTC());
        Assertions.assertEquals(Optional.of(DISCORD_ID), other.discordIdFor(player));
    }

    @Test
    @DisplayName("repeated use does not fail on duplicate mapper registration")
    void survivesRepeatedUse() {
        // addMapper throws when the type is already bound, and the store registers on every call, so this
        // would blow up on the second iteration without the hasMapper guard.
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            this.store.link(player, DISCORD_ID + i);
            Assertions.assertEquals(Optional.of(DISCORD_ID + i), this.store.discordIdFor(player));
        }
    }
}
