package io.github.md5sha256.playernotifications.core;

import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * The outcome of a batched delivery pass.
 *
 * @param attempted distinct targets whose pass ran without throwing — attempted, not necessarily
 *                  delivered, since a muted or unreachable player still counts
 * @param failed    targets whose pass threw, or whose whole chunk failed; each was logged, and any stored
 *                  notification stays unread for their next join
 */
public record BatchDeliveryReport(int attempted, @NotNull Set<UUID> failed) {

    public BatchDeliveryReport {
        failed = Set.copyOf(failed);
    }
}
