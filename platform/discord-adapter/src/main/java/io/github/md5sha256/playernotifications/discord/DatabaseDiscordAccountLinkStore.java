package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.discord.schema.DiscordAccountLinkEntity;
import io.github.md5sha256.playernotifications.discord.schema.DiscordAccountLinkMapper;
import io.github.md5sha256.playernotifications.discord.schema.MariaDiscordAccountLinkMapper;
import org.apache.ibatis.session.Configuration;
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
            DiscordAccountLinkEntity link = mapper(wrapper).selectByPlayer(playerUuid);
            return Optional.ofNullable(link).map(DiscordAccountLinkEntity::discordId);
        }
    }

    @Override
    public @NotNull Optional<UUID> playerFor(long discordId) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            DiscordAccountLinkEntity link = mapper(wrapper).selectByDiscordId(discordId);
            return Optional.ofNullable(link).map(DiscordAccountLinkEntity::playerUuid);
        }
    }

    @Override
    public void link(@NotNull UUID playerUuid, long discordId) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            DiscordAccountLinkMapper mapper = mapper(wrapper);
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
            int removed = mapper(wrapper).deleteByPlayer(playerUuid);
            wrapper.session().commit();
            return removed > 0;
        }
    }

    /**
     * Resolves this module's mapper off the host's session, registering it on first use.
     *
     * <p>The mapper belongs to this module, so it is not in {@code MariaDatabase}'s hardcoded list and
     * {@link SqlSessionWrapper} has no accessor for it. Registration mutates the host's shared MyBatis
     * {@link Configuration}, which is the narrowest option available: MyBatis has no per-session mapper
     * scope.
     *
     * <p>The {@code hasMapper} check is required, not defensive — {@code addMapper} throws when the type is
     * already bound, and this runs on every store call.
     */
    private @NotNull DiscordAccountLinkMapper mapper(@NotNull SqlSessionWrapper wrapper) {
        Configuration configuration = wrapper.session().getConfiguration();
        if (!configuration.hasMapper(MariaDiscordAccountLinkMapper.class)) {
            configuration.addMapper(MariaDiscordAccountLinkMapper.class);
        }
        return wrapper.session().getMapper(MariaDiscordAccountLinkMapper.class);
    }
}
