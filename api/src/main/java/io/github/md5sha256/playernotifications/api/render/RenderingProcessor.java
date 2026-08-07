package io.github.md5sha256.playernotifications.api.render;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The single, framework-supplied {@link NotificationProcessor} that binds a payload's
 * {@link NotificationRenderer} to whichever media a player prefers, so payload authors never write
 * dispatch logic themselves.
 *
 * <p>{@link #receiveNotification(Object, UUID)}:
 * <ol>
 *     <li>Resolves the target's {@link NotificationPreferences#preferredMedia(UUID) preferred media},
 *     dropping {@link NotificationPreferences#MUTED_MEDIUM}. If nothing deliverable remains, logs at
 *     {@code fine} and returns {@link NotificationDisposition#RETAIN}, leaving the notification unread
 *     in the player's inbox — a mute means "do not interrupt me", not "throw this away".</li>
 *     <li>Renders the payload once into a {@link RenderableNotification}.</li>
 *     <li>Delivers to the sink registered for each preferred medium. A medium with no registered sink
 *     is logged at {@code fine} and skipped — the sink may simply not be installed yet.</li>
 *     <li>Folds the results: returns {@link NotificationDisposition#MARK_SEEN} if any sink reported
 *     {@link DeliveryResult#DELIVERED}, otherwise {@link NotificationDisposition#RETAIN}.</li>
 *     <li>If at least one sink delivered and another did not, logs which media were dropped so the
 *     partial-delivery limitation is observable: {@link DeliveryResult#UNREACHABLE} at {@code fine}
 *     (expected and transient), {@link DeliveryResult#UNSUPPORTED} at {@code warning} (a standing
 *     misconfiguration an operator should fix). If nothing was delivered and at least one medium
 *     reported {@link DeliveryResult#UNSUPPORTED}, logs a {@code warning} naming those media — a
 *     player whose only preferred media are permanently unsupported would otherwise accumulate
 *     notifications until expiry with no operator-visible signal.</li>
 * </ol>
 *
 * <p>A sink throwing a {@link RuntimeException} is caught, logged, and treated as
 * {@link DeliveryResult#UNREACHABLE}, so one broken sink cannot abort delivery to the others.
 *
 * @param <T> the payload type this processor accepts
 */
public final class RenderingProcessor<T> implements NotificationProcessor<T> {

    private final NotificationRenderer<T> renderer;
    private final NotificationSinkRegistry sinks;
    private final NotificationPreferences preferences;
    private final String dataType;
    private final Logger logger;

    /**
     * Constructs a processor that resolves preferred media for the given payload's {@code dataType} via
     * {@link NotificationPreferences#preferredMedia(UUID, String)}.
     */
    public RenderingProcessor(@NotNull NotificationRenderer<T> renderer,
                              @NotNull NotificationSinkRegistry sinks,
                              @NotNull NotificationPreferences preferences,
                              @NotNull String dataType,
                              @NotNull Logger logger) {
        this.renderer = renderer;
        this.sinks = sinks;
        this.preferences = preferences;
        this.dataType = dataType;
        this.logger = logger;
    }

    @Override
    public @NotNull NotificationDisposition receiveNotification(@NotNull T payload, @NotNull UUID target) {
        Set<String> media = new LinkedHashSet<>(this.preferences.preferredMedia(target, this.dataType));
        media.remove(NotificationPreferences.MUTED_MEDIUM);
        if (media.isEmpty()) {
            this.logger.fine(() -> "No deliverable media for " + target + "; leaving notification unread");
            return NotificationDisposition.RETAIN;
        }

        RenderableNotification rendered = this.renderer.render(payload, target);

        Map<String, DeliveryResult> results = new LinkedHashMap<>();
        for (String medium : media) {
            Optional<NotificationSink> sink = this.sinks.getSink(medium);
            if (sink.isEmpty()) {
                this.logger.fine(() -> "No sink registered for medium '" + medium + "'; skipping");
                continue;
            }
            results.put(medium, deliverSafely(sink.get(), rendered, target));
        }

        return fold(results, target);
    }

    @NotNull
    private DeliveryResult deliverSafely(@NotNull NotificationSink sink,
                                         @NotNull RenderableNotification rendered,
                                         @NotNull UUID target) {
        try {
            return sink.deliver(rendered, target);
        } catch (RuntimeException e) {
            this.logger.warning("Sink '" + sink.mediumKey() + "' threw while delivering to " + target
                    + "; treating as UNREACHABLE: " + e.getMessage());
            return DeliveryResult.UNREACHABLE;
        }
    }

    @NotNull
    private NotificationDisposition fold(@NotNull Map<String, DeliveryResult> results, @NotNull UUID target) {
        boolean anyDelivered = results.containsValue(DeliveryResult.DELIVERED);
        if (anyDelivered) {
            logDropped(results);
            return NotificationDisposition.MARK_SEEN;
        }
        warnIfAllUnsupported(results, target);
        return NotificationDisposition.RETAIN;
    }

    /**
     * When nothing was delivered and at least one medium reported {@link DeliveryResult#UNSUPPORTED},
     * logs a warning naming those media. This is the "player prefers only Discord and never links an
     * account" case from the design doc's "Known limitations" — without this, the notification silently
     * sits unread until {@code notifExpiryTime} with no operator-visible signal.
     */
    private void warnIfAllUnsupported(@NotNull Map<String, DeliveryResult> results, @NotNull UUID target) {
        List<String> unsupported = new ArrayList<>();
        for (Map.Entry<String, DeliveryResult> entry : results.entrySet()) {
            if (entry.getValue() == DeliveryResult.UNSUPPORTED) {
                unsupported.add(entry.getKey());
            }
        }
        if (!unsupported.isEmpty()) {
            this.logger.warning("No sink delivered notification to " + target + "; unsupported media: "
                    + unsupported + ". This is a standing misconfiguration.");
        }
    }

    /**
     * When at least one sink delivered and another did not, logs which media were dropped so the
     * partial-delivery limitation (see design doc "Known limitations") is observable.
     */
    private void logDropped(@NotNull Map<String, DeliveryResult> results) {
        for (Map.Entry<String, DeliveryResult> entry : results.entrySet()) {
            switch (entry.getValue()) {
                case DELIVERED -> {
                    // Nothing to log; this is the success case.
                }
                case UNREACHABLE -> this.logger.fine(() -> "Medium '" + entry.getKey()
                        + "' was unreachable while another medium delivered; notification marked seen");
                case UNSUPPORTED -> this.logger.warning("Medium '" + entry.getKey()
                        + "' does not support this player while another medium delivered; notification"
                        + " marked seen. This is a standing misconfiguration.");
            }
        }
    }
}
