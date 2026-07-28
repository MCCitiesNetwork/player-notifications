package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

class DiscordDmSinkTest {

    private static final Logger LOGGER = Logger.getLogger(DiscordDmSinkTest.class.getName());
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final RenderableNotification NOTIFICATION =
            new RenderableNotification(Component.text("Title"), Component.text("Body"));

    private static final DiscordMessageFactory FACTORY =
            new DiscordMessageFactory(DiscordMessageFormat.EMBED, 0x5865F2);

    private record FixedProvider(Optional<Long> discordId) implements DiscordAccountProvider {

        @Override
        public String providerKey() {
            return "fixed";
        }

        @Override
        public Optional<Long> discordIdFor(UUID playerUuid) {
            return this.discordId;
        }
    }

    /** Records the id it was asked to message and returns a canned result. */
    private static final class RecordingMessenger implements DiscordMessenger {

        private final DeliveryResult result;
        private final RuntimeException failure;
        private Long lastRecipient;
        private int calls;

        private RecordingMessenger(DeliveryResult result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        static RecordingMessenger returning(DeliveryResult result) {
            return new RecordingMessenger(result, null);
        }

        static RecordingMessenger throwing() {
            return new RecordingMessenger(null, new IllegalStateException("boom"));
        }

        @Override
        public DeliveryResult send(long discordUserId, MessageCreateData message) {
            this.calls++;
            this.lastRecipient = discordUserId;
            if (this.failure != null) {
                throw this.failure;
            }
            return this.result;
        }
    }

    private static DiscordDmSink sink(DiscordAccountProvider accounts, DiscordMessenger messenger) {
        return new DiscordDmSink(accounts, FACTORY, messenger, LOGGER);
    }

    @Test
    void mediumKeyIsDiscordDm() {
        Assertions.assertEquals("discord-dm",
                sink(new FixedProvider(Optional.empty()), RecordingMessenger.returning(DeliveryResult.DELIVERED))
                        .mediumKey());
    }

    @Test
    void displayNameOverridesTheTitleCasedDefault() {
        // NotificationSink's default would title-case "discord-dm" into "Discord Dm".
        DiscordDmSink sink = sink(new FixedProvider(Optional.empty()),
                RecordingMessenger.returning(DeliveryResult.DELIVERED));

        Assertions.assertEquals("Discord DM",
                PlainTextComponentSerializer.plainText().serialize(sink.displayName()));
    }

    @Test
    void descriptionIsNotEmpty() {
        DiscordDmSink sink = sink(new FixedProvider(Optional.empty()),
                RecordingMessenger.returning(DeliveryResult.DELIVERED));

        Assertions.assertNotEquals(Component.empty(), sink.description());
    }

    @Test
    void anUnlinkedPlayerIsUnsupportedAndTheMessengerIsNeverCalled() {
        RecordingMessenger messenger = RecordingMessenger.returning(DeliveryResult.DELIVERED);

        DeliveryResult result =
                sink(new FixedProvider(Optional.empty()), messenger).deliver(NOTIFICATION, PLAYER);

        Assertions.assertEquals(DeliveryResult.UNSUPPORTED, result);
        Assertions.assertEquals(0, messenger.calls);
    }

    @Test
    void aLinkedPlayerIsMessagedAtTheirDiscordId() {
        RecordingMessenger messenger = RecordingMessenger.returning(DeliveryResult.DELIVERED);

        DeliveryResult result =
                sink(new FixedProvider(Optional.of(4242L)), messenger).deliver(NOTIFICATION, PLAYER);

        Assertions.assertEquals(DeliveryResult.DELIVERED, result);
        Assertions.assertEquals(4242L, messenger.lastRecipient);
    }

    @Test
    void theMessengerResultIsPassedThroughUnchanged() {
        RecordingMessenger messenger = RecordingMessenger.returning(DeliveryResult.UNREACHABLE);

        Assertions.assertEquals(DeliveryResult.UNREACHABLE,
                sink(new FixedProvider(Optional.of(1L)), messenger).deliver(NOTIFICATION, PLAYER));
    }

    @Test
    void aThrowingMessengerBecomesUnreachableRatherThanPropagating() {
        DeliveryResult result = sink(new FixedProvider(Optional.of(1L)), RecordingMessenger.throwing())
                .deliver(NOTIFICATION, PLAYER);

        Assertions.assertEquals(DeliveryResult.UNREACHABLE, result);
    }

    @Test
    void aThrowingAccountProviderBecomesUnreachable() {
        DiscordAccountProvider exploding = new DiscordAccountProvider() {
            @Override
            public String providerKey() {
                return "exploding";
            }

            @Override
            public Optional<Long> discordIdFor(UUID playerUuid) {
                throw new IllegalStateException("boom");
            }
        };

        Assertions.assertEquals(DeliveryResult.UNREACHABLE,
                sink(exploding, RecordingMessenger.returning(DeliveryResult.DELIVERED))
                        .deliver(NOTIFICATION, PLAYER));
    }

    @Test
    void deliveryOnTheMainThreadIsRefusedAsUnreachable() {
        RecordingMessenger messenger = RecordingMessenger.returning(DeliveryResult.DELIVERED);
        DiscordDmSink sink = new DiscordDmSink(
                new FixedProvider(Optional.of(1L)), FACTORY, messenger, LOGGER, () -> true);

        Assertions.assertEquals(DeliveryResult.UNREACHABLE, sink.deliver(NOTIFICATION, PLAYER));
        Assertions.assertEquals(0, messenger.calls, "the sink must not block the main thread");
    }
}
