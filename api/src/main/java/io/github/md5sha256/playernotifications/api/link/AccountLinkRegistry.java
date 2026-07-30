package io.github.md5sha256.playernotifications.api.link;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registers {@link AccountLinkProvider}s, keyed by {@link AccountLinkProvider#providerKey()}. Account
 * providers are a different axis from payload types, media and categories, so — following the same
 * convention as {@link io.github.md5sha256.playernotifications.api.NotificationSinkRegistry} — they get
 * their own registry rather than being folded into an existing one.
 *
 * <p>Keys are normalised to lower case on both registration and lookup, so a player who types
 * {@code /notifications link Discord} is not told the provider is unknown.
 *
 * <p>Thread-safety follows the registry convention here: backed by a synchronized map.
 */
public class AccountLinkRegistry {

    private final Map<String, AccountLinkProvider> providers =
            Collections.synchronizedMap(new HashMap<>());

    public void registerProvider(@NotNull AccountLinkProvider provider) {
        this.providers.put(normalise(provider.providerKey()), provider);
    }

    public void unregisterProvider(@NotNull String providerKey) {
        this.providers.remove(normalise(providerKey));
    }

    @NotNull
    public Optional<AccountLinkProvider> getProvider(@NotNull String providerKey) {
        return Optional.ofNullable(this.providers.get(normalise(providerKey)));
    }

    /** A snapshot of the registered keys, already normalised. */
    @NotNull
    public Set<String> registeredProviders() {
        synchronized (this.providers) {
            return Set.copyOf(this.providers.keySet());
        }
    }

    private static @NotNull String normalise(@NotNull String providerKey) {
        return providerKey.toLowerCase(Locale.ROOT);
    }

}
