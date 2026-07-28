package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;

/**
 * One row of the {@code PlayerNotificationPreference} table: a single medium a player has (explicitly
 * or via the {@code *} fallback) configured for one category.
 *
 * @param category the category key, or {@code *} for the pre-migration fallback row
 * @param medium   the medium key, or {@code none} for an explicit mute
 */
public record PlayerNotificationPreferenceEntity(
        @NotNull String category,
        @NotNull String medium
) {
}
