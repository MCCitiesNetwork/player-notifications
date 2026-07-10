package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

class DefaultNotificationServiceTest extends AbstractDatabaseTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();
    private static final UUID PLAYER_C = UUID.randomUUID();

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static ResolvedNotification notification(String key, int priority, List<UUID> targets) {
        return new ResolvedNotification(key, NOW, null, new NotificationTarget(targets), "{}", priority);
    }

    @Test
    @DisplayName("enqueue then resolveAndClear returns the notification with its full target list")
    void enqueueAndResolve() {
        service.enqueueNotification(notification("welcome", 0, List.of(PLAYER_A, PLAYER_B)), false);

        List<ResolvedNotification> resolved = service.resolveAndClearNotifications(PLAYER_A);
        Assertions.assertEquals(1, resolved.size());
        ResolvedNotification n = resolved.get(0);
        Assertions.assertEquals("welcome", n.notifKey());
        Assertions.assertEquals(2, n.notifTarget().playerUUIDs().size());
        Assertions.assertTrue(n.notifTarget().playerUUIDs().containsAll(List.of(PLAYER_A, PLAYER_B)));
    }

    @Test
    @DisplayName("resolveAndClear deletes the resolved notifications")
    void resolveClears() {
        service.enqueueNotification(notification("once", 0, List.of(PLAYER_A)), false);

        Assertions.assertEquals(1, service.resolveAndClearNotifications(PLAYER_A).size());
        Assertions.assertTrue(service.resolveAndClearNotifications(PLAYER_A).isEmpty());
    }

    @Test
    @DisplayName("resolveAndClear returns notifications ordered by descending priority")
    void resolveOrdered() {
        service.enqueueNotification(notification("low", 1, List.of(PLAYER_A)), false);
        service.enqueueNotification(notification("high", 9, List.of(PLAYER_A)), false);

        List<ResolvedNotification> resolved = service.resolveAndClearNotifications(PLAYER_A);
        Assertions.assertEquals(List.of("high", "low"),
                resolved.stream().map(ResolvedNotification::notifKey).toList());
    }

    @Test
    @DisplayName("enqueue with overwrite replaces an existing key")
    void enqueueOverwrite() {
        service.enqueueNotification(notification("dup", 0, List.of(PLAYER_A)), false);
        service.enqueueNotification(notification("dup", 0, List.of(PLAYER_B)), true);

        // The overwrite retargeted the notification from A to B.
        Assertions.assertTrue(service.resolveAndClearNotifications(PLAYER_A).isEmpty());
        List<ResolvedNotification> forB = service.resolveAndClearNotifications(PLAYER_B);
        Assertions.assertEquals(1, forB.size());
        Assertions.assertEquals("dup", forB.get(0).notifKey());
    }

    @Test
    @DisplayName("clearNotification removes a single notification by key")
    void clearByKey() {
        service.enqueueNotification(notification("gone", 0, List.of(PLAYER_A)), false);
        service.clearNotification("gone");
        Assertions.assertTrue(service.resolveAndClearNotifications(PLAYER_A).isEmpty());
    }

    @Test
    @DisplayName("clearNotifications(player) removes every notification for that player")
    void clearByPlayer() {
        service.enqueueNotification(notification("a1", 0, List.of(PLAYER_A)), false);
        service.enqueueNotification(notification("a2", 0, List.of(PLAYER_A, PLAYER_C)), false);
        service.enqueueNotification(notification("c1", 0, List.of(PLAYER_C)), false);

        service.clearNotifications(PLAYER_A);
        Assertions.assertTrue(service.resolveAndClearNotifications(PLAYER_A).isEmpty());
        Assertions.assertEquals(1, service.resolveAndClearNotifications(PLAYER_C).size());
    }

    @Test
    @DisplayName("clearExpiredNotifications removes only expired notifications")
    void clearExpired() {
        ResolvedNotification expired = new ResolvedNotification(
                "expired", NOW, NOW.minus(1, ChronoUnit.HOURS), new NotificationTarget(List.of(PLAYER_A)), "{}", 0);
        ResolvedNotification live = new ResolvedNotification(
                "live", NOW, NOW.plus(1, ChronoUnit.HOURS), new NotificationTarget(List.of(PLAYER_A)), "{}", 0);
        service.enqueueNotification(expired, false);
        service.enqueueNotification(live, false);

        service.clearExpiredNotifications();

        List<ResolvedNotification> remaining = service.resolveAndClearNotifications(PLAYER_A);
        Assertions.assertEquals(1, remaining.size());
        Assertions.assertEquals("live", remaining.get(0).notifKey());
    }
}
