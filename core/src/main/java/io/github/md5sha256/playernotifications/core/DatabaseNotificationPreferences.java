package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.SqlSessionWrapper;
import io.github.md5sha256.playernotifications.core.database.entity.PlayerNotificationPreferenceEntity;
import io.github.md5sha256.playernotifications.core.database.mapper.PlayerNotificationPreferenceMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
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
 * per {@code dataType} through four steps, the first that answers winning:
 *
 * <ol>
 *   <li>rows for this player and this exact {@code dataType} — what they chose;</li>
 *   <li>rows for this player under {@link #ALL_DATA_TYPES_KEY} — a blanket choice;</li>
 *   <li>the operator's per-type default for this {@code dataType}, set by
 *       {@link #reloadTypeDefaults} from {@code delivery-defaults.yml};</li>
 *   <li>the configured default medium set, so a player is never silently cut off from all
 *       notifications.</li>
 * </ol>
 *
 * <p>The operator's per-type default sits <b>below every player row</b> deliberately, so that "every
 * player row beats every operator setting" holds with no exception. Step 4 stays regardless of step 3,
 * because the set of {@code dataType}s is open-ended — any feature module can register one — so no
 * per-type file can be complete, and an unnamed type must not resolve to nothing.
 */
public class DatabaseNotificationPreferences implements NotificationPreferences {

    /**
     * Reserved {@code dataType} key meaning "applies to any data type not otherwise configured" — a
     * blanket choice. Nothing in the dialogs writes it directly.
     */
    public static final String ALL_DATA_TYPES_KEY = "*";

    private final Database database;
    private volatile Set<String> defaultMedia;

    /**
     * The operator's per-{@code dataType} overrides, empty until {@link #reloadTypeDefaults} is called —
     * so a caller that never sets it resolves exactly as this class did before they existed.
     */
    private volatile Map<String, Set<String>> typeDefaults = Map.of();

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
            Set<String> typeDefault = typeDefault(dataType);
            return typeDefault != null ? typeDefault : this.defaultMedia;
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
            Set<String> blanket = byDataType.get(ALL_DATA_TYPES_KEY);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String dataType : dataTypes) {
                Set<String> exact = byDataType.get(dataType);
                if (exact != null) {
                    result.put(dataType, Set.copyOf(exact));
                    continue;
                }
                if (blanket != null) {
                    result.put(dataType, Set.copyOf(blanket));
                    continue;
                }
                // The same four-step chain preferredMedia walks: if only that method learned the
                // per-type default, the preference dialogs would display a default that is not the one
                // actually in force.
                Set<String> typeDefault = typeDefault(dataType);
                result.put(dataType, typeDefault != null ? typeDefault : this.defaultMedia);
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
     * a valid value here — callers encode a silenced data type as {@code {"none"}}.
     */
    public void applyChanges(@NotNull UUID player,
                             @NotNull Map<String, Set<String>> explicitMedia,
                             @NotNull Set<String> dataTypesToReset) {
        applyChanges(player, explicitMedia, dataTypesToReset, null);
    }

    /**
     * Applies a batch of staged changes in one transaction, same as the three-argument form, plus an
     * optional change to the player-level mute flag: {@code null} leaves it untouched, {@code true}/
     * {@code false} sets/clears it on the same session before the commit. Used by the preference dialogs,
     * where a session can stage a mute change alongside (or instead of) per-{@code dataType} edits.
     */
    public void applyChanges(@NotNull UUID player,
                             @NotNull Map<String, Set<String>> explicitMedia,
                             @NotNull Set<String> dataTypesToReset,
                             @Nullable Boolean muted) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            PlayerNotificationPreferenceMapper mapper = wrapper.playerNotificationPreferenceMapper();
            for (String dataType : dataTypesToReset) {
                mapper.deleteByPlayerAndDataType(player, dataType);
            }
            for (Map.Entry<String, Set<String>> entry : explicitMedia.entrySet()) {
                mapper.deleteByPlayerAndDataType(player, entry.getKey());
                mapper.insertPreferences(player, entry.getKey(), entry.getValue());
            }
            if (muted != null) {
                if (muted) {
                    wrapper.playerMuteMapper().insertMute(player, Instant.now());
                } else {
                    wrapper.playerMuteMapper().deleteByPlayer(player);
                }
            }
            wrapper.session().commit();
        }
    }

    /**
     * Immediately clears every stored row for the player, across every data type. Used by
     * {@code /notifications preferences reset}.
     */
    public void resetAll(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.playerNotificationPreferenceMapper().deleteByPlayer(player);
            wrapper.session().commit();
        }
    }

    /**
     * Whether the player has the player-level mute flag set, backed by the {@code PlayerNotificationMute}
     * table. Presence of the row is the mute.
     */
    @Override
    public boolean isMuted(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.playerMuteMapper().countByPlayer(player) > 0;
        }
    }

    /**
     * Sets the player-level mute flag. Idempotent: muting an already-muted player just refreshes the
     * stored timestamp. Leaves every per-{@code dataType} preference row untouched, so unmuting restores
     * exactly what the player had.
     */
    public void mute(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.playerMuteMapper().insertMute(player, Instant.now());
            wrapper.session().commit();
        }
    }

    /**
     * Clears the player-level mute flag. A no-op (not an error) if the player was not muted.
     */
    public void unmute(@NotNull UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            wrapper.playerMuteMapper().deleteByPlayer(player);
            wrapper.session().commit();
        }
    }

    /**
     * The operator's default for {@code dataType}, or {@code null} when none is configured.
     *
     * <p>{@link #ALL_DATA_TYPES_KEY} never resolves to one. The single-argument
     * {@link #preferredMedia(UUID)} asks under that key, and it means "this player's media, whatever the
     * type" — a per-type override has nothing to say about it, and honouring a map entry spelled
     * {@code *} would make that string a third distinct thing in this codebase. {@code DeliveryDefaults}
     * refuses the key at load for the same reason; this guard is what makes that refusal unnecessary to
     * trust.
     */
    @Nullable
    private Set<String> typeDefault(@NotNull String dataType) {
        if (ALL_DATA_TYPES_KEY.equals(dataType)) {
            return null;
        }
        return this.typeDefaults.get(dataType);
    }

    /**
     * Replaces the per-{@code dataType} operator defaults, e.g. after {@code delivery-defaults.yml} is
     * reloaded. An empty map withdraws every override, returning each type to the configured default.
     *
     * <p>A volatile swap rather than a mutation, the idiom {@link #reloadDefaultMedia} uses: takes
     * effect for any call made after this returns, and a resolution racing a reload sees the old map or
     * the new one, never a half-built one.
     */
    public void reloadTypeDefaults(@NotNull Map<String, ? extends Collection<String>> typeDefaults) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : typeDefaults.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        this.typeDefaults = Map.copyOf(copy);
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
