package io.github.md5sha256.playernotifications.paper.broadcast;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The {@code --persistent} path: stores a broadcast as a real {@code Notification} so it survives being
 * missed, then pushes it to whoever is online right now.
 *
 * <p>This is the counterpart to {@link Broadcaster}, not a replacement for it. A transient broadcast is
 * gone the moment it is not seen; a persistent one lands in the inbox, is pushed on the recipient's next
 * join by {@code JoinDeliveryListener}, and stays readable until dismissed.
 *
 * <h2>The three steps, in order</h2>
 * <ol>
 *     <li><b>Enqueue once.</b> One notification with every recipient in a single
 *     {@link NotificationTarget} — one {@code Notification} row and one target row each, which is the
 *     shape the schema exists for. The payload carries the <b>raw MiniMessage</b>, not the rendered
 *     component, so the stored form is the source text and {@link BroadcastRenderer} is the only thing
 *     deciding how it reads. A failure here propagates: pushing a broadcast that was never stored would
 *     announce something the inbox cannot show.</li>
 *     <li><b>Push the online recipients</b> through {@code NotificationDelivery.deliver}. That path —
 *     rather than {@link Broadcaster} — because it stamps {@code seenTime}, so a recipient who read it
 *     live is not pushed it again on their next join. It also honours the mute gate and the
 *     per-{@code dataType} preferences, which is why a suppressed recipient receives nothing here.
 *     Offline recipients are deliberately not pushed: {@code ChatSink} reports {@code DELIVERED} for an
 *     absent player, so delivering to one would mark it seen unread.</li>
 *     <li><b>Bypass the suppressed, if asked.</b> {@link Broadcaster#suppressed} names exactly the
 *     recipients step 2 delivered nothing to, and they are reached transiently with the {@code chat}
 *     fallback. They keep their <b>unread</b> inbox copy — they were reached out of band, and the stored
 *     record is what makes the message recoverable.</li>
 * </ol>
 *
 * <p>Steps 2 and 3 cannot overlap: step 3's set is by construction the set step 2 skipped.
 *
 * <p><b>The seams are narrow on purpose.</b> {@code isOnline} and {@code push} are a
 * {@link Predicate} and a {@link Consumer} rather than a {@code Server} and a
 * {@code NotificationDelivery}, because both of those need a live server to construct and neither
 * contributes a decision this class makes. Production wires {@code push} through a supplier, since
 * {@code /notifications reload} replaces the {@code NotificationDelivery} object.
 */
public final class PersistentBroadcaster {

    /** Per-send random, since two broadcasts sent in the same instant must not collide. */
    private static final String KEY_PREFIX = "broadcast-";

    private final NotificationService service;
    private final Predicate<UUID> isOnline;
    private final Consumer<UUID> push;
    private final Broadcaster broadcaster;
    private final Logger logger;

    public PersistentBroadcaster(@NotNull NotificationService service,
                                 @NotNull Predicate<UUID> isOnline,
                                 @NotNull Consumer<UUID> push,
                                 @NotNull Broadcaster broadcaster,
                                 @NotNull Logger logger) {
        this.service = service;
        this.isOnline = isOnline;
        this.push = push;
        this.broadcaster = broadcaster;
        this.logger = logger;
    }

    /**
     * Stores the broadcast for every recipient and pushes it to those reachable right now.
     *
     * @param content    the rendered content, used only for the {@code bypass} fan-out
     * @param rawContent the MiniMessage source, which is what gets stored
     * @param recipients every recipient; all of them get a stored copy
     * @param bypass     whether to reach the muted and silenced transiently as well
     * @return how many were stored, pushed and bypassed
     */
    @NotNull
    public Result broadcast(@NotNull Component content, @NotNull String rawContent,
                            @NotNull Collection<UUID> recipients, boolean bypass) {
        List<UUID> targets = List.copyOf(recipients);

        // Step 1. Deliberately not caught: a push announcing a broadcast the inbox cannot show is
        // worse than the command failing outright, and the caller reports the failure to the sender.
        this.service.enqueueNotification(new TypedNotification<>(
                KEY_PREFIX + UUID.randomUUID(),
                Instant.now(),
                null,
                new NotificationTarget(targets),
                Broadcaster.BROADCAST_DATA_TYPE,
                new BroadcastPayload(rawContent),
                0), false);

        // Step 2.
        int pushed = 0;
        for (UUID target : targets) {
            if (!this.isOnline.test(target)) {
                continue;
            }
            try {
                this.push.accept(target);
                pushed++;
            } catch (RuntimeException e) {
                // One recipient's delivery failing must not cost the rest theirs — and the broadcast
                // is stored either way, so they will be pushed it on their next join.
                this.logger.warning("Could not push a persistent broadcast to " + target
                        + "; it remains unread in their inbox: " + e.getMessage());
            }
        }

        // Step 3.
        int bypassed = 0;
        if (bypass) {
            List<UUID> suppressed = this.broadcaster.suppressed(targets);
            if (!suppressed.isEmpty()) {
                bypassed = this.broadcaster.broadcast(content, suppressed, true);
            }
        }

        return new Result(targets.size(), pushed, bypassed);
    }

    /**
     * @param stored   recipients the notification was stored for — always every recipient
     * @param pushed   recipients it was pushed to right now
     * @param bypassed suppressed recipients reached transiently by {@code --bypass}
     */
    public record Result(int stored, int pushed, int bypassed) {
    }
}
