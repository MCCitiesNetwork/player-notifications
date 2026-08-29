package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the recipients of a {@code /broadcast}, given the permissions parsed from the command
 * (empty meaning "everyone") and how they combine. This is the swap point between the online and
 * offline audiences: {@link OnlineBroadcastAudience} asks each connected player, and
 * {@link OfflineBroadcastAudience} asks a {@link PermissionLookup}. Every other
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
     * <p><b>Called off the command thread.</b> An offline-capable implementation queries a permission
     * backend and blocks; {@link OnlineBroadcastAudience} needs the main thread instead and marshals
     * back onto it internally, so that requirement lives in each implementation rather than in the
     * command.
     *
     * @param permissions the permission nodes from {@code --perm}; empty means every candidate this
     *                     audience knows about
     * @param chain        how {@code permissions} combine
     * @return the UUIDs to deliver the broadcast to
     */
    @NotNull
    List<UUID> resolve(@NotNull List<String> permissions, @NotNull BroadcastArguments.Chain chain);
}
