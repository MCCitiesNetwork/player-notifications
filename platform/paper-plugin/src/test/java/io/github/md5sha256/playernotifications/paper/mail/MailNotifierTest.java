package io.github.md5sha256.playernotifications.paper.mail;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.localisation.TestMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

        MailNotifier notifier = new MailNotifier(sinks, preferences, TestMessages.shipped(), LOGGER);
        notifier.notifyArrival(UUID.randomUUID(), "Andrew", "Meet me at spawn");

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

        MailNotifier notifier = new MailNotifier(sinks, preferences, TestMessages.shipped(), LOGGER);
        notifier.notifyArrival(UUID.randomUUID(), "Andrew", "Meet me at spawn");

        assertTrue(chat.received.isEmpty());
    }

    @Test
    void mutedRecipientGetsNoNotice() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of("chat"), true);

        MailNotifier notifier = new MailNotifier(sinks, preferences, TestMessages.shipped(), LOGGER);
        notifier.notifyArrival(UUID.randomUUID(), "Andrew", "Meet me at spawn");

        assertTrue(chat.received.isEmpty());
    }

    @Test
    void preferredMediumWithNoRegisteredSinkIsSkippedWithoutThrowing() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        NotificationPreferences preferences = fixedMedia(Set.of("carrier-pigeon"));

        MailNotifier notifier = new MailNotifier(sinks, preferences, TestMessages.shipped(), LOGGER);
        notifier.notifyArrival(UUID.randomUUID(), "Andrew", "Meet me at spawn");
        // No exception is the assertion.
    }

    @Test
    void aThrowingSinkDoesNotPreventTheOtherSinkReceivingIt() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(new ThrowingSink());
        NotificationPreferences preferences = fixedMedia(Set.of("chat", "throws"));

        MailNotifier notifier = new MailNotifier(sinks, preferences, TestMessages.shipped(), LOGGER);
        notifier.notifyArrival(UUID.randomUUID(), "Andrew", "Meet me at spawn");

        assertEquals(1, chat.received.size());
    }

    @Test
    void theNoticeNamesItsSenderAndPreviewsTheMail() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of("chat"));

        MailNotifier notifier = new MailNotifier(sinks, preferences, TestMessages.shipped(), LOGGER);
        notifier.notifyArrival(UUID.randomUUID(), "Andrew", "Meet me at spawn");

        assertEquals(1, chat.received.size());
        RenderableNotification notification = chat.received.get(0);
        assertTrue(plain(notification.title()).contains("Andrew"));
        assertTrue(plain(notification.body()).contains("Meet me at spawn"));
    }

    @Test
    void aLongMessageIsPreviewedAndTruncated() {
        MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
                fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

        String message = "a".repeat(MailNotifier.PREVIEW_LENGTH + 50);
        String body = plain(notifier.arrivalNotice("Andrew", message).body());

        assertTrue(body.contains("a".repeat(MailNotifier.PREVIEW_LENGTH)), body);
        assertFalse(body.contains("a".repeat(MailNotifier.PREVIEW_LENGTH + 1)), body);
        assertTrue(body.endsWith("..."), "a truncated preview says so: " + body);
    }

    @Test
    void thePreviewIsPlainTextSoItsFormattingCannotReachTheNotice() {
        // The stored message is MiniMessage that passed the sender's permission gate, but the notice
        // is a one-line preview: it shows what was written, not how it was styled.
        MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
                fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

        String body = plain(notifier.arrivalNotice("Andrew", "<red>hello</red>").body());

        assertTrue(body.contains("hello"), body);
        assertFalse(body.contains("<red>"), body);
    }

    @Test
    void aLegacySectionCodeFallsBackToLiteralText() {
        // MiniMessage throws on a legacy section code, and mail stored before MailFormatting landed
        // can contain one. MailRenderer takes the same fallback.
        MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
                fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

        String body = plain(notifier.arrivalNotice("Andrew", "§chello").body());

        assertTrue(body.contains("hello"), body);
    }

    @Test
    void aNewlineInTheMessageIsFlattenedOntoOneLine() {
        MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
                fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

        String body = plain(notifier.arrivalNotice("Andrew", "one<newline>two").body());

        assertTrue(body.contains("one two"), body);
    }

    @Test
    void aSenderNameIsNeverParsedAsMarkup() {
        MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
                fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

        RenderableNotification notice = notifier.arrivalNotice("<red>Bob", "hello");

        assertTrue(plain(notice.title()).contains("<red>Bob"));
    }

    @Test
    void everyNoticeCarriesTheSameMarker() {
        // The Discord "Read mail" button recognises the notice by the identity of the marker leading
        // its body, so two notices differing in sender and message must still be recognised.
        MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
                fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

        RenderableNotification first = notifier.arrivalNotice("Andrew", "one");
        RenderableNotification second = notifier.arrivalNotice("Bob", "two");

        assertNotEquals(first.title(), second.title());
        assertNotEquals(first.body(), second.body());
        assertTrue(MailNotifier.isArrivalNotice(first));
        assertTrue(MailNotifier.isArrivalNotice(second));
    }

    @Test
    void onlyAnArrivalNoticeIsRecognised() {
        MailNotifier notifier = new MailNotifier(new NotificationSinkRegistry(),
                fixedMedia(Set.of()), TestMessages.shipped(), LOGGER);

        assertTrue(MailNotifier.isArrivalNotice(notifier.arrivalNotice("Andrew", "hello")));
        // Equal-looking, but built elsewhere: not the notice.
        assertFalse(MailNotifier.isArrivalNotice(new RenderableNotification(
                Component.text("Andrew has sent you mail!"),
                Component.text("hello"))));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
