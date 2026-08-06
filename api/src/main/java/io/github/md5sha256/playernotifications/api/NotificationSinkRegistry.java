package io.github.md5sha256.playernotifications.api;

import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registers {@link NotificationSink}s, keyed by {@link NotificationSink#mediumKey()}. Sinks are keyed
 * by medium, a different axis from data types, so they get their own registry rather than being folded
 * into {@link NotificationDataTypeRegistry}.
 *
 * <p>Thread-safety follows the {@link NotificationDataTypeRegistry} convention: backed by a
 * synchronized map.
 */
public class NotificationSinkRegistry {

    private final Map<String, NotificationSink> sinks = Collections.synchronizedMap(new HashMap<>());

    public void registerSink(@NotNull NotificationSink sink) {
        this.sinks.put(sink.mediumKey(), sink);
    }

    public void unregisterSink(@NotNull String mediumKey) {
        this.sinks.remove(mediumKey);
    }

    @NotNull
    public Optional<NotificationSink> getSink(@NotNull String mediumKey) {
        return Optional.ofNullable(this.sinks.get(mediumKey));
    }

    /**
     * The player-facing label for a medium: the registered sink's {@link NotificationSink#displayName()},
     * or the raw key when nothing is registered under it.
     *
     * <p>Naming a medium lives here rather than in each caller because an unregistered key is a normal
     * case — a player can hold a preference row for a medium whose module is not installed — and every
     * caller that shows media to a player must render that case the same way.
     */
    @NotNull
    public Component displayName(@NotNull String mediumKey) {
        return getSink(mediumKey).map(NotificationSink::displayName)
                .orElseGet(() -> Component.text(mediumKey));
    }

    @NotNull
    public Set<String> registeredMedia() {
        synchronized (this.sinks) {
            return Set.copyOf(this.sinks.keySet());
        }
    }

}
