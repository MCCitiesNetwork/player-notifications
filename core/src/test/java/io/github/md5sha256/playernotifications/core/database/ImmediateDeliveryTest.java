package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.NotificationAudience;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.OfflineLookupUnavailableException;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.AudienceResolver;
import io.github.md5sha256.playernotifications.core.DefaultNotificationService;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import io.github.md5sha256.playernotifications.core.database.entity.NotificationEntity;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The immediate-delivery half of {@code NotificationService}: store-and-push, audience resolution and
 * transient pushes, run against real MariaDB with a same-thread executor so every future is complete by
 * the time the call returns.
 */
class ImmediateDeliveryTest extends AbstractDatabaseTest {

    private record Ping(String message) {
    }

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Instant DUE = Instant.now().minus(1, ChronoUnit.MINUTES)
            .truncatedTo(ChronoUnit.SECONDS);
    private static final String MEDIUM = "recording";

    /** Records every (target, notification) it is handed and reports {@link #result}. */
    private static final class RecordingSink implements NotificationSink {

        private final List<UUID> targets = new ArrayList<>();
        private DeliveryResult result = DeliveryResult.DELIVERED;

        @Override
        public @NotNull String mediumKey() {
            return MEDIUM;
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            this.targets.add(target);
            return this.result;
        }
    }

    /** Preferences every test can bend: the media a type resolves to, and who is muted. */
    private static final class TestPreferences implements NotificationPreferences {

        private Set<String> media = Set.of(MEDIUM);
        private Predicate<UUID> muted = player -> false;

        @Override
        public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
            return this.media;
        }

