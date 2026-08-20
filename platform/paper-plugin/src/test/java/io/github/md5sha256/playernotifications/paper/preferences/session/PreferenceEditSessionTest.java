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

    private static PreferenceEditSession newSession(Map<String, Set<String>> initial) {
        return new PreferenceEditSession(PLAYER, initial, false, NOW);
    }

    private static PreferenceEditSession newSession(Map<String, Set<String>> initial, boolean initiallyMuted) {
        return new PreferenceEditSession(PLAYER, initial, initiallyMuted, NOW);
    }

    @Test
    void startsClean() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")));

        Assertions.assertFalse(session.isDirty());
        Assertions.assertEquals(0, session.dirtyCount());
        Assertions.assertEquals(Set.of("chat"), session.mediaFor("economy"));
    }

    @Test
    void setDataTypeMediaMarksItDirty() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")));

        session.setDataTypeMedia("economy", Set.of("discord"), NOW);

        Assertions.assertTrue(session.isDirty());
        Assertions.assertEquals(Set.of("economy"), session.dirtyDataTypes());
        Assertions.assertEquals(Set.of("discord"), session.mediaFor("economy"));
    }

    @Test
    void toggleDataTypeMediumAddsAndRemoves() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")));

        session.toggleDataTypeMedium("economy", "discord", true, NOW);
        Assertions.assertEquals(Set.of("chat", "discord"), session.mediaFor("economy"));

        session.toggleDataTypeMedium("economy", "chat", false, NOW);
        Assertions.assertEquals(Set.of("discord"), session.mediaFor("economy"));
    }

    @Test
    void emptyingADataTypeSilencesIt() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")));

        session.setDataTypeMedia("economy", Set.of(), NOW);

        Assertions.assertEquals(Map.of("economy", Set.of("none")), session.explicitChanges());
    }

    @Test
    void everyDirtyDataTypeBecomesAnExplicitChange() {
        PreferenceEditSession session = newSession(
                Map.of("economy", Set.of("discord"), "moderation", Set.of("chat")));

        session.setDataTypeMedia("economy", Set.of("dialog"), NOW);
        session.toggleDataTypeMedium("moderation", "chat", false, NOW);

        Assertions.assertEquals(
                Map.of("economy", Set.of("dialog"), "moderation", Set.of("none")),
                session.explicitChanges());
    }

    @Test
    void expiresAfterTheIdleTimeout() {
        PreferenceEditSession session = newSession(Map.of());

        Assertions.assertFalse(session.isExpired(NOW.plus(Duration.ofMinutes(5)), Duration.ofMinutes(15)));
        Assertions.assertTrue(session.isExpired(NOW.plus(Duration.ofMinutes(16)), Duration.ofMinutes(15)));
    }

    @Test
    void touchingResetsTheIdleClock() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")));

        Instant later = NOW.plus(Duration.ofMinutes(10));
        session.setDataTypeMedia("economy", Set.of("discord"), later);

        Assertions.assertFalse(session.isExpired(later.plus(Duration.ofMinutes(10)), Duration.ofMinutes(15)));
    }

    @Test
    void mutedSeedsFromTheConstructor() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")), true);

        Assertions.assertTrue(session.muted());
        Assertions.assertNull(session.stagedMuteChange());
        Assertions.assertFalse(session.isDirty());
    }

    @Test
    void stagingAMuteMarksTheSessionDirty() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")), false);

        session.setMuted(true, NOW);

        Assertions.assertTrue(session.muted());
        Assertions.assertEquals(Boolean.TRUE, session.stagedMuteChange());
        Assertions.assertTrue(session.isDirty());
        Assertions.assertEquals(1, session.dirtyCount());
    }

    @Test
    void stagingAMuteLeavesTheMatrixAlone() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")), false);

        session.setMuted(true, NOW);

        Assertions.assertEquals(Map.of(), session.explicitChanges());
        Assertions.assertEquals(Set.of("chat"), session.mediaFor("economy"));
    }

    @Test
    void stagingAnUnmuteIsStagedToo() {
        PreferenceEditSession session = newSession(Map.of("economy", Set.of("chat")), true);

        session.setMuted(false, NOW);

        Assertions.assertEquals(Boolean.FALSE, session.stagedMuteChange());
    }
}
