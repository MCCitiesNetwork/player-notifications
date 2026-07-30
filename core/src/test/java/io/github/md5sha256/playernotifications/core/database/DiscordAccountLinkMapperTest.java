package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.core.database.entity.DiscordAccountLinkEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.DiscordAccountLinkMapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/**
 * Covers the {@code DiscordAccountLink} table added by migration V2 — the storage behind the Discord
 * adapter's {@code embedded} account-link provider.
 */
class DiscordAccountLinkMapperTest extends AbstractDatabaseTest {

    /** A realistic Discord snowflake: large enough to prove the column is not a 32-bit int. */
    private static final long DISCORD_ID = 123456789012345678L;

    @Test
    @DisplayName("insertLink then select round-trips the row under either key")
    void insertsAndReadsBackByBothKeys() {
        UUID player = UUID.randomUUID();
        // Whole seconds: the column is DATETIME, which does not keep sub-second precision.
        Instant linkedAt = Instant.parse("2026-07-30T12:00:00Z");

        try (SqlSessionWrapper wrapper = database.openSession();
             SqlSession session = wrapper.session()) {
            Assertions.assertEquals(1,
                    wrapper.discordAccountLinkMapper().insertLink(player, DISCORD_ID, linkedAt));
            session.commit();
        }

        try (SqlSessionWrapper wrapper = database.openSession()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();

            DiscordAccountLinkEntity byPlayer = mapper.selectByPlayer(player);
            Assertions.assertNotNull(byPlayer);
            Assertions.assertEquals(player, byPlayer.playerUuid());
            Assertions.assertEquals(DISCORD_ID, byPlayer.discordId());
            Assertions.assertEquals(linkedAt, byPlayer.linkedAt());

            DiscordAccountLinkEntity byDiscordId = mapper.selectByDiscordId(DISCORD_ID);
            Assertions.assertNotNull(byDiscordId);
            Assertions.assertEquals(player, byDiscordId.playerUuid());
        }
    }

    @Test
    @DisplayName("both selects return null when no link exists")
    void selectsReturnNullWhenAbsent() {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
            Assertions.assertNull(mapper.selectByPlayer(UUID.randomUUID()));
            Assertions.assertNull(mapper.selectByDiscordId(DISCORD_ID));
        }
    }

    @Test
    @DisplayName("the unique index rejects a second player for the same Discord id")
    void rejectsASecondPlayerForTheSameDiscordId() {
        try (SqlSessionWrapper wrapper = database.openSession();
             SqlSession session = wrapper.session()) {
            wrapper.discordAccountLinkMapper().insertLink(UUID.randomUUID(), DISCORD_ID, Instant.now());
            session.commit();
        }

        try (SqlSessionWrapper wrapper = database.openSession()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
            Assertions.assertThrows(PersistenceException.class,
                    () -> mapper.insertLink(UUID.randomUUID(), DISCORD_ID, Instant.now()));
        }
    }

    @Test
    @DisplayName("the primary key rejects a second Discord id for the same player")
    void rejectsASecondDiscordIdForTheSamePlayer() {
        UUID player = UUID.randomUUID();
        try (SqlSessionWrapper wrapper = database.openSession();
             SqlSession session = wrapper.session()) {
            wrapper.discordAccountLinkMapper().insertLink(player, DISCORD_ID, Instant.now());
            session.commit();
        }

        try (SqlSessionWrapper wrapper = database.openSession()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
            Assertions.assertThrows(PersistenceException.class,
                    () -> mapper.insertLink(player, DISCORD_ID + 1, Instant.now()));
        }
    }

    @Test
    @DisplayName("deleteByPlayer and deleteByDiscordId each remove exactly their own row")
    void deletesByEitherKey() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        try (SqlSessionWrapper wrapper = database.openSession();
             SqlSession session = wrapper.session()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
            mapper.insertLink(playerA, 333L, Instant.now());
            mapper.insertLink(playerB, 444L, Instant.now());
            session.commit();
        }

        try (SqlSessionWrapper wrapper = database.openSession();
             SqlSession session = wrapper.session()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
            Assertions.assertEquals(1, mapper.deleteByPlayer(playerA));
            Assertions.assertEquals(1, mapper.deleteByDiscordId(444L));
            Assertions.assertEquals(0, mapper.deleteByPlayer(UUID.randomUUID()));
            Assertions.assertEquals(0, mapper.deleteByDiscordId(999L));
            session.commit();
        }

        try (SqlSessionWrapper wrapper = database.openSession()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
            Assertions.assertNull(mapper.selectByPlayer(playerA));
            Assertions.assertNull(mapper.selectByPlayer(playerB));
        }
    }
}
