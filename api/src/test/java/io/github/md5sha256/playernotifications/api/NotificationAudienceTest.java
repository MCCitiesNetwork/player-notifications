package io.github.md5sha256.playernotifications.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class NotificationAudienceTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    @DisplayName("players() copies its input, so later changes to the caller's list do not leak in")
    void playersFactoryCopies() {
        List<UUID> source = new ArrayList<>(List.of(A));
        NotificationAudience audience = NotificationAudience.players(source);
        source.add(B);
        Assertions.assertEquals(new NotificationAudience.Players(Set.of(A)), audience);
    }

    @Test
    @DisplayName("withAnyPermission chains with OR")
    void anyIsOr() {
        Assertions.assertEquals(
                new NotificationAudience.WithPermissions(List.of("x.y"), PermissionChain.OR, true),
                NotificationAudience.withAnyPermission(true, "x.y"));
    }

    @Test
    @DisplayName("withAllPermissions chains with AND")
    void allIsAnd() {
        Assertions.assertEquals(
                new NotificationAudience.WithPermissions(List.of("a", "b"), PermissionChain.AND, false),
                NotificationAudience.withAllPermissions(false, "a", "b"));
    }

    @Test
    @DisplayName("an empty permission list is rejected at construction")
    void emptyPermissionsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NotificationAudience.withAnyPermission(false));
    }

    @Test
    @DisplayName("a blank permission is rejected at construction")
    void blankPermissionRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NotificationAudience.withAnyPermission(false, " "));
    }
}
