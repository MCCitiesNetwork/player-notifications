package io.github.md5sha256.playernotifications.discord;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.objects.managers.AccountLinkManager;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves Discord ids from legacy DiscordSRV 2.x's account link manager.
 *
 * <p>The only class in this module that touches DiscordSRV, and it uses it purely as a <em>link
 * source</em> — no message ever goes through it, because its bundled JDA is relocated and so not
 * type-compatible with ours.
 *
 * <p>DiscordSRV is optional. Its classes are only visible because {@code paper-plugin.yml} declares a
 * soft dependency with {@code join-classpath: true}; if it is absent or a version whose API has
 * moved, {@link #isAvailable()} catches the resulting {@link LinkageError} and this provider simply
 * reports itself unavailable, so the chain falls through to the next one.
 */
public final class DiscordSrvAccountProvider implements DiscordAccountProvider {

    /** The key this provider is referenced by in {@code link-providers}. */
    public static final String PROVIDER_KEY = "discordsrv";

    private static final String PLUGIN_NAME = "DiscordSRV";

    private final Logger logger;

    public DiscordSrvAccountProvider(@NotNull Logger logger) {
        this.logger = logger;
    }

    @Override
    public @NotNull String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public boolean isAvailable() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)
                    && DiscordSRV.getPlugin() != null;
        } catch (LinkageError error) {
            this.logger.fine("DiscordSRV classes are not loadable; treating the provider as "
                    + "unavailable: " + error);
            return false;
        }
    }

    @Override
    public @NotNull Optional<UUID> playerFor(long discordId) {
        AccountLinkManager links = accountLinks();
        if (links == null) {
            return Optional.empty();
        }

        String id = Long.toString(discordId);
        // The cache first, then the blocking lookup — the same order discordIdFor uses, and for the same
        // reason: every caller of this is already off the main thread.
        UUID player = links.getUuidFromCache(id);
        if (player == null) {
            player = links.getUuid(id);
        }
        return Optional.ofNullable(player);
    }

    @Override
    public @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid) {
        AccountLinkManager links = accountLinks();
        if (links == null) {
            return Optional.empty();
        }

        // The cache first, then the blocking lookup — the delivery loop is already off the main
        // thread, so a database round trip here is acceptable.
        String discordId = links.getDiscordIdFromCache(playerUuid);
        if (discordId == null) {
            discordId = links.getDiscordId(playerUuid);
        }
        if (discordId == null || discordId.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(discordId.trim()));
        } catch (NumberFormatException exception) {
            this.logger.log(Level.WARNING, "DiscordSRV returned an unparseable Discord id '"
                    + discordId + "' for " + playerUuid, exception);
            return Optional.empty();
        }
    }

    /** DiscordSRV's link manager, or {@code null} when its classes are absent or it has none. */
    private AccountLinkManager accountLinks() {
        try {
            return DiscordSRV.getPlugin().getAccountLinkManager();
        } catch (LinkageError error) {
            this.logger.fine("DiscordSRV classes are not loadable: " + error);
            return null;
        }
    }
}
