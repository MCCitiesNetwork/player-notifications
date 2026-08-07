package io.github.md5sha256.playernotifications.paper;

import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the gate and the reload swap. {@code PlayerJoinEvent} and the Bukkit scheduler need a live
 * server, so {@code onJoin} itself is verified by hand with {@code runServer} — everything reachable
 * without a server is tested here, which is why the gate decision lives in {@code deliver} rather
 * than inline in the event handler.
 *
 * <p>The {@code Plugin} argument is {@code null} throughout: it is only dereferenced by {@code onJoin}
 * (to reach the scheduler) and by the failure branch of {@code deliver} (to reach the logger), and no
 * test here takes either path. It is not a claim that {@code null} is a legal argument.
 */
class JoinDeliveryListenerTest {

    private final AtomicInteger supplierCalls = new AtomicInteger();
    private final Supplier<NotificationDelivery> delivery = () -> {
        this.supplierCalls.incrementAndGet();
        return null;
    };

    @Test
    void reportsConstructedSettings() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 3L);
        assertTrue(listener.enabled());
        assertEquals(3L, listener.delaySeconds());
    }

    @Test
    void reloadSettingsReplacesBoth() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 3L);
        listener.reloadSettings(false, 30L);
        assertFalse(listener.enabled());
        assertEquals(30L, listener.delaySeconds());
    }

    @Test
    void reloadSettingsClampsNegativeDelay() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 3L);
        listener.reloadSettings(true, -5L);
        assertEquals(0L, listener.delaySeconds());
    }

    @Test
    void deliverDoesNothingWhenDisabled() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, false, 0L);
        listener.deliver(UUID.randomUUID());
        assertEquals(0, this.supplierCalls.get());
    }

    @Test
    void deliverIsSkippedAfterAReloadTurnsItOff() {
        JoinDeliveryListener listener = new JoinDeliveryListener(null, this.delivery, null, true, 30L);
        listener.reloadSettings(false, 30L);
        listener.deliver(UUID.randomUUID());
        assertEquals(0, this.supplierCalls.get());
    }
}
