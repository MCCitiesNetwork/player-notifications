package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link NotificationPreferences} backed by the {@code PlayerNotificationPreference} table. A player
 * with no rows falls back to a configurable default medium set, so existing players are not silently
 * cut off from all notifications.
 */
public class DatabaseNotificationPreferences implements NotificationPreferences {

    private final Database database;
    private final Set<String> defaultMedia;

    public DatabaseNotificationPreferences(@NotNull Database database, @NotNull Collection<String> defaultMedia) {
        this.database = database;
        this.defaultMedia = Set.copyOf(defaultMedia);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            List<String> stored = wrapper.playerNotificationPreferenceMapper().selectByPlayer(player);
            if (stored.isEmpty()) {
                return this.defaultMedia;
            }
            return Set.copyOf(stored);
        }
    }

    /**
     * Wholesale-replaces the given player's preferred media: deletes any existing rows and inserts the
     * given set in the same transaction. Passing an empty set clears the player's preferences, so
     * {@link #preferredMedia(UUID)} subsequently falls back to the configured default media.
     */
    public void setPreferredMedia(@NotNull UUID player, @NotNull Set<String> media) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            mapper.deleteByPlayer(player);
            if (!media.isEmpty()) {
                mapper.insertPreferences(player, media);
            }
            wrapper.session().commit();
        }
    }
}
