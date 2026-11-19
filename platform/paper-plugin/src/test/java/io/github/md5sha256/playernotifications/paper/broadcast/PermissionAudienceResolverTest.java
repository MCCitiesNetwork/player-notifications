package io.github.md5sha256.playernotifications.paper.broadcast;

import io.github.md5sha256.playernotifications.api.NotificationAudience;
import io.github.md5sha256.playernotifications.api.OfflineLookupUnavailableException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class PermissionAudienceResolverTest {

    private static final UUID ONLINE = UUID.randomUUID();
    private static final UUID OFFLINE = UUID.randomUUID();

    /** A fake audience recording each call's permissions and chain. */
    private static final class RecordingAudience implements BroadcastAudience {

        private final List<UUID> answer;
        private final List<BroadcastArguments.Chain> chains = new ArrayList<>();
        private final List<List<String>> permissions = new ArrayList<>();

        RecordingAudience(List<UUID> answer) {
            this.answer = answer;
        }

        @Override
        public List<UUID> resolve(List<String> permissions, BroadcastArguments.Chain chain) {
            this.permissions.add(permissions);
            this.chains.add(chain);
            return this.answer;
        }
    }

    @Test
    @DisplayName("withAnyPermission reaches the audience as an OR chain")
    void orMapsToOr() {
        RecordingAudience online = new RecordingAudience(List.of(ONLINE));

        new PermissionAudienceResolver(online, null).resolve(
                (NotificationAudience.WithPermissions) NotificationAudience.withAnyPermission(false, "a"));

        Assertions.assertEquals(List.of(BroadcastArguments.Chain.OR), online.chains);
        Assertions.assertEquals(List.of(List.of("a")), online.permissions);
    }

    @Test
    @DisplayName("withAllPermissions reaches the audience as an AND chain")
    void andMapsToAnd() {
        RecordingAudience online = new RecordingAudience(List.of(ONLINE));

        new PermissionAudienceResolver(online, null).resolve(
                (NotificationAudience.WithPermissions) NotificationAudience.withAllPermissions(false, "a", "b"));

        Assertions.assertEquals(List.of(BroadcastArguments.Chain.AND), online.chains);
    }

    @Test
    @DisplayName("an online-only audience never asks the offline lookup")
    void onlineOnlySkipsOffline() {
        RecordingAudience online = new RecordingAudience(List.of(ONLINE));
        RecordingAudience offline = new RecordingAudience(List.of(OFFLINE));

        Set<UUID> result = new PermissionAudienceResolver(online, offline).resolve(
                (NotificationAudience.WithPermissions) NotificationAudience.withAnyPermission(false, "a"));

        Assertions.assertEquals(Set.of(ONLINE), result);
        Assertions.assertEquals(List.of(), offline.chains);
    }

    @Test
    @DisplayName("includeOffline returns online and offline holders together")
    void includeOfflineUnions() {
        RecordingAudience online = new RecordingAudience(List.of(ONLINE));
        RecordingAudience offline = new RecordingAudience(List.of(OFFLINE));

        Set<UUID> result = new PermissionAudienceResolver(online, offline).resolve(
                (NotificationAudience.WithPermissions) NotificationAudience.withAnyPermission(true, "a"));

        Assertions.assertEquals(Set.of(ONLINE, OFFLINE), result);
    }

    @Test
    @DisplayName("includeOffline with no offline lookup available throws OfflineLookupUnavailableException")
    void includeOfflineWithoutLookupThrows() {
        RecordingAudience online = new RecordingAudience(List.of(ONLINE));
        PermissionAudienceResolver resolver = new PermissionAudienceResolver(online, null);
        NotificationAudience.WithPermissions audience =
                (NotificationAudience.WithPermissions) NotificationAudience.withAnyPermission(true, "a");

        Assertions.assertThrows(OfflineLookupUnavailableException.class, () -> resolver.resolve(audience));
    }
}
