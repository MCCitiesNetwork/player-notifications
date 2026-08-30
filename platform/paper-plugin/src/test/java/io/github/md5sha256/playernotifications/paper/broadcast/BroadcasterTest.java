package io.github.md5sha256.playernotifications.paper.broadcast;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.localisation.TestMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Broadcaster} against a real {@link NotificationSinkRegistry} holding recording fakes and a
 * lambda {@link NotificationPreferences}, reusing the same fixture shapes as
 * {@code paper.mail.MailNotifierTest} — no live server needed.
 */
class BroadcasterTest {

    private static final Logger LOGGER = Logger.getLogger(BroadcasterTest.class.getName());

    private static final class RecordingSink implements NotificationSink {
        private final String key;
        private final DeliveryResult result;
        final List<RenderableNotification> received = new ArrayList<>();

        RecordingSink(String key, DeliveryResult result) {
            this.key = key;
            this.result = result;
        }

        @Override
        public @NotNull String mediumKey() {
            return this.key;
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                                @NotNull UUID target) {
            this.received.add(notification);
            return this.result;
        }
    }

    private static final class ThrowingSink implements NotificationSink {
        @Override
        public @NotNull String mediumKey() {
            return "throws";
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                                @NotNull UUID target) {
            throw new RuntimeException("boom");
        }
    }

    /** A fixed set of preferred media and a fixed global-mute answer, ignoring player and data type. */
    private static NotificationPreferences fixedMedia(Set<String> media, boolean muted) {
        return new NotificationPreferences() {
            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
                return media;
            }

            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType) {
                return media;
            }

