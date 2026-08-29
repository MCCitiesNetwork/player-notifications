package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The regression test for {@code /broadcast --persistent}'s central claim: a stored broadcast is an
 * <em>ordinary renderable notification</em>, with no special handling anywhere in {@code core}.
 *
 * <p>The shape that matters is one notification carrying <b>every recipient in a single
 * {@link NotificationTarget}</b> — one {@code Notification} row and one target row each. What this pins
 * down is that the per-target read state is genuinely per target: pushing to one recipient must stamp
 * only <em>their</em> {@code seenTime}, leaving the other's unread and still due. If that were ever
 * wrong, a broadcast delivered to one online player would silently vanish from everyone else's inbox.
 *
 * <p>{@code core} cannot see {@code paper.broadcast.BroadcastPayload}, so the payload here is a local
 * record. That is the point rather than a limitation — nothing about this behaviour depends on which
 * payload type it is.
 */
class PersistentBroadcastTest extends AbstractDatabaseTest {

    private record Broadcast(String message) {
    }

    private static final String DATA_TYPE = "broadcast";
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    private static final class RecordingSink implements NotificationSink {

        private final List<UUID> targets = new ArrayList<>();

        @Override
        public @NotNull String mediumKey() {
            return "recording";
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            this.targets.add(target);
            return DeliveryResult.DELIVERED;
        }
    }

    private static void registerBroadcast() {
        service.registerJsonRenderable(DATA_TYPE, Broadcast.class, (payload, target) ->
                new RenderableNotification(Component.text("Broadcast"),
                        Component.text(payload.message())));
    }

    /** Exactly what {@code PersistentBroadcaster} enqueues: one notification, every recipient. */
    private static void enqueueBroadcast(String key, String message, UUID... recipients) {
        service.enqueueNotification(new TypedNotification<>(
                key, DUE, null, new NotificationTarget(List.of(recipients)), DATA_TYPE,
                new Broadcast(message), 0), false);
    }

    private static NotificationDelivery delivery(NotificationSinkRegistry sinks) {
        NotificationPreferences preferences = player -> Set.of("recording");
        return new NotificationDelivery(database, service.dataTypeRegistry(), sinks, preferences,
                Logger.getLogger("test"));
    }

    private static List<String> stillDue(UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectDueByPlayer(player, NOW).stream()
                    .map(io.github.md5sha256.playernotifications.core.database.entity
                            .NotificationEntity::notifKey)
                    .toList();
        }
    }

    @Test
    @DisplayName("one enqueue puts the broadcast unread in every recipient's inbox")
    void landsUnreadInEveryInbox() {
        registerBroadcast();
        enqueueBroadcast("b1", "server restarting", ALICE, BOB);

        for (UUID player : List.of(ALICE, BOB)) {
            InboxPage page = service.inbox(player, 1, 10);
            Assertions.assertEquals(1, page.totalEntries(), "inbox of " + player);
            Assertions.assertEquals(1, page.unreadCount(), "unread count of " + player);
            Assertions.assertTrue(page.entries().get(0).unread());
            Assertions.assertEquals(DATA_TYPE, page.entries().get(0).notifPayloadType());
        }
    }

    @Test
    @DisplayName("delivering to one recipient marks only their copy seen")
    void deliveryIsPerTarget() {
        registerBroadcast();
        RecordingSink sink = new RecordingSink();
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);
        enqueueBroadcast("b2", "server restarting", ALICE, BOB);

        delivery(sinks).deliver(ALICE, NOW);

        Assertions.assertEquals(List.of(ALICE), sink.targets);
        // Alice has read it: still in her inbox, no longer unread, and never pushed again.
        Assertions.assertEquals(0, service.inbox(ALICE, 1, 10).unreadCount());
        Assertions.assertEquals(1, service.inbox(ALICE, 1, 10).totalEntries());
        Assertions.assertTrue(stillDue(ALICE).isEmpty());
        // Bob's copy is untouched — the failure this test exists to catch.
        Assertions.assertEquals(1, service.inbox(BOB, 1, 10).unreadCount());
        Assertions.assertEquals(List.of("b2"), stillDue(BOB));
    }

    @Test
    @DisplayName("a recipient dismissing their copy leaves everyone else's alone")
    void dismissalIsPerTarget() {
        registerBroadcast();
        enqueueBroadcast("b3", "server restarting", ALICE, BOB);

        service.deleteNotificationTarget("b3", ALICE);

        Assertions.assertEquals(0, service.inbox(ALICE, 1, 10).totalEntries());
        Assertions.assertEquals(1, service.inbox(BOB, 1, 10).totalEntries());
    }

    @Test
    @DisplayName("the last recipient dismissing it removes the notification itself")
    void theTriggerCleansUpOnceNobodyHoldsIt() {
        registerBroadcast();
        enqueueBroadcast("b4", "server restarting", ALICE, BOB);

        service.deleteNotificationTarget("b4", ALICE);
        service.deleteNotificationTarget("b4", BOB);

        try (SqlSessionWrapper wrapper = database.openSession()) {
            Assertions.assertNull(wrapper.notificationMapper().selectByKey("b4"));
        }
    }

    @Test
    @DisplayName("a persistent broadcast never expires, so a prune leaves it alone")
    void aPruneDoesNotRemoveIt() {
        registerBroadcast();
        enqueueBroadcast("b5", "server restarting", ALICE, BOB);

        service.clearExpiredNotifications();

        Assertions.assertEquals(1, service.inbox(ALICE, 1, 10).totalEntries());
        Assertions.assertEquals(1, service.inbox(BOB, 1, 10).totalEntries());
    }
}
