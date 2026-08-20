package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.discord.DiscordAccountProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the Discord user behind an interaction into the player whose mail, inbox and preferences the
 * command should act on — the reverse of the lookup delivery does.
 *
 * <p>Goes through the configured {@link DiscordAccountProvider} chain rather than the embedded link
 * store directly, so the commands work on a server running {@code link-providers: [discordsrv]} instead
 * of being silently dead there.
 */
public final class DiscordUserResolver {

    /** The reply an unlinked Discord user gets, naming exactly what to run in game. */
    public static final String NOT_LINKED_MESSAGE =
            "Your Discord account is not linked to a Minecraft account."
                    + " Run /notifications link discord in game to get a code.";

    private final DiscordAccountProvider accounts;
    private final Logger logger;

    public DiscordUserResolver(@NotNull DiscordAccountProvider accounts, @NotNull Logger logger) {
        this.accounts = accounts;
        this.logger = logger;
    }

    /**
     * The player linked to this Discord user, or empty if none is.
     *
     * <p>Blocking; call off the main thread. A failing lookup is logged and reported as "not linked"
     * rather than propagating: an exception escaping into an interaction leaves it with no reply at
     * all, which reads as the bot being dead rather than as an account needing linking.
     */
    public @NotNull Optional<UUID> resolve(long discordId) {
        try {
            return this.accounts.playerFor(discordId);
        } catch (RuntimeException exception) {
            this.logger.log(Level.WARNING,
                    "Failed to resolve the player linked to Discord user " + discordId, exception);
            return Optional.empty();
        }
    }
}
