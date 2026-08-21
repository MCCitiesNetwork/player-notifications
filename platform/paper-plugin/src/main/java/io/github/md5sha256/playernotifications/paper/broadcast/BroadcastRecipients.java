package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The OR match between a broadcast's {@code --perm} list and a candidate recipient's permissions,
 * kept separate from any Bukkit-touching class so it is unit-testable without a live server — the
 * {@link Predicate} seam is the same device {@code MailRecipients}' resolver uses.
 *
 * <p>This is deliberately not folded into {@link OnlineBroadcastAudience}: the OR rule is the part a
 * future offline-capable {@link BroadcastAudience} would reuse unchanged, since where the
 * {@code Predicate<String>} comes from — a live {@code Player}, a permission plugin's API, anything
 * else — is the only thing that differs between an online and an offline audience.
 */
public final class BroadcastRecipients {

    private BroadcastRecipients() {
    }

    /**
     * A candidate recipient: their identity, and a way to ask whether they hold a given permission
     * node. The predicate is the seam — in production it is {@code Player::hasPermission}, but nothing
     * here knows that.
     */
    public record Candidate(@NotNull UUID uuid, @NotNull Predicate<String> hasPermission) {
    }

    /**
     * Selects the UUIDs of every candidate matching {@code permissions} under OR semantics: an empty
     * {@code permissions} list matches every candidate (no {@code --perm} flag means "everyone"),
     * otherwise a candidate matches when {@link Candidate#hasPermission()} accepts at least one listed
     * permission. Iteration order is preserved and each candidate contributes at most one UUID to the
     * result, even if it would match more than one listed permission.
     *
     * @return an unmodifiable list of matching UUIDs
     */
    @NotNull
    public static List<UUID> select(@NotNull Collection<Candidate> candidates,
                                    @NotNull List<String> permissions) {
        List<UUID> result = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (matches(candidate, permissions)) {
                result.add(candidate.uuid());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean matches(@NotNull Candidate candidate, @NotNull List<String> permissions) {
        if (permissions.isEmpty()) {
            return true;
        }
        for (String permission : permissions) {
            if (candidate.hasPermission().test(permission)) {
                return true;
            }
        }
        return false;
    }
}
