package io.github.md5sha256.playernotifications.core.database;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Covers the design's central claim: a payload whose data type is bound to a RETAIN processor is
 * excluded from delivery entirely — no sink ever sees it, and it stays unread in the inbox rather than
 * being marked seen the way a normal delivered notification would be.
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-08-10-first-party-mail.md}, Task 3.
 */
class MailNotDeliveredTest extends AbstractDatabaseTest {

    private record Mail(String message) {
    }

    private record Ping(String message) {
    }

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DUE = NOW.minus(1, ChronoUnit.MINUTES);

    private static final class RecordingSink implements NotificationSink {

        private final List<RenderableNotification> received = new ArrayList<>();

        @Override
        public @NotNull String mediumKey() {
            return "recording";
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            this.received.add(notification);
            return DeliveryResult.DELIVERED;
        }
    }

    private static void registerMail() {
        service.registerJsonRenderable("mail", Mail.class, (payload, target) ->
                new RenderableNotification(Component.text("Mail"), Component.text(payload.message())));
        // The rule under test: an explicit RETAIN processor wins dispatch precedence over the renderer
        // path, so the notification is never rendered for delivery and seenTime is never stamped.
        service.dataTypeRegistry().registerProcessor(Mail.class,
                (payload, target) -> NotificationDisposition.RETAIN);
    }

    private static void registerPing() {
        service.registerJsonRenderable("ping", Ping.class, (payload, target) ->
                new RenderableNotification(Component.text("Ping"), Component.text(payload.message())));
    }

    private static void enqueueMail(String key, String message) {
        service.enqueueNotification(new TypedNotification<>(
                key, DUE, null, new NotificationTarget(List.of(PLAYER)), "mail",
                new Mail(message), 0), false);
    }

    private static void enqueuePing(String key, String message) {
        service.enqueueNotification(new TypedNotification<>(
                key, DUE, null, new NotificationTarget(List.of(PLAYER)), "ping",
                new Ping(message), 0), false);
    }

    private static NotificationDelivery delivery(NotificationSinkRegistry sinks) {
        NotificationPreferences preferences = player -> Set.of("recording");
        return new NotificationDelivery(database, service.dataTypeRegistry(), sinks, preferences,
                Logger.getLogger("test"));
    }

    @Test
    @DisplayName("mail is never delivered: the sink only receives the ordinary notification")
    void mailIsExcludedFromDelivery() {
        registerMail();
        registerPing();
        RecordingSink sink = new RecordingSink();
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(sink);

        enqueueMail("m1", "hello there");
        enqueuePing("p1", "a real notification");

        delivery(sinks).deliver(PLAYER, NOW);

        // 1. The recording sink got only the non-mail notification.
        Assertions.assertEquals(1, sink.received.size());
        Assertions.assertEquals("a real notification",
                PlainTextComponentSerializer.plainText().serialize(sink.received.get(0).body()));

        // 2. The mail's seenTime is still null.
        InboxEntry mailEntry = service.inbox(PLAYER, 1, 10, "mail").entries().stream()
                .filter(entry -> entry.notifKey().equals("m1"))
                .findFirst()
                .orElseThrow();
        Assertions.assertNull(mailEntry.seenTime());

        // 3. The mail is still in the filtered inbox as unread.
        var mailPage = service.inbox(PLAYER, 1, 10, "mail");
        Assertions.assertEquals(1, mailPage.entries().size());
        Assertions.assertTrue(mailPage.entries().get(0).unread());
    }
}
