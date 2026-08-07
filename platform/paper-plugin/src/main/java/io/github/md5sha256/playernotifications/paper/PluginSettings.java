package io.github.md5sha256.playernotifications.paper;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;

/**
 * Plugin behaviour settings, deserialized from {@code settings.yml} via Configurate.
 *
 * @param pruneIntervalSeconds how often expired notifications are pruned from the
 *                             database, in seconds; falls back to one hour when
 *                             absent or non-positive
 * @param defaultMedia         the medium keys a player is assumed to prefer when they have expressed
 *                             no preference of their own (see {@code DatabaseNotificationPreferences})
 * @param deliverOnJoin        whether joining the server triggers delivery of that player's due
 *                             notifications (see {@code JoinDeliveryListener})
 * @param joinDeliveryDelaySeconds how long after the join event delivery runs, in seconds; a negative
 *                             value is clamped to {@code 0}, which means "immediately, on the next
 *                             async tick" rather than falling back to a default
 * @param inboxPageSize        how many inbox entries {@code /notifications} shows per page, clamped to
 *                             {@code 1..20}
 */
@ConfigSerializable
public record PluginSettings(
        @Setting("prune-interval-seconds")
        long pruneIntervalSeconds,

        @Setting("default-media")
        @Required
        List<String> defaultMedia,

        // Primitives, so deliberately not @Required: that rule exists to stop a missing key
        // deserializing to null, which a primitive cannot do. Both keys are written into every data
        // folder by the copy-defaults-then-merge path, including on upgrade.
        @Setting("deliver-on-join")
        boolean deliverOnJoin,

        @Setting("join-delivery-delay-seconds")
        long joinDeliveryDelaySeconds,

        @Setting("inbox-page-size")
        int inboxPageSize
) {

    private static final long DEFAULT_PRUNE_INTERVAL_SECONDS = 3600L;

    /** Matches {@code paper.ui.PageBounds.MAX_PAGE_SIZE}; a dialog cannot usefully show more rows. */
    public static final int MAX_INBOX_PAGE_SIZE = 20;
    private static final int DEFAULT_INBOX_PAGE_SIZE = 7;

    public PluginSettings {
        if (pruneIntervalSeconds <= 0) {
            pruneIntervalSeconds = DEFAULT_PRUNE_INTERVAL_SECONDS;
        }
        if (joinDeliveryDelaySeconds < 0) {
            joinDeliveryDelaySeconds = 0;
        }
        // An absent key deserializes a primitive to 0, which is not a usable page size; anything else
        // is clamped rather than rejected.
        inboxPageSize = inboxPageSize <= 0
                ? DEFAULT_INBOX_PAGE_SIZE
                : Math.min(inboxPageSize, MAX_INBOX_PAGE_SIZE);
    }
}
