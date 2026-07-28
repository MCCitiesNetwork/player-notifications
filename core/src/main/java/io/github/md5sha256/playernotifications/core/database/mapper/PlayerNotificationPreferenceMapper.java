package io.github.md5sha256.playernotifications.core.database.mapper;

import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code PlayerNotificationPreference} table, which
 * stores a player's preferred media per category as rows sharing a {@code playerUuid}. SQL annotations
 * are supplied by database-specific sub-interfaces.
 */
public interface PlayerNotificationPreferenceMapper {

    /**
     * Every stored row for the player, across every category (including the {@code *} fallback).
     */
    @NotNull List<PlayerNotificationPreferenceEntity> selectByPlayer(@NotNull UUID playerUuid);

    /**
     * The media stored for exactly the given category. Empty if the player has no rows for that exact
     * category — callers apply {@code *}/default fallback themselves.
     */
    @NotNull List<String> selectByPlayerAndCategory(@NotNull UUID playerUuid, @NotNull String category);

    /**
     * Inserts every given medium as a preference row for the player and category in a single multi-row
     * statement. The caller must ensure {@code media} is non-empty; an empty collection would produce
     * invalid SQL.
     *
     * @return the number of rows inserted
     */
    int insertPreferences(@NotNull UUID playerUuid, @NotNull String category, @NotNull Collection<String> media);

    /**
     * Deletes every preference row for the given player and category, e.g. before replacing them
     * wholesale or resetting the category to the server default.
     *
     * @return the number of rows removed
     */
    int deleteByPlayerAndCategory(@NotNull UUID playerUuid, @NotNull String category);

    /**
     * Deletes every preference row for the given player across all categories.
     *
     * @return the number of rows removed
     */
    int deleteByPlayer(@NotNull UUID playerUuid);

}
