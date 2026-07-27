package io.github.md5sha256.playernotifications.core.database.mapper;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code PlayerNotificationPreference} table, which
 * stores a player's preferred media as a set of rows sharing a {@code playerUuid}. SQL annotations are
 * supplied by database-specific sub-interfaces.
 */
public interface PlayerNotificationPreferenceMapper {

    @NotNull List<String> selectByPlayer(@NotNull UUID playerUuid);

    /**
     * Inserts every given medium as a preference row for the player in a single multi-row statement.
     * The caller must ensure {@code media} is non-empty; an empty collection would produce invalid SQL.
     *
     * @return the number of rows inserted
     */
    int insertPreferences(@NotNull UUID playerUuid, @NotNull Collection<String> media);

    /**
     * Deletes every preference row for the given player, e.g. before replacing them wholesale.
     *
     * @return the number of rows removed
     */
    int deleteByPlayer(@NotNull UUID playerUuid);

}
