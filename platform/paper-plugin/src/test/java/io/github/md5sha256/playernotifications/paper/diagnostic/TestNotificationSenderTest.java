package io.github.md5sha256.playernotifications.paper.diagnostic;

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

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code /notifications test}'s reply, which distinguishes four outcomes a player can
 * otherwise only tell apart by guessing: muted, this type silenced, no media chosen, and delivered.
 *
 * <p>Only {@code report} is exercised. {@code send} needs the Bukkit scheduler and a live
 * {@code Player}, so it stays manually verified — the reply logic was pulled behind a
 * package-private seam precisely so this half does not have to be.
 */
class TestNotificationSenderTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void mutedPlayerIsToldTheNotificationWasSuppressedAndHowToUndoIt() {
        TestNotificationSender sender = senderWith(fixedMedia(Set.of("chat"), true), registry("chat"));

        String reply = plain(sender.report(PLAYER));

        assertTrue(reply.contains("muted"), reply);
        assertTrue(reply.contains("/notifications unmute"), reply);
    }

    @Test
    void silencedTypeIsReportedAsTheSettingItIsRatherThanAsAFailure() {
        TestNotificationSender sender = senderWith(
                fixedMedia(Set.of(NotificationPreferences.SILENCED_MEDIUM), false), registry("chat"));

        String reply = plain(sender.report(PLAYER));

        assertTrue(reply.contains("silenced"), reply);
        assertTrue(reply.contains("/notifications preferences"), reply);
    }

    @Test
    void noChosenMediaIsWordedApartFromASilence() {
        TestNotificationSender sender = senderWith(fixedMedia(Set.of(), false), registry("chat"));

        String reply = plain(sender.report(PLAYER));

        assertTrue(reply.contains("no delivery methods"), reply);
        // The two "nothing was sent" cases must not read the same: one is a choice to silence the
        // type, the other is a type that was never configured, and they are fixed differently.
        assertFalse(reply.contains("silenced"), reply);
    }

    @Test
    void attemptedMediaAreListedByDisplayNameAndSeparated() {
        TestNotificationSender sender =
                senderWith(fixedMedia(Set.of("chat", "dialog"), false), registry("chat", "dialog"));

        String reply = plain(sender.report(PLAYER));

        assertTrue(reply.contains("Chat"), reply);
        assertTrue(reply.contains("Dialog"), reply);
        assertTrue(reply.contains("Chat, Dialog"), reply);
    }

    @Test
    void aPreferredMediumWithNoRegisteredSinkIsCalledOut() {
        // RenderingProcessor skips a sink-less medium silently, so the reply would otherwise claim
        // a delivery attempt that never happened — the usual cause is an uninstalled module.
        TestNotificationSender sender =
                senderWith(fixedMedia(Set.of("chat", "discord-dm"), false), registry("chat"));

        String reply = plain(sender.report(PLAYER));

        assertTrue(reply.contains("skipped"), reply);
        assertTrue(reply.contains("discord-dm"), reply);
    }

    private static TestNotificationSender senderWith(NotificationPreferences preferences,
                                                     NotificationSinkRegistry registry) {
        // report() reads only the preferences and the sink registry; the plugin, service and delivery
        // supplier belong to send(), which this suite does not exercise.
        return new TestNotificationSender(TestMessages.shipped(), null, null, preferences, registry, null);
    }

    private static NotificationSinkRegistry registry(String... media) {
        NotificationSinkRegistry registry = new NotificationSinkRegistry();
        for (String medium : media) {
            registry.registerSink(new StubSink(medium));
        }
        return registry;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

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

    /** Registered only so the registry can resolve a display name for the medium. */
    private record StubSink(String key) implements NotificationSink {
        @Override
        public @NotNull String mediumKey() {
            return this.key;
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            return DeliveryResult.DELIVERED;
        }
    }
}
