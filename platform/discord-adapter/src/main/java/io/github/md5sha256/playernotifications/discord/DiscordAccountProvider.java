package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a Minecraft player's UUID to a Discord user id.
 *
 * <p>This is the swap point for account linking. The only implementation shipped is
 * {@link DiscordSrvAccountProvider}, backed by legacy DiscordSRV, but the sink never names it: it
 * resolves through whatever ordered chain {@code link-providers} in {@code discord.yml} describes. A
 * future provider — an own link table, a different linking plugin — is one class, one registration
 * and one config line, with no change to {@link DiscordDmSink}.
 *
 * <p>A future channel-ping sink would resolve its mention target through the same interface, which
 * is the other reason this sits outside the sink.
 */
public interface DiscordAccountProvider {

    /**
     * The key this provider is registered and referenced under in {@code link-providers}
     * (e.g. {@code "discordsrv"}).
     */
    @NotNull String providerKey();

    /**
     * The Discord user id linked to the given player, or empty if this provider knows of no link.
     *
     * <p>May block; callers are expected to be off the main thread.
     */
    @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid);

    /**
     * The player linked to the given Discord user id, or empty if this provider knows of no link.
     *
     * <p>The reverse of {@link #discordIdFor(UUID)}, and the lookup every Discord-side slash command
     * starts with. A {@code default} returning empty rather than an abstract method, so a provider
     * written before this existed still compiles and simply answers nothing — it stays usable for
     * delivery, which is what it was written for.
     *
     * <p>May block; callers are expected to be off the main thread.
     */
    default @NotNull Optional<UUID> playerFor(long discordId) {
        return Optional.empty();
    }

    /**
     * Whether this provider can currently answer at all — typically "the backing plugin is installed
     * and enabled". An unavailable provider is skipped without being queried, so an optional
     * dependency being absent costs nothing per lookup.
     */
    default boolean isAvailable() {
        return true;
    }
}
