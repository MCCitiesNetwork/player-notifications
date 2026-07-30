package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.DiscordAccountLinkEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.DiscordAccountLinkMapper;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DiscordAccountLinkStore} over the host's {@code DiscordAccountLink} table.
 *
 * <p>One {@link SqlSessionWrapper} per call: these are one-shot operations driven by a command or a
 * Discord interaction, never part of a wider unit of work, and holding a session open between them would
 * pin a pooled connection for the length of a player's linking attempt.
 */
public final class DatabaseDiscordAccountLinkStore implements DiscordAccountLinkStore {

    private final Database database;
    private final Clock clock;

    public DatabaseDiscordAccountLinkStore(@NotNull Database database, @NotNull Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    @Override
    public @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            DiscordAccountLinkEntity link = wrapper.discordAccountLinkMapper().selectByPlayer(playerUuid);
            return Optional.ofNullable(link).map(DiscordAccountLinkEntity::discordId);
        }
    }

    @Override
    public @NotNull Optional<UUID> playerFor(long discordId) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            DiscordAccountLinkEntity link = wrapper.discordAccountLinkMapper().selectByDiscordId(discordId);
            return Optional.ofNullable(link).map(DiscordAccountLinkEntity::playerUuid);
        }
    }

    @Override
    public void link(@NotNull UUID playerUuid, long discordId) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            DiscordAccountLinkMapper mapper = wrapper.discordAccountLinkMapper();
            // Both sides in one transaction: the table is uniquely indexed on playerUuid and on
            // discordId, so a replacement that cleared only one of them would hit the other's
            // constraint.
            mapper.deleteByPlayer(playerUuid);
            mapper.deleteByDiscordId(discordId);
            // Truncated to seconds to match the DATETIME column, so a value read back equals the value
            // written rather than differing by a dropped fraction.
            mapper.insertLink(playerUuid, discordId, this.clock.instant().truncatedTo(ChronoUnit.SECONDS));
            wrapper.session().commit();
        }
    }

    @Override
    public boolean unlink(@NotNull UUID playerUuid) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            int removed = wrapper.discordAccountLinkMapper().deleteByPlayer(playerUuid);
            wrapper.session().commit();
            return removed > 0;
        }
    }
}
