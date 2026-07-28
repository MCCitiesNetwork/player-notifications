package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registers {@link DiscordAccountProvider}s keyed by {@link DiscordAccountProvider#providerKey()},
 * mirroring {@link io.github.md5sha256.playernotifications.api.NotificationSinkRegistry}'s shape and
 * its synchronized-map thread-safety convention.
 */
public final class DiscordAccountProviderRegistry {

    private final Map<String, DiscordAccountProvider> providers =
            Collections.synchronizedMap(new HashMap<>());

    public void register(@NotNull DiscordAccountProvider provider) {
        this.providers.put(provider.providerKey(), provider);
    }

    public @NotNull Optional<DiscordAccountProvider> get(@NotNull String providerKey) {
        return Optional.ofNullable(this.providers.get(providerKey));
    }
}
