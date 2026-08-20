package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MailNotifier} against a real {@link NotificationSinkRegistry} holding recording fakes and a
 * lambda {@link NotificationPreferences}, mirroring how {@link io.github.md5sha256.playernotifications.api.render.RenderingProcessor}
 * is tested — no live server needed.
 */
class MailNotifierTest {

    private static final Logger LOGGER = Logger.getLogger(MailNotifierTest.class.getName());

    private static final class RecordingSink implements NotificationSink {
        private final String key;
        private final DeliveryResult result;
        final List<RenderableNotification> received = new ArrayList<>();

        RecordingSink(String key, DeliveryResult result) {
            this.key = key;
            this.result = result;
        }

        @Override
        public @org.jetbrains.annotations.NotNull String mediumKey() {
            return this.key;
        }

        @Override
        public @org.jetbrains.annotations.NotNull DeliveryResult deliver(
                @org.jetbrains.annotations.NotNull RenderableNotification notification,
                @org.jetbrains.annotations.NotNull UUID target) {
            this.received.add(notification);
            return this.result;
        }
    }

    /** A fixed set of preferred media, ignoring player and data type. */
    private static NotificationPreferences fixedMedia(Set<String> media) {
        return fixedMedia(media, false);
    }

    /** A fixed set of preferred media and a fixed global-mute answer, ignoring player and data type. */
    private static NotificationPreferences fixedMedia(Set<String> media, boolean muted) {
        return new NotificationPreferences() {
            @Override
            public @org.jetbrains.annotations.NotNull Set<String> preferredMedia(@org.jetbrains.annotations.NotNull UUID player) {
                return media;
            }

            @Override
            public @org.jetbrains.annotations.NotNull Set<String> preferredMedia(
                    @org.jetbrains.annotations.NotNull UUID player, @org.jetbrains.annotations.NotNull String dataType) {
                return media;
            }

            @Override
            public boolean isMuted(@org.jetbrains.annotations.NotNull UUID player) {
                return muted;
            }
        };
    }

    private static final class ThrowingSink implements NotificationSink {
        @Override
        public @org.jetbrains.annotations.NotNull String mediumKey() {
            return "throws";
        }

        @Override
        public @org.jetbrains.annotations.NotNull DeliveryResult deliver(
                @org.jetbrains.annotations.NotNull RenderableNotification notification,
                @org.jetbrains.annotations.NotNull UUID target) {
            throw new RuntimeException("boom");
        }
    }

    @Test
    void deliversToEveryPreferredMediumWithARegisteredSink() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        RecordingSink discord = new RecordingSink("discord-dm", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(discord);
        NotificationPreferences preferences = fixedMedia(Set.of("chat", "discord-dm"));

        MailNotifier notifier = new MailNotifier(sinks, preferences, LOGGER);
        notifier.notifyArrival(UUID.randomUUID());

        assertEquals(1, chat.received.size());
        assertEquals(1, discord.received.size());
    }

    @Test
    void mutedPlayerGetsNothing() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences =
                fixedMedia(Set.of(NotificationPreferences.SILENCED_MEDIUM));

        MailNotifier notifier = new MailNotifier(sinks, preferences, LOGGER);
        notifier.notifyArrival(UUID.randomUUID());

        assertTrue(chat.received.isEmpty());
    }

    @Test
    void mutedRecipientGetsNoNotice() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of("chat"), true);

        MailNotifier notifier = new MailNotifier(sinks, preferences, LOGGER);
        notifier.notifyArrival(UUID.randomUUID());

        assertTrue(chat.received.isEmpty());
    }

    @Test
    void preferredMediumWithNoRegisteredSinkIsSkippedWithoutThrowing() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        NotificationPreferences preferences = fixedMedia(Set.of("carrier-pigeon"));

        MailNotifier notifier = new MailNotifier(sinks, preferences, LOGGER);
        notifier.notifyArrival(UUID.randomUUID());
        // No exception is the assertion.
    }

    @Test
    void aThrowingSinkDoesNotPreventTheOtherSinkReceivingIt() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(new ThrowingSink());
        NotificationPreferences preferences = fixedMedia(Set.of("chat", "throws"));

        MailNotifier notifier = new MailNotifier(sinks, preferences, LOGGER);
        notifier.notifyArrival(UUID.randomUUID());

        assertEquals(1, chat.received.size());
    }

    @Test
    void noticeIsTheVerbatimTextWithNothingFromTheMail() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of("chat"));

        MailNotifier notifier = new MailNotifier(sinks, preferences, LOGGER);
        notifier.notifyArrival(UUID.randomUUID());

        assertEquals(1, chat.received.size());
        RenderableNotification notification = chat.received.get(0);
        String title = PlainTextComponentSerializer.plainText().serialize(notification.title());
        String body = PlainTextComponentSerializer.plainText().serialize(notification.body());
        assertEquals("You have new mail!", title);
        assertEquals("Use /mail to read it.", body);

        String all = title + " " + body;
        assertFalse(all.toLowerCase().contains("steve"));
        assertFalse(all.matches(".*\\d.*"));
    }
}
