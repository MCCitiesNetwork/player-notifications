package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link NotificationPreferences} backed by the {@code PlayerNotificationPreference} table, resolved
 * per notification category. A player with no rows for a category falls back to the {@link
 * #ALL_CATEGORIES_KEY} rows (a blanket choice), then to a configurable default medium set, so a player
 * is never silently cut off from all notifications.
 */
public class DatabaseNotificationPreferences implements NotificationPreferences {

    /**
     * Reserved category key meaning "applies to any category not otherwise configured" — a blanket
     * choice. Nothing in the dialogs writes it directly.
     */
    public static final String ALL_CATEGORIES_KEY = "*";

    private final Database database;
    private volatile Set<String> defaultMedia;

    public DatabaseNotificationPreferences(@NotNull Database database, @NotNull Collection<String> defaultMedia) {
        this.database = database;
        this.defaultMedia = Set.copyOf(defaultMedia);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
        return preferredMedia(player, ALL_CATEGORIES_KEY);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String category) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            if (!ALL_CATEGORIES_KEY.equals(category)) {
                List<String> exact = mapper.selectByPlayerAndCategory(player, category);
                if (!exact.isEmpty()) {
                    return Set.copyOf(exact);
                }
            }
            List<String> fallback = mapper.selectByPlayerAndCategory(player, ALL_CATEGORIES_KEY);
            if (!fallback.isEmpty()) {
                return Set.copyOf(fallback);
            }
            return this.defaultMedia;
        }
    }

    /**
     * Resolves the effective media for every given category in one database round trip: exact rows,
     * else the {@link #ALL_CATEGORIES_KEY} fallback, else the configured default. Used to load a
     * preference edit session's starting matrix.
     */
    public @NotNull Map<String, Set<String>> effectiveMediaByCategory(@NotNull UUID player,
                                                                       @NotNull Set<String> categoryKeys) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byCategory = groupByCategory(wrapper, player);
            Set<String> fallback = byCategory.getOrDefault(ALL_CATEGORIES_KEY, this.defaultMedia);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String category : categoryKeys) {
                Set<String> exact = byCategory.get(category);
                result.put(category, exact != null ? Set.copyOf(exact) : Set.copyOf(fallback));
            }
            return Map.copyOf(result);
        }
    }

    /**
     * Which of the given categories the player has exact stored rows for, as opposed to inheriting the
     * {@link #ALL_CATEGORIES_KEY} fallback or the configured default. Used to label a category
     * "server default" in the preferences dialogs.
     */
    public @NotNull Set<String> explicitlyConfiguredCategories(@NotNull UUID player,
                                                                @NotNull Set<String> categoryKeys) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byCategory = groupByCategory(wrapper, player);
            Set<String> configured = new HashSet<>();
            for (String category : categoryKeys) {
                if (byCategory.containsKey(category)) {
                    configured.add(category);
                }
            }
            return Set.copyOf(configured);
        }
    }

    private static @NotNull Map<String, Set<String>> groupByCategory(@NotNull SqlSessionWrapper wrapper,
                                                                       @NotNull UUID player) {
        List<PlayerNotificationPreferenceEntity> rows =
                wrapper.playerNotificationPreferenceMapper().selectByPlayer(player);
        Map<String, Set<String>> byCategory = new HashMap<>();
        for (PlayerNotificationPreferenceEntity row : rows) {
            byCategory.computeIfAbsent(row.category(), key -> new TreeSet<>()).add(row.medium());
        }
        return byCategory;
    }

    /**
     * Applies a batch of staged changes in one transaction. Categories in {@code categoriesToReset} have
     * their rows deleted, falling back to {@link #ALL_CATEGORIES_KEY}/the configured default again.
     * Every entry in {@code explicitMedia} wholesale-replaces that category's rows; an empty set is not
     * a valid value here — callers encode a mute as {@code {"none"}}.
     */
    public void applyChanges(@NotNull UUID player,
                             @NotNull Map<String, Set<String>> explicitMedia,
                             @NotNull Set<String> categoriesToReset) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            for (String category : categoriesToReset) {
                mapper.deleteByPlayerAndCategory(player, category);
            }
            for (Map.Entry<String, Set<String>> entry : explicitMedia.entrySet()) {
                mapper.deleteByPlayerAndCategory(player, entry.getKey());
                mapper.insertPreferences(player, entry.getKey(), entry.getValue());
            }
            wrapper.session().commit();
        }
    }

    /**
     * Immediately mutes every given category for the player in one transaction, storing an explicit
     * {@code none} row for each. Used by {@code /notifications mute}.
     */
    public void muteAll(@NotNull UUID player, @NotNull Set<String> categoryKeys) {
        Map<String, Set<String>> mutes = new LinkedHashMap<>();
        for (String category : categoryKeys) {
            mutes.put(category, Set.of(NullSink.MEDIUM_KEY));
        }
        applyChanges(player, mutes, Set.of());
    }

    /**
     * Immediately clears every stored row for the player, across every category. Used by
     * {@code /notifications reset}.
     */
    public void resetAll(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.playerNotificationPreferenceMapper().deleteByPlayer(player);
            wrapper.session().commit();
        }
    }

    /**
     * Replaces the configured default media, e.g. after {@code settings.yml} is reloaded. Takes effect
     * for any {@link #preferredMedia} call made after this returns; in-flight calls may still observe
     * the previous value.
     */
    public void reloadDefaultMedia(@NotNull Collection<String> defaultMedia) {
        this.defaultMedia = Set.copyOf(defaultMedia);
    }
}
