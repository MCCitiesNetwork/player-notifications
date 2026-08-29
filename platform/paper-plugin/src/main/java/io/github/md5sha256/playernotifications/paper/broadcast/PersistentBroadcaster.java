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
import java.util.logging.Logger;

/**
 * The {@code --persistent} path: stores a broadcast as a real {@code Notification} so it survives being
 * missed, then runs a delivery pass for every recipient.
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
 *     <li><b>Push every recipient</b> through {@code NotificationDelivery.deliver}. That path — rather
 *     than {@link Broadcaster} — because it stamps {@code seenTime}, so a recipient who read it live is
 *     not pushed it again on their next join. It also honours the mute gate and the
 *     per-{@code dataType} preferences, which is why a suppressed recipient receives nothing here.
 *
 *     <p><b>Offline recipients are pushed too, and the sinks decide.</b> An earlier version skipped
 *     them, on the premise that {@code ChatSink} reports {@code DELIVERED} for an absent player and
 *     would therefore mark the notification seen for someone who saw nothing. <b>That premise was
 *     false</b> — {@code ChatSink} looks the {@code Player} up and returns {@code UNREACHABLE} when it
 *     is null — and the skip made {@code --offline} fail at the one thing it exists for, since
 *     {@code DiscordDmSink} reaches a linked player perfectly well while they are logged out. Letting
 *     each sink answer is both simpler and correct: MARK_SEEN-wins marks it seen only if something
 *     genuinely delivered, and a chat-only recipient who is offline gets all-{@code UNREACHABLE},
 *     stays unread, and is pushed again on join.</li>
 *     <li><b>Bypass the suppressed, if asked.</b> {@link Broadcaster#suppressed} names exactly the
 *     recipients step 2 delivered nothing to, and they are reached transiently with the {@code chat}
 *     fallback. They keep their <b>unread</b> inbox copy — they were reached out of band, and the stored
 *     record is what makes the message recoverable.</li>
 * </ol>
 *
 * <p>Steps 2 and 3 do not double up. Step 2 runs for everyone, but a muted recipient is stopped by the
 * gate at the top of {@code NotificationDelivery.deliver} and a silenced one by
 * {@code RenderingProcessor}, so neither receives anything from it — and those are exactly the
 * recipients {@link Broadcaster#suppressed} returns for step 3.
 *
 * <p><b>The seam is narrow on purpose.</b> {@code push} is a {@link Consumer} rather than a
 * {@code NotificationDelivery}, which needs a live server to construct and contributes no decision this
 * class makes. Production wires it to read the delivery field at call time, since
 * {@code /notifications reload} replaces that object.
 */
public final class PersistentBroadcaster {

    /** Per-send random, since two broadcasts sent in the same instant must not collide. */
    private static final String KEY_PREFIX = "broadcast-";

    private final NotificationService service;
    private final Consumer<UUID> push;
    private final Broadcaster broadcaster;
    private final Logger logger;

    public PersistentBroadcaster(@NotNull NotificationService service,
                                 @NotNull Consumer<UUID> push,
                                 @NotNull Broadcaster broadcaster,
                                 @NotNull Logger logger) {
        this.service = service;
        this.push = push;
        this.broadcaster = broadcaster;
        this.logger = logger;
    }

    /**
     * Stores the broadcast for every recipient and runs a delivery pass for each of them.
     *
     * @param content    the rendered content, used only for the {@code bypass} fan-out
     * @param rawContent the MiniMessage source, which is what gets stored
     * @param recipients every recipient; all of them get a stored copy and a delivery attempt
     * @param bypass     whether to reach the muted and silenced transiently as well
     * @return how many were stored, attempted, failed and bypassed
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

        // Step 2. Every recipient, online or not - the sinks decide who they can actually reach.
        int attempted = 0;
        int failed = 0;
        for (UUID target : targets) {
            try {
                this.push.accept(target);
                attempted++;
            } catch (RuntimeException e) {
                // One recipient failing must not cost the rest theirs - and the broadcast is stored
                // either way, so they will be pushed it on their next join.
                failed++;
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

        return new Result(targets.size(), attempted, failed, bypassed);
    }

    /**
     * <b>{@code attempted} is not {@code delivered}.</b> {@code NotificationDelivery.deliver} returns
     * {@code void}, so this class cannot know whether any sink reached the player - the same honesty
     * {@code /notifications test} observes when it names the media it attempted rather than the ones
     * that worked.
     *
     * <p>{@code failed} exists because an attempted count of zero was otherwise ambiguous between
     * "nobody was reachable" and "every single delivery threw", which need completely different
     * responses from whoever ran the command.
     *
     * @param stored    recipients the notification was stored for - always every recipient
     * @param attempted recipients a delivery pass ran for without throwing
     * @param failed    recipients whose delivery pass threw; logged, and stored unread regardless
     * @param bypassed  suppressed recipients reached transiently by {@code --bypass}
     */
    public record Result(int stored, int attempted, int failed, int bypassed) {
    }
}
