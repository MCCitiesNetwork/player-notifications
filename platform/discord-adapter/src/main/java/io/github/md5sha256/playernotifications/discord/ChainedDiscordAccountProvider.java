package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link DiscordAccountProvider} that consults an ordered list of delegates and returns the first
 * link found.
 *
 * <p>Order comes from {@code link-providers} in {@code discord.yml}, resolved against a
 * {@link DiscordAccountProviderRegistry}. An unknown key is warned about and dropped rather than
 * failing module startup, and a delegate that is unavailable or throws is skipped — one broken
 * provider must never mask a working one further down the chain.
 */
public final class ChainedDiscordAccountProvider implements DiscordAccountProvider {

    /** The key this composite reports; it is never registered under a config-facing name. */
    public static final String PROVIDER_KEY = "chain";

    private final List<DiscordAccountProvider> delegates;
    private final Logger logger;

    private ChainedDiscordAccountProvider(
            @NotNull List<DiscordAccountProvider> delegates, @NotNull Logger logger) {
        this.delegates = List.copyOf(delegates);
        this.logger = logger;
    }

    /**
     * Builds a chain from an ordered list of provider keys, resolved against the given registry.
     * Unknown keys are logged as warnings and skipped.
     */
    public static @NotNull ChainedDiscordAccountProvider of(
            @NotNull List<String> orderedKeys,
            @NotNull DiscordAccountProviderRegistry registry,
            @NotNull Logger logger) {
        List<DiscordAccountProvider> resolved = new ArrayList<>(orderedKeys.size());
        for (String key : orderedKeys) {
            Optional<DiscordAccountProvider> provider = registry.get(key);
            if (provider.isEmpty()) {
                logger.warning("Unknown Discord link provider '" + key
                        + "' in link-providers; skipping it");
                continue;
            }
            resolved.add(provider.get());
        }
        return new ChainedDiscordAccountProvider(resolved, logger);
    }

    @Override
    public @NotNull String providerKey() {
        return PROVIDER_KEY;
    }

    /** The resolved delegate keys, in consultation order. Unknown config keys have already been dropped. */
    public @NotNull List<String> delegateKeys() {
        return this.delegates.stream().map(DiscordAccountProvider::providerKey).toList();
    }

    /**
     * Logs which link sources are usable right now, once, at startup.
     *
     * <p>A chain whose providers are all unavailable resolves nothing, silently, forever — every DM comes
     * back {@code UNSUPPORTED} and nothing is logged until a notification is actually dropped. This is the
     * only diagnostic for that misconfiguration, so an all-unavailable (or empty) chain warns.
     *
     * <p>Deliberately does not query any provider: availability is a cheap "is the backing plugin there"
     * check, whereas a lookup would need a player and could block.
     */
    public void reportAvailability() {
        boolean anyAvailable = false;
        for (DiscordAccountProvider delegate : this.delegates) {
            boolean available = isAvailableQuietly(delegate);
            anyAvailable |= available;
            this.logger.info("Discord link provider '" + delegate.providerKey() + "': "
                    + (available ? "available" : "not available"));
        }
        if (!anyAvailable) {
            this.logger.warning("No Discord link provider is available, so no player can be resolved to a"
                    + " Discord account and the '" + DiscordMedia.DM + "' medium cannot deliver."
                    + " Check link-providers in discord.yml.");
        }
    }

    private boolean isAvailableQuietly(@NotNull DiscordAccountProvider delegate) {
        try {
            return delegate.isAvailable();
        } catch (RuntimeException exception) {
            this.logger.log(Level.WARNING, "Discord link provider '" + delegate.providerKey()
                    + "' failed its availability check", exception);
            return false;
        }
    }

    @Override
    public @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid) {
        for (DiscordAccountProvider delegate : this.delegates) {
            if (!delegate.isAvailable()) {
                continue;
            }
            Optional<Long> discordId;
            try {
                discordId = delegate.discordIdFor(playerUuid);
            } catch (RuntimeException exception) {
                this.logger.log(Level.WARNING, "Discord link provider '" + delegate.providerKey()
                        + "' failed to resolve " + playerUuid + "; trying the next provider",
                        exception);
                continue;
            }
            if (discordId.isPresent()) {
                return discordId;
            }
        }
        return Optional.empty();
    }
}
