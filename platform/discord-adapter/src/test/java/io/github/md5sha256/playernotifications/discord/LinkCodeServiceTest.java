package io.github.md5sha256.playernotifications.discord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class LinkCodeServiceTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    private MutableClock clock;
    private LinkCodeService service;

    @BeforeEach
    void setUp() {
        this.clock = MutableClock.atEpoch();
        this.service = new LinkCodeService(TTL, this.clock);
    }

    @Test
    @DisplayName("an issued code uses only unambiguous characters")
    void issuesACodeFromTheRestrictedAlphabet() {
        String code = this.service.issue(UUID.randomUUID());

        Assertions.assertEquals(LinkCodeService.CODE_LENGTH, code.length());
        for (char character : code.toCharArray()) {
            // The player reads this off a chat line and retypes it in Discord, so I/O/0/1 are excluded.
            Assertions.assertTrue(LinkCodeService.ALPHABET.indexOf(character) >= 0,
                    "unexpected character '" + character + "' in " + code);
        }
    }

    @Test
    @DisplayName("a code redeems exactly once")
    void redeemsOnceAndConsumes() {
        UUID player = UUID.randomUUID();
        String code = this.service.issue(player);

        Assertions.assertEquals(Optional.of(player), this.service.redeem(code));
        Assertions.assertEquals(Optional.empty(), this.service.redeem(code),
                "a code overheard after use must be worthless");
        Assertions.assertFalse(this.service.hasOutstandingCode(player));
    }

    @Test
    @DisplayName("redeeming tolerates the case and whitespace a player actually types")
    void isForgivingAboutInputFormatting() {
        UUID player = UUID.randomUUID();
        String code = this.service.issue(player);

        Assertions.assertEquals(Optional.of(player),
                this.service.redeem("  " + code.toLowerCase(Locale.ROOT) + " "));
    }

    @Test
    @DisplayName("an unknown code is rejected")
    void rejectsAnUnknownCode() {
        Assertions.assertEquals(Optional.empty(), this.service.redeem("ZZZZZZ"));
        Assertions.assertEquals(Optional.empty(), this.service.redeem(""));
    }

    @Test
    @DisplayName("a code past its TTL is rejected and forgotten")
    void rejectsAnExpiredCode() {
        UUID player = UUID.randomUUID();
        String code = this.service.issue(player);

        this.clock.advance(TTL.plusSeconds(1));

        Assertions.assertEquals(Optional.empty(), this.service.redeem(code));
        Assertions.assertFalse(this.service.hasOutstandingCode(player));
    }

    @Test
    @DisplayName("a code is still valid immediately before it expires")
    void acceptsACodeOnTheBoundary() {
        UUID player = UUID.randomUUID();
        String code = this.service.issue(player);

        this.clock.advance(TTL.minusSeconds(1));

        Assertions.assertEquals(Optional.of(player), this.service.redeem(code));
    }

    @Test
    @DisplayName("re-issuing replaces the player's outstanding code")
    void reissuingInvalidatesThePreviousCode() {
        UUID player = UUID.randomUUID();
        String first = this.service.issue(player);
        String second = this.service.issue(player);

        Assertions.assertNotEquals(first, second);
        // A player may only ever have one live code, so a stale one cannot be redeemed later.
        Assertions.assertEquals(Optional.empty(), this.service.redeem(first));
        Assertions.assertEquals(Optional.of(player), this.service.redeem(second));
    }

    @Test
    @DisplayName("cancel invalidates an outstanding code")
    void cancelInvalidatesAnOutstandingCode() {
        UUID player = UUID.randomUUID();
        String code = this.service.issue(player);

        this.service.cancel(player);

        Assertions.assertFalse(this.service.hasOutstandingCode(player));
        Assertions.assertEquals(Optional.empty(), this.service.redeem(code));
    }

    @Test
    @DisplayName("cancel on a player with no code is a no-op")
    void cancelWithoutACodeIsHarmless() {
        Assertions.assertDoesNotThrow(() -> this.service.cancel(UUID.randomUUID()));
    }

    @Test
    @DisplayName("hasOutstandingCode tracks issuance and expiry")
    void reportsWhetherACodeIsOutstanding() {
        UUID player = UUID.randomUUID();
        Assertions.assertFalse(this.service.hasOutstandingCode(player));

        this.service.issue(player);
        Assertions.assertTrue(this.service.hasOutstandingCode(player));

        this.clock.advance(TTL.plusSeconds(1));
        Assertions.assertFalse(this.service.hasOutstandingCode(player),
                "an expired code must not be reported as outstanding");
    }

    @Test
    @DisplayName("codes issued to different players are distinct and independent")
    void codesAreDistinctPerPlayer() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            codes.add(this.service.issue(UUID.randomUUID()));
        }

        Assertions.assertEquals(50, codes.size(), "issued codes collided");
    }

    @Test
    @DisplayName("the configured TTL is reported for player-facing messages")
    void exposesItsTtl() {
        Assertions.assertEquals(TTL, this.service.ttl());
    }
}
