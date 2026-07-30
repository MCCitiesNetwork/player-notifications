package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * Read/write access to the plugin's own Discord account links, backing
 * {@link EmbeddedDiscordAccountProvider} and the {@code /discordlink} flow.
 *
 * <p>An interface rather than a concrete class purely so the flow and the provider are testable without
 * a database — {@link DatabaseDiscordAccountLinkStore} is the only production implementation.
 *
 * <p>Every method may block on JDBC; callers are expected to be off the main thread. Every method may
 * also throw a {@link RuntimeException} on a database failure, which callers must handle rather than
 * let escape into a Discord interaction or a command.
 */
public interface DiscordAccountLinkStore {

    /** The Discord id linked to the player, or empty if they have no link. */
    @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid);

    /** The player linked to the Discord id, or empty if that account is not linked. */
    @NotNull Optional<UUID> playerFor(long discordId);

    /**
     * Links the player to the Discord id, replacing any existing link on <em>either</em> side.
     *
     * <p>Both sides must be cleared because the table is uniquely indexed in both directions: without
     * it, a player claiming a Discord id previously linked to someone else would fail at the
     * constraint, whereas the natural reading of "I linked my account again" is "replace".
     */
    void link(@NotNull UUID playerUuid, long discordId);

    /**
     * Removes the player's link.
     *
     * @return whether a link existed, so the caller can tell the player which of the two happened
     */
    boolean unlink(@NotNull UUID playerUuid);
}
