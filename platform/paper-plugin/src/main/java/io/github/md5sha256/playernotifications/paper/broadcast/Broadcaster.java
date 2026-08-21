package io.github.md5sha256.playernotifications.paper.broadcast;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Fans a broadcast out through whichever media each recipient prefers for {@link #BROADCAST_DATA_TYPE},
 * mirroring {@link io.github.md5sha256.playernotifications.paper.mail.MailNotifier#notifyArrival(UUID)}
 * per recipient rather than for one.
 *
 * <p>A broadcast is <strong>never stored</strong> — there is no {@code Notification} row, no
 * {@code notifKey}, and no {@link io.github.md5sha256.playernotifications.api.processor.NotificationDisposition}
 * to report back to a delivery loop, because its audience ("whoever is online and holds the permission
 * right now") is not a set that survives being written down. {@code Broadcaster} takes a plain
 * {@link Collection} of {@link UUID}s and never asks whether a recipient is online, so it is already
 * offline-neutral — the {@link #FALLBACK_MEDIUM} fallback below is the one exception, because it is the
 * one place this class has to pick a medium on the recipient's behalf rather than merely deliver to
 * whichever the recipient already chose.
 *
 * <p>The fallback is {@code chat} specifically because {@code ChatSink} is registered unconditionally by
 * the host — it is the one medium that cannot be missing. Falling back to the server's {@code
 * default-media} instead was rejected: a server that misconfigures it would make {@code --bypass}
 * silently deliver nowhere, which is exactly the failure the flag exists to rule out.
 */
public final class Broadcaster {

    /**
     * The registered {@code dataType} mapping key for a broadcast, so it appears in
     * {@code /notifications preferences} and can be silenced like any other type — see
     * {@code paper.broadcast.BroadcastPayload}.
     */
    public static final String BROADCAST_DATA_TYPE = "broadcast";

    /**
     * The medium a bypassed recipient with no usable preferred medium falls back to. {@code ChatSink} is
     * registered unconditionally, so this is the one medium guaranteed to exist.
     */
    public static final String FALLBACK_MEDIUM = "chat";

    /**
     * The title every broadcast renders with. Non-chat sinks need a title regardless of whether the
     * command supplies one — a Discord embed has one either way — and {@link RenderableNotification}
     * requires one.
     */

    private final MessageContainer messages;
    private final NotificationSinkRegistry sinks;
    private final NotificationPreferences preferences;
    private final Logger logger;

    public Broadcaster(@NotNull MessageContainer messages,
                       @NotNull NotificationSinkRegistry sinks,
                        @NotNull NotificationPreferences preferences,
                        @NotNull Logger logger) {
        this.messages = messages;
        this.sinks = sinks;
        this.preferences = preferences;
        this.logger = logger;
    }

    /**
     * Delivers {@code content} to every recipient in {@code recipients}, per-recipient:
     * <ol>
     *     <li>Skipped, uncounted, when {@link NotificationPreferences#isMuted(UUID)} is true and
     *     {@code bypass} is false.</li>
     *     <li>Otherwise resolves {@link NotificationPreferences#preferredMedia(UUID, String)} for
     *     {@link #BROADCAST_DATA_TYPE} and drops {@link NotificationPreferences#SILENCED_MEDIUM}.</li>
     *     <li>If the resolved set is empty: skipped, uncounted, unless {@code bypass} is true, in which
     *     case delivery falls back to {@link #FALLBACK_MEDIUM} alone. A bypassed recipient who
     *     <em>does</em> have usable media is delivered to those, never to the fallback — bypass overrides
     *     the suppression, not the recipient's choice of medium.</li>
     *     <li>For each resolved medium, an unregistered sink is skipped with a {@code fine} log; a
     *     registered sink is delivered to inside a {@code try}/{@code catch (RuntimeException)} so one
     *     broken sink cannot suppress the others, and a non-{@link DeliveryResult#DELIVERED} result is
     *     {@code fine}-logged.</li>
     *     <li>The recipient is counted once delivery was attempted through at least the fallback or the
     *     resolved media (i.e. whenever step 3 did not skip them).</li>
     * </ol>
     *
     * @return the number of recipients the broadcast was actually attempted for
     */
    public int broadcast(@NotNull Component content, @NotNull Collection<UUID> recipients, boolean bypass) {
        int attempted = 0;
        for (UUID recipient : recipients) {
            if (this.preferences.isMuted(recipient) && !bypass) {
                this.logger.fine(() -> "Recipient " + recipient + " is muted; skipping the broadcast");
                continue;
            }
            Set<String> media = new LinkedHashSet<>(
                    this.preferences.preferredMedia(recipient, BROADCAST_DATA_TYPE));
            media.remove(NotificationPreferences.SILENCED_MEDIUM);
            if (media.isEmpty()) {
                if (!bypass) {
                    this.logger.fine(() -> "Recipient " + recipient
                            + " has no usable media for a broadcast; skipping");
                    continue;
                }
                media = Set.of(FALLBACK_MEDIUM);
            }
            // Read per broadcast rather than held in a field, so /notifications reload takes effect.
            RenderableNotification notification = new RenderableNotification(
                    this.messages.messageFor(MessageKeys.BROADCAST_TITLE), content);
            for (String medium : media) {
                Optional<NotificationSink> sink = this.sinks.getSink(medium);
                if (sink.isEmpty()) {
                    this.logger.fine(() -> "No sink registered for medium '" + medium
                            + "'; skipping the broadcast");
                    continue;
                }
                deliverSafely(sink.get(), notification, recipient);
            }
            attempted++;
        }
        return attempted;
    }

    private void deliverSafely(@NotNull NotificationSink sink, @NotNull RenderableNotification notification,
                                @NotNull UUID recipient) {
        try {
            DeliveryResult result = sink.deliver(notification, recipient);
            if (result != DeliveryResult.DELIVERED) {
                this.logger.fine(() -> "Broadcast not delivered via '" + sink.mediumKey()
                        + "' for " + recipient + ": " + result);
            }
        } catch (RuntimeException e) {
            this.logger.warning("Sink '" + sink.mediumKey() + "' threw while delivering a broadcast to "
                    + recipient + "; treating as unreachable: " + e.getMessage());
        }
    }
}
