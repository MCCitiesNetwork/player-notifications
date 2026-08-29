package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Answers "who holds these permissions" for players who are not online — the one question Bukkit
 * cannot answer, and therefore the thing that gates {@code /broadcast --offline} entirely.
 *
 * <h2>Why this is set-valued</h2>
 *
 * <p>The obvious shape is per-player: {@code Optional<Predicate<String>> forOfflinePlayer(UUID)}, to
 * be fed into {@link BroadcastRecipients} exactly like an online candidate's
 * {@code Player::hasPermission}. That was designed first and rejected, because it forces one
 * permission-backend load for every candidate — N storage round trips for a set the backend can
 * compute in a handful of queries. Drawing the seam around <em>who matches</em> rather than <em>does
 * this player match</em> is what makes offline broadcasts affordable at all.
 *
 * <p>The cost, accepted: the online and offline paths match by different code
 * ({@link BroadcastRecipients} chains predicates, an implementation of this chains queries), and they
 * can in principle disagree — most plausibly on context-conditional nodes, which Bukkit evaluates for a
 * live player and a lookup generally cannot. Sharing one matcher is not possible while one side answers
 * "who" and the other "whether".
 *
 * <p><b>Implementations block.</b> Every one of them is a query against another plugin's storage, so
 * this is called only from an async task, never the main thread.
 */
public interface PermissionLookup {

    /** For log lines and the startup notice — e.g. {@code "LuckPerms"}. */
    @NotNull
    String providerName();

    /**
     * Every player the backend knows of who holds {@code permissions} under {@code chain}.
     *
     * <p>{@code permissions} is never empty in practice: {@code --offline} requires at least one
     * {@code --perm}, precisely so that a lookup is never asked to enumerate the entire population.
     *
     * @return the matching UUIDs; empty when nothing matches
     * @throws RuntimeException if the backend cannot be queried — a partial audience is worse than a
     *                          failed command for an operation whose recipients are not present to
     *                          notice they were missed
     */
    @NotNull
    Set<UUID> matching(@NotNull List<String> permissions, @NotNull BroadcastArguments.Chain chain);
}
