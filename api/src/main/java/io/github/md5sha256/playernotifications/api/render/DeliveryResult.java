package io.github.md5sha256.playernotifications.api.render;

/**
 * The outcome a {@link NotificationSink} reports for a single delivery attempt, distinguishing
 * transient failure (worth retrying) from permanent failure (worth logging once and leaving alone).
 */
public enum DeliveryResult {

    /** The notification reached the player. */
    DELIVERED,

    /** Transient failure: offline, an API hiccup, rate limiting — retry later. */
    UNREACHABLE,

    /**
     * Permanent failure: this sink can never serve this player (e.g. no linked Discord account). Exists
     * so a standing misconfiguration can be logged once as a warning instead of being retried silently
     * on every delivery pass.
     */
    UNSUPPORTED
}
