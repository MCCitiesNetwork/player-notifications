package io.github.md5sha256.playernotifications.paper.preferences.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class PreferenceSessionManagerTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static PreferenceEditSession freshSession() {
        return new PreferenceEditSession(PLAYER, Map.of("economy", Set.of("chat")), Instant.now());
    }

    @Test
    void getOrCreateBuildsOnceThenReusesTheSameInstance() {
        PreferenceSessionManager manager = new PreferenceSessionManager();

        PreferenceEditSession first = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);
        PreferenceEditSession second = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        Assertions.assertSame(first, second);
    }

    @Test
    void getReturnsEmptyWhenNoSessionExists() {
        PreferenceSessionManager manager = new PreferenceSessionManager();

        Assertions.assertTrue(manager.get(PLAYER).isEmpty());
    }

    @Test
    void getReturnsTheStoredSession() {
        PreferenceSessionManager manager = new PreferenceSessionManager();
        PreferenceEditSession created = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        Optional<PreferenceEditSession> found = manager.get(PLAYER);

        Assertions.assertTrue(found.isPresent());
        Assertions.assertSame(created, found.get());
    }

    @Test
    void dropRemovesTheSession() {
        PreferenceSessionManager manager = new PreferenceSessionManager();
        manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        manager.drop(PLAYER);

        Assertions.assertTrue(manager.get(PLAYER).isEmpty());
    }

    @Test
    void getOrCreateRebuildsAnExpiredSession() {
        PreferenceSessionManager manager = new PreferenceSessionManager();
        Instant longAgo = Instant.now().minus(PreferenceSessionManager.IDLE_TIMEOUT).minusSeconds(60);
        PreferenceEditSession expired = new PreferenceEditSession(PLAYER, Map.of("economy", Set.of("chat")),
                longAgo);
        manager.getOrCreate(PLAYER, () -> expired);

        PreferenceEditSession rebuilt = manager.getOrCreate(PLAYER, PreferenceSessionManagerTest::freshSession);

        Assertions.assertNotSame(expired, rebuilt);
    }
}
