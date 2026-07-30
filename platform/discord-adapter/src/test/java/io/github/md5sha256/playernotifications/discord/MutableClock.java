package io.github.md5sha256.playernotifications.discord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} whose instant is advanced by hand, so expiry can be tested without sleeping.
 */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    static MutableClock atEpoch() {
        return new MutableClock(Instant.parse("2026-07-30T12:00:00Z"));
    }

    void advance(Duration amount) {
        this.now = this.now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return this.now;
    }
}
