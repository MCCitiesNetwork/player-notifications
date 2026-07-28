package io.github.md5sha256.playernotifications.paper.preferences.session;

import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import org.jetbrains.annotations.NotNull;

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
    private final Set<String> explicitAtLoad;
    private final Set<String> fallbackMedia;
    private final Set<String> dirtyDataTypes = new HashSet<>();
    private final Set<String> resetDataTypes = new HashSet<>();
    private Instant lastTouched;

    /**
     * @param initialEffectiveMedia the matrix as it would currently apply, per data type (exact rows,
     *                              else the {@code *} fallback, else the configured default)
     * @param explicitAtLoad        the data types that had exact stored rows when this session was
     *                              loaded, used by {@link #isUsingServerDefault(String)}
     * @param fallbackMedia         the plain {@code *}/configured-default media, used to populate a
     *                              data type when it is reset
     */
    public PreferenceEditSession(@NotNull UUID player,
                                 @NotNull Map<String, Set<String>> initialEffectiveMedia,
                                 @NotNull Set<String> explicitAtLoad,
                                 @NotNull Set<String> fallbackMedia,
                                 @NotNull Instant now) {
        this.player = player;
        this.media = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : initialEffectiveMedia.entrySet()) {
            this.media.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        this.explicitAtLoad = Set.copyOf(explicitAtLoad);
        this.fallbackMedia = Set.copyOf(fallbackMedia);
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
     * Overwrites one data type's staged media, marking it dirty. An empty set stages a mute, not a
     * fall-through to the server default — only {@link #resetDataType(String, Instant)} does that.
     */
    public void setDataTypeMedia(@NotNull String dataType, @NotNull Set<String> newMedia, @NotNull Instant now) {
        this.media.put(dataType, new TreeSet<>(newMedia));
        this.dirtyDataTypes.add(dataType);
        this.resetDataTypes.remove(dataType);
        this.lastTouched = now;
    }

    /**
     * Stages "use the server default" for one data type: its staged media becomes the fallback media
     * captured at load time, and it is written by clearing its rows on {@code Apply} rather than by
     * writing the fallback media explicitly.
     */
    public void resetDataType(@NotNull String dataType, @NotNull Instant now) {
        this.media.put(dataType, new TreeSet<>(this.fallbackMedia));
        this.dirtyDataTypes.add(dataType);
        this.resetDataTypes.add(dataType);
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

    public boolean isDirty() {
        return !this.dirtyDataTypes.isEmpty();
    }

    public int dirtyCount() {
        return this.dirtyDataTypes.size();
    }

    @NotNull
    public Set<String> dirtyDataTypes() {
        return Set.copyOf(this.dirtyDataTypes);
    }

    @NotNull
    public Set<String> dataTypesToReset() {
        return Set.copyOf(this.resetDataTypes);
    }

    /**
     * Dirty data types that are explicit selections rather than resets, keyed to the media that should
     * be written wholesale. An empty selection is encoded as {@link NullSink#MEDIUM_KEY}.
     */
    @NotNull
    public Map<String, Set<String>> explicitChanges() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String dataType : this.dirtyDataTypes) {
            if (!this.resetDataTypes.contains(dataType)) {
                Set<String> selected = this.media.getOrDefault(dataType, Set.of());
                result.put(dataType, selected.isEmpty() ? Set.of(NullSink.MEDIUM_KEY) : Set.copyOf(selected));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Whether the given data type is currently showing the server default rather than an explicit
     * choice — true if it was never explicitly configured and has not been touched, or if it has been
     * staged for reset.
     */
    public boolean isUsingServerDefault(@NotNull String dataType) {
        if (this.dirtyDataTypes.contains(dataType)) {
            return this.resetDataTypes.contains(dataType);
        }
        return !this.explicitAtLoad.contains(dataType);
    }

    public boolean isExpired(@NotNull Instant now, @NotNull Duration idleTimeout) {
        return Duration.between(this.lastTouched, now).compareTo(idleTimeout) > 0;
    }
}