            @Override
            public boolean isMuted(@NotNull UUID player) {
                return muted;
            }
        };
    }

    private static NotificationPreferences fixedMedia(Set<String> media) {
        return fixedMedia(media, false);
    }

    // ----- without bypass -----

    @Test
    void deliversToEveryPreferredMediumWithARegisteredSink() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        RecordingSink discord = new RecordingSink("discord-dm", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(discord);
        NotificationPreferences preferences = fixedMedia(Set.of("chat", "discord-dm"));
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);
        Component content = Component.text("hello");

        int attempted = broadcaster.broadcast(content, List.of(UUID.randomUUID()), false);

        assertEquals(1, attempted);
        assertEquals(1, chat.received.size());
        assertEquals(1, discord.received.size());
        assertEquals("Broadcast",
                PlainTextComponentSerializer.plainText().serialize(chat.received.get(0).title()));
        assertEquals("hello",
                PlainTextComponentSerializer.plainText().serialize(chat.received.get(0).body()));
    }

    @Test
    void silencedRecipientGetsNothingAndIsNotCounted() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of(NotificationPreferences.SILENCED_MEDIUM));
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), false);

        assertEquals(0, attempted);
        assertTrue(chat.received.isEmpty());
    }

    @Test
    void mutedRecipientGetsNothingAndIsNotCounted() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of("chat"), true);
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), false);

        assertEquals(0, attempted);
        assertTrue(chat.received.isEmpty());
    }

    @Test
    void unregisteredMediumIsSkippedWhileOthersStillDeliver() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of("chat", "carrier-pigeon"));
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), false);

        assertEquals(1, attempted);
        assertEquals(1, chat.received.size());
    }

    @Test
    void aThrowingSinkDoesNotPreventTheOtherSinkReceivingIt() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(new ThrowingSink());
        NotificationPreferences preferences = fixedMedia(Set.of("chat", "throws"));
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), false);

        assertEquals(1, attempted);
        assertEquals(1, chat.received.size());
    }

    @Test
    void returnValueEqualsRecipientsActuallyAttempted() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of("chat"));
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"),
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), false);

        assertEquals(3, attempted);
        assertEquals(3, chat.received.size());
    }

    // ----- with bypass -----

    @Test
    void bypassDeliversASilencedRecipientOnChatAndCountsThem() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of(NotificationPreferences.SILENCED_MEDIUM));
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), true);

        assertEquals(1, attempted);
        assertEquals(1, chat.received.size());
    }

    @Test
    void bypassDeliversAMutedRecipientWithNoUsableMediaOnChat() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        NotificationPreferences preferences = fixedMedia(Set.of(NotificationPreferences.SILENCED_MEDIUM), true);
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), true);

        assertEquals(1, attempted);
        assertEquals(1, chat.received.size());
    }

    @Test
    void bypassDeliversAMutedRecipientPreferringDiscordOnDiscordNotChat() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        RecordingSink discord = new RecordingSink("discord-dm", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(discord);
        NotificationPreferences preferences = fixedMedia(Set.of("discord-dm"), true);
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), true);

        assertEquals(1, attempted);
        assertEquals(1, discord.received.size());
        assertTrue(chat.received.isEmpty());
    }

    @Test
    void bypassDoesNotAffectARecipientWithRealMedia() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        RecordingSink discord = new RecordingSink("discord-dm", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(discord);
        NotificationPreferences preferences = fixedMedia(Set.of("chat", "discord-dm"));
        Broadcaster broadcaster = new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);

        int attempted = broadcaster.broadcast(Component.text("hi"), List.of(UUID.randomUUID()), true);

        assertEquals(1, attempted);
        assertEquals(1, chat.received.size());
        assertEquals(1, discord.received.size());
    }

    // --- Task 3: suppressed() ---

    /** Per-player media and mute, so one call can mix suppressed and deliverable recipients. */
    private static NotificationPreferences perPlayer(java.util.Map<UUID, Set<String>> media,
                                                     Set<UUID> muted) {
        return new NotificationPreferences() {
            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player) {
                return media.getOrDefault(player, Set.of());
            }

            @Override
            public @NotNull Set<String> preferredMedia(@NotNull UUID player, @NotNull String dataType) {
                return media.getOrDefault(player, Set.of());
            }

            @Override
            public boolean isMuted(@NotNull UUID player) {
                return muted.contains(player);
            }
        };
    }

    private static Broadcaster broadcasterWith(NotificationPreferences preferences) {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink("chat", DeliveryResult.DELIVERED));
        return new Broadcaster(TestMessages.shipped(), sinks, preferences, LOGGER);
    }

    @Test
    void suppressedReturnsAMutedRecipient() {
        UUID muted = UUID.randomUUID();
        Broadcaster broadcaster = broadcasterWith(
                perPlayer(java.util.Map.of(muted, Set.of("chat")), Set.of(muted)));

        assertEquals(List.of(muted), broadcaster.suppressed(List.of(muted)));
    }

    @Test
    void suppressedReturnsARecipientSilencedToNone() {
        UUID silenced = UUID.randomUUID();
        Broadcaster broadcaster = broadcasterWith(perPlayer(
                java.util.Map.of(silenced, Set.of(NotificationPreferences.SILENCED_MEDIUM)), Set.of()));

        assertEquals(List.of(silenced), broadcaster.suppressed(List.of(silenced)));
    }

    @Test
    void suppressedReturnsARecipientWithNoPreferredMediaAtAll() {
        UUID empty = UUID.randomUUID();
        Broadcaster broadcaster = broadcasterWith(perPlayer(java.util.Map.of(), Set.of()));

        assertEquals(List.of(empty), broadcaster.suppressed(List.of(empty)));
    }

    @Test
    void suppressedExcludesARecipientWithUsableMedia() {
        UUID reachable = UUID.randomUUID();
        Broadcaster broadcaster = broadcasterWith(
                perPlayer(java.util.Map.of(reachable, Set.of("chat")), Set.of()));

        assertTrue(broadcaster.suppressed(List.of(reachable)).isEmpty());
    }

    @Test
    void suppressedPreservesInputOrderAndKeepsOnlyTheSuppressed() {
        UUID first = UUID.randomUUID();
        UUID reachable = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        Broadcaster broadcaster = broadcasterWith(perPlayer(
                java.util.Map.of(reachable, Set.of("chat")), Set.of(first, last)));

        assertEquals(List.of(first, last), broadcaster.suppressed(List.of(first, reachable, last)));
    }
}
