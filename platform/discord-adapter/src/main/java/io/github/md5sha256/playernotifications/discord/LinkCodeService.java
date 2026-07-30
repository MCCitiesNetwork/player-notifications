package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and redeems the short-lived codes that tie a {@code /notifications link discord} in game to a {@code /link}
 * slash command in Discord.
 *
 * <p>Codes are held in memory and do not survive a restart. That is deliberate: a code's whole purpose is
 * to be transient, its lifetime is minutes, and a player who loses one to a restart simply runs
 * {@code /notifications link discord} again — persisting it would mean a schema migration for state designed to expire.
 *
 * <p>The security property that matters is that a code cannot be guessed: a guessed code would link an
 * attacker's Discord account to the victim's player. Hence {@link SecureRandom}, single use, and a short
 * TTL. The alphabet excludes {@code I}, {@code O}, {@code 0} and {@code 1} because the player reads the
 * code off a chat line and retypes it elsewhere.
 *
 * <p>Thread-safe: the command runs on the main thread (dispatching to an async executor) while redemption
 * arrives on a JDA event thread.
 */
public final class LinkCodeService {

    /** Unambiguous characters only — no {@code I}, {@code O}, {@code 0} or {@code 1}. */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Long enough that guessing is impractical within a code's lifetime, short enough to retype. */
    public static final int CODE_LENGTH = 6;

    /** A code and the moment it stops being redeemable. */
    private record PendingCode(@NotNull UUID playerUuid, @NotNull Instant expiresAt) {
    }

    private final Map<String, PendingCode> byCode = new ConcurrentHashMap<>();
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final Clock clock;

    public LinkCodeService(@NotNull Duration ttl, @NotNull Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    /** How long an issued code remains redeemable; used to tell the player when theirs expires. */
    public @NotNull Duration ttl() {
        return this.ttl;
    }

    /**
     * Issues a code for the player, invalidating any code they already hold so only one is ever live.
     */
    public @NotNull String issue(@NotNull UUID playerUuid) {
        sweepExpired();
        cancel(playerUuid);

        String code;
        do {
            code = randomCode();
            // putIfAbsent, so two players cannot end up sharing a code on a collision.
        } while (this.byCode.putIfAbsent(code,
                new PendingCode(playerUuid, this.clock.instant().plus(this.ttl))) != null);

        this.byPlayer.put(playerUuid, code);
        return code;
    }

    /**
     * Consumes the code and returns the player who was issued it, or empty if it is unknown or expired.
     *
     * <p>Expired and unknown are deliberately indistinguishable to the caller: an expired entry is
     * removed here, so there is nothing left to report it by. The player-facing message names both
     * causes.
     */
    public @NotNull Optional<UUID> redeem(@NotNull String code) {
        String normalized = normalize(code);
        PendingCode pending = this.byCode.remove(normalized);
        if (pending == null) {
            return Optional.empty();
        }
        this.byPlayer.remove(pending.playerUuid(), normalized);
        if (!pending.expiresAt().isAfter(this.clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(pending.playerUuid());
    }

    /** Drops any outstanding code for the player, e.g. when they unlink. */
    public void cancel(@NotNull UUID playerUuid) {
        String existing = this.byPlayer.remove(playerUuid);
        if (existing != null) {
            this.byCode.remove(existing);
        }
    }

    /** Whether the player holds a code that is still redeemable. */
    public boolean hasOutstandingCode(@NotNull UUID playerUuid) {
        String code = this.byPlayer.get(playerUuid);
        if (code == null) {
            return false;
        }
        PendingCode pending = this.byCode.get(code);
        return pending != null && pending.expiresAt().isAfter(this.clock.instant());
    }

    private @NotNull String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(this.random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private static @NotNull String normalize(@NotNull String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Drops expired entries. Called on issue rather than from a scheduled task: the map is bounded by the
     * number of players who have recently run the command, so it cannot grow without one.
     */
    private void sweepExpired() {
        Instant now = this.clock.instant();
        Iterator<Map.Entry<String, PendingCode>> iterator = this.byCode.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingCode> entry = iterator.next();
            if (!entry.getValue().expiresAt().isAfter(now)) {
                iterator.remove();
                this.byPlayer.remove(entry.getValue().playerUuid(), entry.getKey());
            }
        }
    }
}
