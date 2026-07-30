package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves Discord ids from the plugin's own {@code DiscordAccountLink} table, populated by the
 * {@code /notifications link discord} flow.
 *
 * <p>This is what makes DiscordSRV genuinely optional: with {@code embedded} in {@code link-providers}, a
 * server needs no third-party linking plugin at all. Listing both keys lets a server migrating off
 * DiscordSRV resolve its old links while new ones land here.
 *
 * <p>Unlike {@link DiscordSrvAccountProvider} this is always {@linkplain #isAvailable() available}: the
 * database is a hard dependency of the host, so there is no "not installed" state to report.
 */
public final class EmbeddedDiscordAccountProvider implements DiscordAccountProvider {

    /** The key this provider is referenced by in {@code link-providers}. */
    public static final String PROVIDER_KEY = "embedded";

    private final DiscordAccountLinkStore store;

    public EmbeddedDiscordAccountProvider(@NotNull DiscordAccountLinkStore store) {
        this.store = store;
    }

    @Override
    public @NotNull String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public @NotNull Optional<Long> discordIdFor(@NotNull UUID playerUuid) {
        // A database failure propagates: ChainedDiscordAccountProvider catches a throwing delegate, logs
        // it, and tries the next one, which is the behaviour we want here.
        return this.store.discordIdFor(playerUuid);
    }
}