        @Override
        public boolean isMuted(@NotNull UUID player) {
            return this.muted.test(player);
        }
    }

    private RecordingSink sink;
    private TestPreferences preferences;
    private NotificationDelivery delivery;
    private Set<UUID> resolved;

    @BeforeEach
    void attach() {
        service.registerJsonRenderable("ping", Ping.class, (payload, target) ->
                new RenderableNotification(Component.text("Ping"), Component.text(payload.message())));
        this.sink = new RecordingSink();
        this.preferences = new TestPreferences();
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(this.sink);
        this.delivery = new NotificationDelivery(database, service.dataTypeRegistry(), sinks,
                this.preferences, Logger.getLogger("test"));
        this.resolved = Set.of();
        service.attachHost(() -> this.delivery, Runnable::run, audience -> this.resolved,
                Logger.getLogger("test"));
    }

    private static TypedNotification<Ping> ping(String key, List<UUID> targets) {
        return new TypedNotification<>(key, DUE, null, new NotificationTarget(targets), "ping",
                new Ping("hello"), 0);
    }

    private static RenderableNotification renderable() {
        return new RenderableNotification(Component.text("T"), Component.text("B"));
    }

    private static boolean exists(String key) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectByKey(key) != null;
        }
    }

    private static List<String> stillDue(UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectDueByPlayer(player, Instant.now()).stream()
                    .map(NotificationEntity::notifKey)
                    .toList();
        }
    }

    @Test
    @DisplayName("enqueueAndDeliver stores the notification and pushes it now, marking it seen")
    void storesAndDelivers() {
        service.enqueueAndDeliver(ping("k1", List.of(A)), false).join();

        Assertions.assertEquals(List.of(A), this.sink.targets);
        Assertions.assertTrue(exists("k1"));
        Assertions.assertEquals(List.of(), stillDue(A));
    }

    @Test
    @DisplayName("the ResolvedNotification overload stores and pushes too")
    void resolvedOverloadDelivers() {
        service.enqueueAndDeliver(new ResolvedNotification("k1r", DUE, null,
                new NotificationTarget(List.of(A)), "ping", "{\"message\":\"hi\"}", 0), false).join();

        Assertions.assertEquals(List.of(A), this.sink.targets);
        Assertions.assertTrue(exists("k1r"));
    }

    @Test
    @DisplayName("an unreachable recipient keeps the notification stored and due for their next join")
    void unreachableStaysDue() {
        this.sink.result = DeliveryResult.UNREACHABLE;

        service.enqueueAndDeliver(ping("k2", List.of(A)), false).join();

        Assertions.assertTrue(exists("k2"));
        Assertions.assertEquals(List.of("k2"), stillDue(A));
    }

    @Test
    @DisplayName("a muted recipient is pushed nothing and the notification stays due")
    void mutedReceivesNothing() {
        this.preferences.muted = player -> true;

        service.enqueueAndDeliver(ping("k3", List.of(A)), false).join();

        Assertions.assertEquals(List.of(), this.sink.targets);
        Assertions.assertEquals(List.of("k3"), stillDue(A));
    }

    @Test
    @DisplayName("a failed enqueue fails the future and pushes nothing")
    void failedEnqueueDeliversNothing() {
        service.enqueueNotification(ping("k4", List.of(B)), false);

        CompletableFuture<Void> future = service.enqueueAndDeliver(ping("k4", List.of(A)), false);

        Assertions.assertThrows(CompletionException.class, future::join);
        Assertions.assertEquals(List.of(), this.sink.targets);
    }

    @Test
    @DisplayName("one target's delivery throwing does not stop the others")
    void oneFailingTargetDoesNotStopOthers() {
        // isMuted runs unguarded at the top of deliver; a throwing sink would not do, because
        // RenderingProcessor already catches those as UNREACHABLE.
        this.preferences.muted = player -> {
            if (player.equals(A)) {
                throw new IllegalStateException("boom");
            }
            return false;
        };

        service.enqueueAndDeliver(ping("k5", List.of(A, B)), false).join();

        Assertions.assertEquals(List.of(B), this.sink.targets);
    }

    @Test
    @DisplayName("a player listed twice gets one delivery pass")
    void duplicateTargetsCollapse() {
        this.resolved = Set.of(A);

        service.deliverTransient("ping", renderable(), NotificationAudience.players(List.of(A, A))).join();

        Assertions.assertEquals(List.of(A), this.sink.targets);
    }

    @Test
    @DisplayName("a refusing executor fails the future instead of throwing from the call")
    void rejectingExecutorFailsFuture() {
        service.attachHost(() -> this.delivery, runnable -> {
            throw new IllegalStateException("plugin disabled");
        }, audience -> Set.of(), Logger.getLogger("test"));

        CompletableFuture<Void> future = service.enqueueAndDeliver(ping("k7", List.of(A)), false);

        Assertions.assertTrue(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("a missing delivery fails the future")
    void nullDeliveryFailsFuture() {
        service.attachHost(() -> null, Runnable::run, audience -> Set.of(), Logger.getLogger("test"));

        CompletableFuture<Void> future = service.enqueueAndDeliver(ping("k8", List.of(A)), false);

        Assertions.assertTrue(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("a service never attached to a host fails the future")
    void unattachedFailsFuture() {
        DefaultNotificationService unattached = new DefaultNotificationService(database);

        CompletableFuture<Void> future = unattached.enqueueAndDeliver(ping("k9", List.of(A)), false);

        Assertions.assertTrue(future.isCompletedExceptionally());
        Assertions.assertFalse(exists("k9"));
    }

    @Test
    @DisplayName("a permission audience replaces the notification's own target with the resolved players")
    void permissionAudienceBecomesTheTarget() {
        this.resolved = Set.of(A, B);

        service.enqueueAndDeliver(ping("k10", List.of()),
                NotificationAudience.withAnyPermission(false, "x.y"), false).join();

        Assertions.assertEquals(1, service.resolveNotifications(A).size());
        Assertions.assertEquals(1, service.resolveNotifications(B).size());
        Assertions.assertEquals(Set.of(A, B), Set.copyOf(this.sink.targets));
    }

    @Test
    @DisplayName("an audience resolving to nobody stores nothing and completes normally")
    void emptyAudienceStoresNothing() {
        this.resolved = Set.of();

        service.enqueueAndDeliver(ping("k11", List.of()),
                NotificationAudience.withAnyPermission(false, "x.y"), false).join();

        Assertions.assertFalse(exists("k11"));
    }

    @Test
    @DisplayName("an unavailable offline lookup fails the future with that exception as the cause")
    void offlineLookupUnavailableFailsFuture() {
        AudienceResolver refusing = audience -> {
            throw new OfflineLookupUnavailableException("no LuckPerms");
        };
        service.attachHost(() -> this.delivery, Runnable::run, refusing, Logger.getLogger("test"));

        CompletableFuture<Void> future = service.enqueueAndDeliver(ping("k12", List.of()),
                NotificationAudience.withAnyPermission(true, "x.y"), false);

        CompletionException thrown = Assertions.assertThrows(CompletionException.class, future::join);
        Assertions.assertInstanceOf(OfflineLookupUnavailableException.class, thrown.getCause());
        Assertions.assertFalse(exists("k12"));
    }

    @Test
    @DisplayName("a transient send reaches the preferred sink and stores nothing")
    void transientStoresNothing() {
        service.deliverTransient("ping", renderable(), NotificationAudience.players(List.of(A))).join();

        Assertions.assertEquals(List.of(A), this.sink.targets);
        Assertions.assertEquals(0, service.inbox(A, 1, 20).totalEntries());
    }

    @Test
    @DisplayName("a transient send respects the mute and a per-type silence")
    void transientRespectsMuteAndSilence() {
        this.preferences.muted = A::equals;
        this.preferences.media = Set.of(NotificationPreferences.SILENCED_MEDIUM);

        service.deliverTransient("ping", renderable(), NotificationAudience.players(List.of(A, B))).join();

        Assertions.assertEquals(List.of(), this.sink.targets);
    }
}
