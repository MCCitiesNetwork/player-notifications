package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.BatchDeliveryReport;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import io.github.md5sha256.playernotifications.core.database.entity.DueNotificationEntity;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * {@link NotificationDelivery#deliver(java.util.Collection)}: chunked passes with one due query and one
 * seen-marking write per chunk. A batch size of 2 makes every three-player case cross a chunk boundary.
 */
class BatchDeliveryTest extends AbstractDatabaseTest {

    private record Ping(String message) {
    }

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final String MEDIUM = "recording";

    private static final class RecordingSink implements NotificationSink {

        private final List<UUID> targets = new ArrayList<>();
        private Predicate<UUID> unreachable = player -> false;

        @Override
        public @NotNull String mediumKey() {
            return MEDIUM;
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            this.targets.add(target);
            return this.unreachable.test(target) ? DeliveryResult.UNREACHABLE : DeliveryResult.DELIVERED;
        }
    }

    private static final class TestPreferences implements NotificationPreferences {

        private Predicate<UUID> muted = player -> false;

        @Override
        public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
            return Set.of(MEDIUM);
        }

        @Override
        public boolean isMuted(@NotNull UUID player) {
            return this.muted.test(player);
        }
    }

    private RecordingSink sink;
    private TestPreferences preferences;
    private NotificationDelivery delivery;

    @BeforeEach
    void setUp() {
        service.registerJsonRenderable("ping", Ping.class, (payload, target) ->
                new RenderableNotification(Component.text("Ping"), Component.text(payload.message())));
        this.sink = new RecordingSink();
        this.preferences = new TestPreferences();
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(this.sink);
        this.delivery = new NotificationDelivery(database, service.dataTypeRegistry(), sinks, this.preferences,
                Logger.getLogger("test"), 2);
    }

    private static void enqueue(String key, Instant scheduled, Instant expiry, UUID... targets) {
        service.enqueueNotification(new TypedNotification<>(key, scheduled, expiry,
                new NotificationTarget(List.of(targets)), "ping", new Ping(key), 0), false);
    }

    private static void enqueueDue(String key, UUID... targets) {
        enqueue(key, NOW.minus(1, ChronoUnit.MINUTES), null, targets);
    }

    private static List<String> stillDue(UUID player) {
        try (SqlSessionWrapper wrapper = database.openSession()) {
            return wrapper.notificationMapper().selectDueByPlayer(player, Instant.now()).stream()
                    .map(NotificationEntity::notifKey)
                    .toList();
        }
    }

    @Test
    @DisplayName("every target across chunk boundaries is delivered once and marked seen")
    void deliversAcrossChunks() {
        enqueueDue("n", A, B, C);

        BatchDeliveryReport report = this.delivery.deliver(List.of(A, B, C));

        Assertions.assertEquals(Set.of(A, B, C), Set.copyOf(this.sink.targets));
        Assertions.assertEquals(3, this.sink.targets.size());
        Assertions.assertEquals(new BatchDeliveryReport(3, Set.of()), report);
        Assertions.assertEquals(List.of(), stillDue(A));
        Assertions.assertEquals(List.of(), stillDue(C));
    }

    @Test
    @DisplayName("a player listed twice is delivered once")
    void duplicatesCollapse() {
        enqueueDue("n", A);

        BatchDeliveryReport report = this.delivery.deliver(List.of(A, A, A));

        Assertions.assertEquals(List.of(A), this.sink.targets);
        Assertions.assertEquals(1, report.attempted());
    }

    @Test
    @DisplayName("a target whose pass throws is reported failed and the others are still delivered")
    void failingTargetIsIsolated() {
        enqueueDue("n", A, B, C);
        this.preferences.muted = player -> {
            if (player.equals(B)) {
                throw new IllegalStateException("boom");
            }
            return false;
        };

        BatchDeliveryReport report = this.delivery.deliver(List.of(A, B, C));

        Assertions.assertEquals(Set.of(A, C), Set.copyOf(this.sink.targets));
        Assertions.assertEquals(new BatchDeliveryReport(2, Set.of(B)), report);
        Assertions.assertEquals(List.of("n"), stillDue(B));
    }

    @Test
    @DisplayName("a muted target is pushed nothing, stays due, and still counts as attempted")
    void mutedTargetSkipped() {
        enqueueDue("n", A, B);
        this.preferences.muted = A::equals;

        BatchDeliveryReport report = this.delivery.deliver(List.of(A, B));

        Assertions.assertEquals(List.of(B), this.sink.targets);
        Assertions.assertEquals(2, report.attempted());
        Assertions.assertEquals(List.of("n"), stillDue(A));
    }

    @Test
    @DisplayName("an unreachable target stays due; a reachable one in the same chunk is marked seen")
    void unreachableStaysDue() {
        enqueueDue("n", A, B);
        this.sink.unreachable = A::equals;

        this.delivery.deliver(List.of(A, B));

        Assertions.assertEquals(List.of("n"), stillDue(A));
        Assertions.assertEquals(List.of(), stillDue(B));
    }

    @Test
    @DisplayName("an already-seen notification is not pushed again")
    void seenNotResent() {
        enqueueDue("n", A);
        this.delivery.deliver(List.of(A));
        this.sink.targets.clear();

        this.delivery.deliver(List.of(A));

        Assertions.assertEquals(List.of(), this.sink.targets);
    }

    @Test
    @DisplayName("selectDueByPlayers tags each row with its player and excludes seen, expired and future rows")
    void mapperSelectsDueRowsPerPlayer() {
        enqueueDue("due-a", A);
        enqueueDue("due-ab", A, B);
        enqueue("expired", NOW.minus(2, ChronoUnit.HOURS), NOW.minus(1, ChronoUnit.HOURS), A);
        enqueue("future", NOW.plus(1, ChronoUnit.HOURS), null, A);
        enqueueDue("other", C);

        List<DueNotificationEntity> rows;
        try (SqlSessionWrapper wrapper = database.openSession()) {
            rows = wrapper.notificationMapper().selectDueByPlayers(List.of(A, B), NOW);
        }

        Map<UUID, Set<String>> byPlayer = rows.stream().collect(Collectors.groupingBy(
                DueNotificationEntity::playerUuid,
                Collectors.mapping(row -> row.notification().notifKey(), Collectors.toSet())));
        Assertions.assertEquals(Map.of(A, Set.of("due-a", "due-ab"), B, Set.of("due-ab")), byPlayer);
    }
}
