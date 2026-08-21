package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the recipients of a {@code /broadcast}, given the OR-list of permissions parsed from the
 * command (empty meaning "everyone"). This is the swap point for a future offline-capable
 * implementation: today {@link OnlineBroadcastAudience} is the only implementation, and every other
 * piece of the broadcast feature — {@code BroadcastArguments}, {@link BroadcastRecipients},
 * {@code Broadcaster} — is already written in terms that do not care whether a resolved UUID belongs
 * to an online or an offline player, so that follow-up can be a new implementation of this interface
 * rather than a rewrite of the command.
 *
 * <p>What such an implementation would still need to answer is deliberately not decided here — see the
 * design doc's "Room for offline delivery" section
 * ({@code docs/superpowers/specs/2026-08-21-broadcast-command-design.md}) for the open questions
 * (offline permission checks, bounding the candidate set, whether resolution can stay synchronous, and
 * whether "never stored" survives).
 */
@FunctionalInterface
public interface BroadcastAudience {

    /**
     * @param permissions the OR-list of permission nodes from {@code --perm}; empty means every
     *                     candidate this audience knows about
     * @return the UUIDs to deliver the broadcast to
     */
    @NotNull
    List<UUID> resolve(@NotNull List<String> permissions);
}
