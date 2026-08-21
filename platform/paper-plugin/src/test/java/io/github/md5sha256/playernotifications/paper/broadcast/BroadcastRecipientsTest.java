package io.github.md5sha256.playernotifications.paper.broadcast;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BroadcastRecipients#select} against hand-built candidates, so the OR rule is testable without
 * a live server — the same reason the {@code Predicate<String>} seam exists.
 */
class BroadcastRecipientsTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    @Test
    void emptyPermissionListReturnsEveryCandidateInIterationOrder() {
        List<BroadcastRecipients.Candidate> candidates = List.of(
                new BroadcastRecipients.Candidate(ALICE, permission -> false),
                new BroadcastRecipients.Candidate(BOB, permission -> false),
                new BroadcastRecipients.Candidate(CAROL, permission -> false));

        List<UUID> result = BroadcastRecipients.select(candidates, List.of());

        assertEquals(List.of(ALICE, BOB, CAROL), result);
    }

    @Test
    void candidateHoldingOnlyOneListedPermissionIsSelected() {
        BroadcastRecipients.Candidate holdsB =
                new BroadcastRecipients.Candidate(ALICE, "b"::equals);

        List<UUID> result = BroadcastRecipients.select(List.of(holdsB), List.of("a", "b"));

        assertEquals(List.of(ALICE), result);
    }

    @Test
    void candidateHoldingNeitherListedPermissionIsNotSelected() {
        BroadcastRecipients.Candidate holdsNeither =
                new BroadcastRecipients.Candidate(ALICE, permission -> false);

        List<UUID> result = BroadcastRecipients.select(List.of(holdsNeither), List.of("a", "b"));

        assertTrue(result.isEmpty());
    }

    @Test
    void candidateHoldingBothListedPermissionsAppearsExactlyOnce() {
        BroadcastRecipients.Candidate holdsBoth =
                new BroadcastRecipients.Candidate(ALICE, permission -> true);

        List<UUID> result = BroadcastRecipients.select(List.of(holdsBoth), List.of("a", "b"));

        assertEquals(List.of(ALICE), result);
    }

    @Test
    void emptyCandidateCollectionReturnsEmptyList() {
        List<UUID> result = BroadcastRecipients.select(List.of(), List.of("a"));

        assertTrue(result.isEmpty());
    }
}
