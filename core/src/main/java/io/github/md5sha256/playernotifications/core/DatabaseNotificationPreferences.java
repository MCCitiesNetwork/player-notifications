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
 * per {@code dataType}. A player with no rows for a {@code dataType} falls back to the {@link
 * #ALL_DATA_TYPES_KEY} rows (a blanket choice), then to a configurable default medium set, so a player
 * is never silently cut off from all notifications.
 */
public class DatabaseNotificationPreferences implements NotificationPreferences {

    /**
     * Reserved {@code dataType} key meaning "applies to any data type not otherwise configured" — a
     * blanket choice. Nothing in the dialogs writes it directly.
     */
    public static final String ALL_DATA_TYPES_KEY = "*";

    private final Database database;
    private volatile Set<String> defaultMedia;

    public DatabaseNotificationPreferences(@NotNull Database database, @NotNull Collection<String> defaultMedia) {
        this.database = database;
        this.defaultMedia = Set.copyOf(defaultMedia);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
        return preferredMedia(player, ALL_DATA_TYPES_KEY);
    }

    @Override
    public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            if (!ALL_DATA_TYPES_KEY.equals(dataType)) {
                List<String> exact = mapper.selectByPlayerAndDataType(player, dataType);
                if (!exact.isEmpty()) {
                    return Set.copyOf(exact);
                }
            }
            List<String> fallback = mapper.selectByPlayerAndDataType(player, ALL_DATA_TYPES_KEY);
            if (!fallback.isEmpty()) {
                return Set.copyOf(fallback);
            }
            return this.defaultMedia;
        }
    }

    /**
     * Resolves the effective media for every given data type in one database round trip: exact rows,
     * else the {@link #ALL_DATA_TYPES_KEY} fallback, else the configured default. Used to load a
     * preference edit session's starting matrix.
     */
    public @NotNull Map<String, Set<String>> effectiveMediaByDataType(@NotNull UUID player,
                                                                       @NotNull Set<String> dataTypes) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byDataType = groupByDataType(wrapper, player);
            Set<String> fallback = byDataType.getOrDefault(ALL_DATA_TYPES_KEY, this.defaultMedia);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String dataType : dataTypes) {
                Set<String> exact = byDataType.get(dataType);
                result.put(dataType, exact != null ? Set.copyOf(exact) : Set.copyOf(fallback));
            }
            return Map.copyOf(result);
        }
    }

    /**
     * Which of the given data types the player has exact stored rows for, as opposed to inheriting the
     * {@link #ALL_DATA_TYPES_KEY} fallback or the configured default. Used to label a data type "server
     * default" in the preferences dialogs.
     */
    public @NotNull Set<String> explicitlyConfiguredDataTypes(@NotNull UUID player,
                                                               @NotNull Set<String> dataTypes) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            Map<String, Set<String>> byDataType = groupByDataType(wrapper, player);
            Set<String> configured = new HashSet<>();
            for (String dataType : dataTypes) {
                if (byDataType.containsKey(dataType)) {
                    configured.add(dataType);
                }
            }
            return Set.copyOf(configured);
        }
    }

    private static @NotNull Map<String, Set<String>> groupByDataType(@NotNull SqlSessionWrapper wrapper,
                                                                       @NotNull UUID player) {
        List<PlayerNotificationPreferenceEntity> rows =
                wrapper.playerNotificationPreferenceMapper().selectByPlayer(player);
        Map<String, Set<String>> byDataType = new HashMap<>();
        for (PlayerNotificationPreferenceEntity row : rows) {
            byDataType.computeIfAbsent(row.dataType(), key -> new TreeSet<>()).add(row.medium());
        }
        return byDataType;
    }

    /**
     * Applies a batch of staged changes in one transaction. Data types in {@code dataTypesToReset} have
     * their rows deleted, falling back to {@link #ALL_DATA_TYPES_KEY}/the configured default again.
     * Every entry in {@code explicitMedia} wholesale-replaces that data type's rows; an empty set is not
     * a valid value here — callers encode a mute as {@code {"none"}}.
     */
    public void applyChanges(@NotNull UUID player,
                             @NotNull Map<String, Set<String>> explicitMedia,
                             @NotNull Set<String> dataTypesToReset) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            for (String dataType : dataTypesToReset) {
                mapper.deleteByPlayerAndDataType(player, dataType);
            }
            for (Map.Entry<String, Set<String>> entry : explicitMedia.entrySet()) {
                mapper.deleteByPlayerAndDataType(player, entry.getKey());
                mapper.insertPreferences(player, entry.getKey(), entry.getValue());
            }
            wrapper.session().commit();
        }
    }

    /**
     * Immediately mutes every given data type for the player in one transaction, storing an explicit
     * {@code none} row for each, plus an unconditional {@link #ALL_DATA_TYPES_KEY} blanket row so the
     * mute also covers any data type not in {@code dataTypes} — including one registered by a module
     * installed after this call, and the degenerate case of an empty {@code dataTypes} (no registered
     * payload mappings), which would otherwise write nothing and silently no-op. Used by
     * {@code /notifications mute}.
     */
    public void muteAll(@NotNull UUID player, @NotNull Set<String> dataTypes) {
        Map<String, Set<String>> mutes = new LinkedHashMap<>();
        for (String dataType : dataTypes) {
            mutes.put(dataType, Set.of(NullSink.MEDIUM_KEY));
        }
        mutes.put(ALL_DATA_TYPES_KEY, Set.of(NullSink.MEDIUM_KEY));
        applyChanges(player, mutes, Set.of());
    }

    /**
     * Immediately clears every stored row for the player, across every data type. Used by
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
