package io.github.md5sha256.playernotifications.paper.broadcast;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.paper.localisation.TestMessages;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PersistentBroadcaster}'s three steps: enqueue once, push the online recipients through the
 * ordinary delivery path, and - only under bypass - reach the suppressed ones transiently.
 *
 * <p>The isOnline and push seams are a Predicate and a Consumer rather than a Server and a
 * NotificationDelivery, because both of those need a live server to construct. Everything worth
 * asserting sits on this side of that line.
 */
class PersistentBroadcasterTest {

    private static final Logger LOGGER = Logger.getLogger(PersistentBroadcasterTest.class.getName());

    /** Captures every enqueue call rather than persisting anything, as MailSenderTest does. */
    static class RecordingService implements NotificationService {

        final List<TypedNotification<?>> enqueued = new ArrayList<>();
        final List<Boolean> overwriteFlags = new ArrayList<>();

        @Override
        public void enqueueNotification(@NotNull ResolvedNotification notification, boolean overwriteAllowed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void enqueueNotification(@NotNull TypedNotification<T> notification, boolean overwriteAllowed) {
            this.enqueued.add(notification);
            this.overwriteFlags.add(overwriteAllowed);
        }

        @Override
        public <T> void registerJsonPayload(@NotNull String dataType, @NotNull Class<T> type,
                @NotNull io.github.md5sha256.playernotifications.api.processor.NotificationProcessor<T> processor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type,
                @NotNull io.github.md5sha256.playernotifications.api.render.NotificationRenderer<T> renderer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull List<ResolvedNotification> resolveNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotification(@NotNull String notificationKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTarget(@NotNull String notificationKey, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTargets(@NotNull String notificationKey, @NotNull Collection<UUID> playerIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull String notificationDataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearExpiredNotifications() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize, @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull Map<String, Integer> unreadCountsByDataType(@NotNull UUID playerId) {
            return Map.of();
        }

        @Override
        public int unreadCount(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSeen(@NotNull String key, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markUnread(@NotNull String key, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAllSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dismissSeen(@NotNull UUID playerId, @Nullable Collection<String> dataTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void pruneOrphanedTargets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationDataTypeRegistry dataTypeRegistry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationCategoryRegistry categoryRegistry() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A real chat sink that records who it delivered to. Broadcaster is final, so the bypass fan-out is
     * observed through the sink it actually reaches rather than by subclassing it - which also means
     * these tests exercise the real suppression rule instead of a stand-in for it.
     */
    private static final class RecordingChatSink
            implements io.github.md5sha256.playernotifications.api.render.NotificationSink {
        final List<UUID> delivered = new ArrayList<>();

        @Override
        public @NotNull String mediumKey() {
            return Broadcaster.FALLBACK_MEDIUM;
        }

        @Override
        public @NotNull io.github.md5sha256.playernotifications.api.render.DeliveryResult deliver(
                @NotNull io.github.md5sha256.playernotifications.api.render.RenderableNotification n,
                @NotNull UUID target) {
            this.delivered.add(target);
            return io.github.md5sha256.playernotifications.api.render.DeliveryResult.DELIVERED;
        }
    }

    private static Broadcaster broadcasterWith(Map<UUID, Set<String>> media, Set<UUID> muted,
                                               RecordingChatSink sink) {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);
        NotificationPreferences preferences = new NotificationPreferences() {
            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
                return media.getOrDefault(player, Set.of());
            }

            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType) {
                return media.getOrDefault(player, Set.of());
            }

            @Override
            public boolean isMuted(@NotNull UUID player) {
                return muted.contains(player);
            }
        };
        return new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);
    }

    @Test
    void enqueuesOneNotificationCarryingEveryRecipient() {
        RecordingService service = new RecordingService();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(service, uuid -> { }, broadcasterWith(Map.of(), Set.of(), new RecordingChatSink()), LOGGER);

        broadcaster.broadcast(Component.text("rendered"), "<red>raw</red>", List.of(alice, bob), false);

        assertEquals(1, service.enqueued.size());
        TypedNotification<?> notification = service.enqueued.get(0);
        assertEquals(List.of(alice, bob), notification.notifTarget().playerUUIDs());
        assertEquals(Broadcaster.BROADCAST_DATA_TYPE, notification.notifPayloadType());
        assertTrue(notification.notifKey().startsWith("broadcast-"));
        assertNull(notification.notifExpiryTime());
    }

    @Test
    void storesTheRawMiniMessageRatherThanTheRenderedComponent() {
        RecordingService service = new RecordingService();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(service, uuid -> { }, broadcasterWith(Map.of(), Set.of(), new RecordingChatSink()), LOGGER);

        broadcaster.broadcast(Component.text("rendered"), "<red>raw</red>",
                List.of(UUID.randomUUID()), false);

        BroadcastPayload payload = (BroadcastPayload) service.enqueued.get(0).notifPayload();
        assertEquals("<red>raw</red>", payload.message());
    }

    @Test
    void pushesEveryRecipientIncludingOnesWhoAreOffline() {
        // The bug this pins: offline recipients used to be skipped, on the false premise that ChatSink
        // reports DELIVERED for an absent player. It does not — it returns UNREACHABLE — so the sinks
        // can be trusted to decide, and DiscordDmSink reaches a linked player who is not logged in.
        // Skipping them made --offline fail at the one thing it exists for.
        RecordingService service = new RecordingService();
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        List<UUID> pushed = new ArrayList<>();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(service, pushed::add,
                broadcasterWith(Map.of(), Set.of(), new RecordingChatSink()), LOGGER);

        PersistentBroadcaster.Result result = broadcaster.broadcast(Component.text("hi"), "hi",
                List.of(online, offline), false);

        assertEquals(List.of(online, offline), pushed);
        assertEquals(2, result.stored());
        assertEquals(2, result.attempted());
        assertEquals(0, result.failed());
    }

    @Test
    void withoutBypassTheTransientFanOutIsNeverCalled() {
        RecordingService service = new RecordingService();
        UUID muted = UUID.randomUUID();
        RecordingChatSink sink = new RecordingChatSink();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(service, uuid -> { }, broadcasterWith(Map.of(), Set.of(muted), sink), LOGGER);

        PersistentBroadcaster.Result result =
                broadcaster.broadcast(Component.text("hi"), "hi", List.of(muted), false);

        assertTrue(sink.delivered.isEmpty());
        assertEquals(0, result.bypassed());
    }

    @Test
    void withBypassTheTransientFanOutReachesExactlyTheSuppressed() {
        RecordingService service = new RecordingService();
        UUID muted = UUID.randomUUID();
        UUID reachable = UUID.randomUUID();
        RecordingChatSink sink = new RecordingChatSink();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(service, uuid -> { },
                broadcasterWith(Map.of(reachable, Set.of("chat")), Set.of(muted), sink), LOGGER);

        PersistentBroadcaster.Result result = broadcaster.broadcast(Component.text("hi"), "hi",
                List.of(muted, reachable), true);

        // Only the muted recipient is reached transiently; the reachable one was already pushed.
        assertEquals(List.of(muted), sink.delivered);
        assertEquals(1, result.bypassed());
    }

    @Test
    void aThrowingPushIsCountedAsFailedAndDoesNotStopTheRest() {
        RecordingService service = new RecordingService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<UUID> pushed = new ArrayList<>();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(service, uuid -> {
            if (uuid.equals(first)) {
                throw new RuntimeException("boom");
            }
            pushed.add(uuid);
        }, broadcasterWith(Map.of(), Set.of(), new RecordingChatSink()), LOGGER);

        PersistentBroadcaster.Result result =
                broadcaster.broadcast(Component.text("hi"), "hi", List.of(first, second), false);

        assertEquals(List.of(second), pushed);
        assertEquals(2, result.stored());
        assertEquals(1, result.attempted());
        // Counted, not silent: "attempted 0" must not be ambiguous between nobody being reachable and
        // every delivery blowing up, since those need completely different responses from an operator.
        assertEquals(1, result.failed());
    }

    @Test
    void anEnqueueFailurePropagatesRatherThanPushingAnUnstoredBroadcast() {
        RecordingService failing = new RecordingService() {
            @Override
            public <T> void enqueueNotification(@NotNull TypedNotification<T> notification,
                                                boolean overwriteAllowed) {
                throw new IllegalStateException("database down");
            }
        };
        List<UUID> pushed = new ArrayList<>();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(failing, pushed::add,
                broadcasterWith(Map.of(), Set.of(), new RecordingChatSink()), LOGGER);

        Assertions.assertThrows(IllegalStateException.class,
                () -> broadcaster.broadcast(Component.text("hi"), "hi",
                        List.of(UUID.randomUUID()), false));
        assertTrue(pushed.isEmpty());
    }

    // --- typed broadcasts: /broadcast --type <key> ---

    @Test
    void aTypedBroadcastIsStoredUnderTheDeclaredDataTypeAndPayload() {
        RecordingService service = new RecordingService();
        UUID alice = UUID.randomUUID();
        PersistentBroadcaster broadcaster = new PersistentBroadcaster(service, uuid -> { },
                broadcasterWith(Map.of(), Set.of(), new RecordingChatSink()), LOGGER);
        io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationPayload payload =
                new io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationPayload(
                        "restart-warning", "<red>raw</red>");

        PersistentBroadcaster.Result result = broadcaster.broadcast(Component.text("Server Restart"),
                Component.text("rendered"), "restart-warning", payload, List.of(alice), false);

        assertEquals(1, result.stored());
        TypedNotification<?> notification = service.enqueued.get(0);
        assertEquals("restart-warning", notification.notifPayloadType());
        assertEquals(payload, notification.notifPayload());
        assertEquals(List.of(alice), notification.notifTarget().playerUUIDs());
    }

    @Test
    void aTypedBypassAsksWhoWasSuppressedForThatType() {
        RecordingService service = new RecordingService();
        UUID silenced = UUID.randomUUID();
        RecordingChatSink sink = new RecordingChatSink();
        // Reachable for ordinary broadcasts, silenced for restart-warning: only a data-type-aware
        // suppressed() names them, and only then does --bypass reach them.
        Broadcaster broadcaster = broadcasterForDataType(Broadcaster.BROADCAST_DATA_TYPE, sink);
        PersistentBroadcaster persistent =
                new PersistentBroadcaster(service, uuid -> { }, broadcaster, LOGGER);

        PersistentBroadcaster.Result result = persistent.broadcast(Component.text("Server Restart"),
                Component.text("rendered"), "restart-warning",
                new io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationPayload(
                        "restart-warning", "raw"),
                List.of(silenced), true);

        assertEquals(1, result.bypassed());
        assertEquals(List.of(silenced), sink.delivered);
    }

    /** Media declared for exactly one data type; every other type resolves to nothing. */
    private static Broadcaster broadcasterForDataType(String dataType, RecordingChatSink sink) {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);
        NotificationPreferences preferences = new NotificationPreferences() {
            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
                return Set.of();
            }

            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String type) {
                return dataType.equals(type) ? Set.of(Broadcaster.FALLBACK_MEDIUM) : Set.of();
            }

            @Override
            public boolean isMuted(@NotNull UUID player) {
                return false;
            }
        };
        return new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);
    }
}
