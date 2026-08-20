package io.github.md5sha256.playernotifications.paper.preferences.session;

import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A player's in-progress edits to their dataType x medium preference matrix. Both preference dialogs
 * (by delivery method, by notification type) mutate the same session, so the two pivots can never
 * disagree, and nothing is written to the database until {@code Apply}.
 *
 * <p>Not thread-safe; callers (the dialog classes) only ever touch a session from the server main
 * thread.
 */
public final class PreferenceEditSession {

    private final UUID player;
    private final Map<String, Set<String>> media;
    private final Set<String> dirtyDataTypes = new HashSet<>();
    private boolean muted;
    private Boolean stagedMute;
    private Instant lastTouched;

    /**
     * @param initialEffectiveMedia the matrix as it would currently apply, per data type (exact rows,
     *                              else the {@code *} fallback, else the configured default)
     */
    public PreferenceEditSession(@NotNull UUID player,
                                 @NotNull Map<String, Set<String>> initialEffectiveMedia,
                                 @NotNull Instant now) {
        this(player, initialEffectiveMedia, false, now);
    }

    /**
     * @param initialEffectiveMedia the matrix as it would currently apply, per data type (exact rows,
     *                              else the {@code *} fallback, else the configured default)
     * @param initiallyMuted        the player-level mute flag as currently stored, seeded clean (no
     *                              staged change) — the global mute is orthogonal to the media matrix
     */
    public PreferenceEditSession(@NotNull UUID player,
                                 @NotNull Map<String, Set<String>> initialEffectiveMedia,
                                 boolean initiallyMuted,
                                 @NotNull Instant now) {
        this.player = player;
        this.media = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : initialEffectiveMedia.entrySet()) {
            this.media.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        this.muted = initiallyMuted;
        this.lastTouched = now;
    }

    @NotNull
    public UUID player() {
        return this.player;
    }

    @NotNull
    public Set<String> mediaFor(@NotNull String dataType) {
        return Set.copyOf(this.media.getOrDefault(dataType, Set.of()));
    }

    /**
     * Overwrites one data type's staged media, marking it dirty. An empty set stages a mute: there is
     * no way to stage a fall-through to the server default, which is deliberate — every edit a player
     * makes is an explicit choice.
     */
    public void setDataTypeMedia(@NotNull String dataType, @NotNull Set<String> newMedia, @NotNull Instant now) {
        this.media.put(dataType, new TreeSet<>(newMedia));
        this.dirtyDataTypes.add(dataType);
        this.lastTouched = now;
    }

    /**
     * Toggles a single medium within a single data type — the operation the "by delivery method" editor
     * performs on Save.
     */
    public void toggleDataTypeMedium(@NotNull String dataType, @NotNull String medium, boolean enabled,
                                     @NotNull Instant now) {
        Set<String> current = new TreeSet<>(this.media.getOrDefault(dataType, Set.of()));
        if (enabled) {
            current.add(medium);
        } else {
            current.remove(medium);
        }
        setDataTypeMedia(dataType, current, now);
    }

    /**
     * The player-level do-not-disturb flag as it currently stands in this session — the seeded value,
     * or the staged one once {@link #setMuted} has been called.
     */
    public boolean muted() {
        return this.muted;
    }

    /**
     * The staged change to the global mute flag, or {@code null} while nothing has been staged this
     * session. Orthogonal to {@link #explicitChanges()}: muting does not touch the media matrix, so
     * unmuting restores exactly what was configured per data type.
     */
    public @Nullable Boolean stagedMuteChange() {
        return this.stagedMute;
    }

    /**
     * Stages a change to the global mute flag. Marks the session dirty independently of any data type
     * edit — {@link #dirtyCount()} counts it as one more pending change.
     */
    public void setMuted(boolean muted, @NotNull Instant now) {
        this.muted = muted;
        this.stagedMute = muted;
        this.lastTouched = now;
    }

    public boolean isDirty() {
        return dirtyCount() > 0;
    }

    public int dirtyCount() {
        return this.dirtyDataTypes.size() + (this.stagedMute == null ? 0 : 1);
    }

    @NotNull
    public Set<String> dirtyDataTypes() {
        return Set.copyOf(this.dirtyDataTypes);
    }

    /**
     * Every dirty data type keyed to the media that should be written wholesale. An empty selection is
     * encoded as {@link NotificationPreferences#MUTED_MEDIUM}, so a staged edit always resolves to explicit rows.
     */
    @NotNull
    public Map<String, Set<String>> explicitChanges() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String dataType : this.dirtyDataTypes) {
            Set<String> selected = this.media.getOrDefault(dataType, Set.of());
            result.put(dataType, selected.isEmpty() ? Set.of(NotificationPreferences.MUTED_MEDIUM) : Set.copyOf(selected));
        }
        return Map.copyOf(result);
    }

    public boolean isExpired(@NotNull Instant now, @NotNull Duration idleTimeout) {
        return Duration.between(this.lastTouched, now).compareTo(idleTimeout) > 0;
    }
}
