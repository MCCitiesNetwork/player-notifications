package io.github.md5sha256.playernotifications.paper.preferences.session;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Holds one {@link PreferenceEditSession} per player who currently has a preferences dialog open. A
 * session left untouched for {@link #IDLE_TIMEOUT} is treated as gone: reopening the dialog starts
 * fresh from the database rather than resuming stale staged edits.
 */
public final class PreferenceSessionManager {

    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);

    private final Map<UUID, PreferenceEditSession> sessions = new ConcurrentHashMap<>();

    /**
     * Returns the player's current session if one exists and has not expired, otherwise builds a new one
     * via {@code factory} and stores it.
     */
    @NotNull
    public PreferenceEditSession getOrCreate(@NotNull UUID player, @NotNull Supplier<PreferenceEditSession> factory) {
        return this.sessions.compute(player, (uuid, existing) -> {
            Instant now = Instant.now();
            if (existing != null && !existing.isExpired(now, IDLE_TIMEOUT)) {
                return existing;
            }
            return factory.get();
        });
    }

    /**
     * The player's current session, or empty if none exists or it has expired. An expired session found
     * here is evicted.
     */
    @NotNull
    public Optional<PreferenceEditSession> get(@NotNull UUID player) {
        PreferenceEditSession session = this.sessions.get(player);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(Instant.now(), IDLE_TIMEOUT)) {
            this.sessions.remove(player);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void drop(@NotNull UUID player) {
        this.sessions.remove(player);
    }
}
