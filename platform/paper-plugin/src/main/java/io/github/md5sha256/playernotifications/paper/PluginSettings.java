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
        // deserializing to null, which a primitive cannot do.
        //
        // A key absent from an older settings.yml is NOT written in on upgrade any more -- the plugin
        // no longer rewrites a config file after its first copy, and reports the gap through
        // PlayerNotificationsPlugin.warnAboutMissingConfigKeys instead. So an absent key here really
        // does reach this record as 0/false, and the compact constructor below is what makes that
        // safe. A future boolean whose correct default is `true` cannot rely on this and needs its
        // own handling.
        @Setting("deliver-on-join")
        boolean deliverOnJoin,

        @Setting("join-delivery-delay-seconds")
        long joinDeliveryDelaySeconds,

        @Setting("inbox-page-size")
        int inboxPageSize,

        @Setting("delivery-batch-size")
        int deliveryBatchSize
) {

    private static final long DEFAULT_PRUNE_INTERVAL_SECONDS = 3600L;

    /** Matches {@code paper.ui.PageBounds.MAX_PAGE_SIZE}; a dialog cannot usefully show more rows. */
    public static final int MAX_INBOX_PAGE_SIZE = 20;
    private static final int DEFAULT_INBOX_PAGE_SIZE = 7;

    /**
     * Targets per chunk of a batched delivery pass: one due query and one seen-marking write each.
     * 200 already cuts round trips 200-fold, beyond which the per-player mute check and sink calls
     * dominate; it bounds a chunk's due rows to a few MB even with a backlog; and it is far inside
     * MariaDB's placeholder and packet limits, which is why the cap can be 1000.
     */
    public static final int DEFAULT_DELIVERY_BATCH_SIZE = 200;
    public static final int MAX_DELIVERY_BATCH_SIZE = 1000;

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
        // The same rule: an absent key is 0, so a non-positive value means "use the default".
        deliveryBatchSize = deliveryBatchSize <= 0
                ? DEFAULT_DELIVERY_BATCH_SIZE
                : Math.min(deliveryBatchSize, MAX_DELIVERY_BATCH_SIZE);
    }
}
