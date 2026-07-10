package io.github.md5sha256.playernotifications.paper;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Plugin behaviour settings, deserialized from {@code settings.yml} via Configurate.
 *
 * @param pruneIntervalSeconds how often expired notifications are pruned from the
 *                             database, in seconds; falls back to one hour when
 *                             absent or non-positive
 */
@ConfigSerializable
public record PluginSettings(
        @Setting("prune-interval-seconds")
        long pruneIntervalSeconds
) {

    private static final long DEFAULT_PRUNE_INTERVAL_SECONDS = 3600L;

    public PluginSettings {
        if (pruneIntervalSeconds <= 0) {
            pruneIntervalSeconds = DEFAULT_PRUNE_INTERVAL_SECONDS;
        }
    }
}
