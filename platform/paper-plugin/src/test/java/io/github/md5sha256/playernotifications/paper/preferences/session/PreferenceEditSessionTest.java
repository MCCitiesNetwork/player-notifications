package io.github.md5sha256.playernotifications.paper.preferences.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class PreferenceEditSessionTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private static PreferenceEditSession newSession(Map<String, Set<String>> initial, Set<String> explicitAtLoad) {
        return new PreferenceEditSession(PLAYER, initial, explicitAtLoad, Set.of("chat"), NOW);
    }

    @Test
    void startsClean() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        Assertions.assertFalse(session.isDirty());
        Assertions.assertEquals(0, session.dirtyCount());
        Assertions.assertEquals(Set.of("chat"), session.mediaFor("economy"));
    }

    @Test
    void setCategoryMediaMarksItDirty() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        session.setCategoryMedia("economy", Set.of("discord"), NOW);

        Assertions.assertTrue(session.isDirty());
        Assertions.assertEquals(Set.of("economy"), session.dirtyCategories());
        Assertions.assertEquals(Set.of("discord"), session.mediaFor("economy"));
    }

    @Test
    void toggleCategoryMediumAddsAndRemoves() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        session.toggleCategoryMedium("economy", "discord", true, NOW);
        Assertions.assertEquals(Set.of("chat", "discord"), session.mediaFor("economy"));

        session.toggleCategoryMedium("economy", "chat", false, NOW);
        Assertions.assertEquals(Set.of("discord"), session.mediaFor("economy"));
    }

    @Test
    void emptyingACategoryStagesAMute() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("chat")), Set.of("economy"));

        session.setCategoryMedia("economy", Set.of(), NOW);

        Assertions.assertEquals(Map.of("economy", Set.of("none")), session.explicitChanges());
        Assertions.assertEquals(Set.of(), session.categoriesToReset());
    }

    @Test
    void resetCategoryStagesAResetUsingFallbackMedia() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("discord")), Set.of("economy"));

        session.resetCategory("economy", NOW);

        Assertions.assertEquals(Set.of("chat"), session.mediaFor("economy"));
        Assertions.assertEquals(Set.of("economy"), session.categoriesToReset());
        Assertions.assertTrue(session.explicitChanges().isEmpty());
    }

    @Test
    void reSettingMediaAfterAResetCancelsTheReset() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("discord")), Set.of("economy"));

        session.resetCategory("economy", NOW);
        session.setCategoryMedia("economy", Set.of("dialog"), NOW);

        Assertions.assertEquals(Set.of(), session.categoriesToReset());
        Assertions.assertEquals(Map.of("economy", Set.of("dialog")), session.explicitChanges());
    }

    @Test
    void isUsingServerDefaultReflectsLoadStateAndStagedResets() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("discord"), "moderation", Set.of("chat")),
                Set.of("economy"));

        Assertions.assertFalse(session.isUsingServerDefault("economy"));
        Assertions.assertTrue(session.isUsingServerDefault("moderation"));

        session.resetCategory("economy", NOW);
        Assertions.assertTrue(session.isUsingServerDefault("economy"));

        session.setCategoryMedia("moderation", Set.of("discord"), NOW);
        Assertions.assertFalse(session.isUsingServerDefault("moderation"));
    }

    @Test
    void expiresAfterTheIdleTimeout() {
        PreferenceEditSession session = newSession(Map.of(), Set.of());

        Assertions.assertFalse(session.isExpired(NOW.plus(Duration.ofMinutes(5)), Duration.ofMinutes(15)));
        Assertions.assertTrue(session.isExpired(NOW.plus(Duration.ofMinutes(16)), Duration.ofMinutes(15)));
    }

    @Test
    void touchingResetsTheIdleClock() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")), Set.of("economy"));

        Instant later = NOW.plus(Duration.ofMinutes(10));
        session.setCategoryMedia("economy", Set.of("discord"), later);

        Assertions.assertFalse(session.isExpired(later.plus(Duration.ofMinutes(10)), Duration.ofMinutes(15)));
    }
}
