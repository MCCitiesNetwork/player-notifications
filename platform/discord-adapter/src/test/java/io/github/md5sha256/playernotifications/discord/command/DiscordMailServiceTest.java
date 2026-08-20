package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

class DiscordMailServiceTest {

    private static final Logger LOGGER = Logger.getLogger(DiscordMailServiceTest.class.getName());
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private FakeNotificationService service;
    private List<UUID> noticed;

    /** Records who the arrival notice reached, through the real sink fan-out. */
    private static final class RecordingSink implements NotificationSink {

        private final List<UUID> targets;

        private RecordingSink(List<UUID> targets) {
            this.targets = targets;
        }

        @Override
        public @NotNull String mediumKey() {
            return "recording";
        }

        @Override
        public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification,
                                               @NotNull UUID target) {
            this.targets.add(target);
            return DeliveryResult.DELIVERED;
        }
    }

    @BeforeEach
    void setUp() {
        this.service = new FakeNotificationService(new NotificationDataTypeRegistry());
        this.noticed = new ArrayList<>();
    }

    private DiscordMailService mailService() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink(this.noticed));
        NotificationPreferences preferences = player -> Set.of("recording");

        return new DiscordMailService(
                new MailSender(this.service),
                new MailNotifier(sinks, preferences, LOGGER),
                Map.of("Steve", RECIPIENT)::get,
                uuid -> "Alex",
                LOGGER);
    }

    /** What the recipient actually reads: the stored MiniMessage source, rendered back to plain text. */
    private static String renderedText(String stored) {
        return PlainTextComponentSerializer.plainText()
                .serialize(MiniMessage.miniMessage().deserialize(stored));
    }

    private static MailPayload payloadOf(TypedNotification<?> notification) {
        return (MailPayload) notification.notifPayload();
    }

    @Test
    void aMailIsEnqueuedForTheResolvedRecipientAndTheNoticeFires() {
        DiscordMailService.SendResult result = mailService().send(SENDER, "Steve", "hello there");

        Assertions.assertEquals("Steve",
                Assertions.assertInstanceOf(DiscordMailService.SendResult.Ok.class, result).recipientName());
        Assertions.assertEquals(1, this.service.enqueued().size());
        TypedNotification<?> enqueued = this.service.enqueued().get(0);
        Assertions.assertEquals(MailPayload.DATA_TYPE, enqueued.notifPayloadType());
        Assertions.assertEquals(List.of(RECIPIENT), enqueued.notifTarget().playerUUIDs());
        Assertions.assertEquals(SENDER, payloadOf(enqueued).sender());
        Assertions.assertEquals("Alex", payloadOf(enqueued).senderName(),
                "the sender's name is captured at send time, not looked up when the mail is read");
        Assertions.assertEquals("hello there", payloadOf(enqueued).message());
        Assertions.assertEquals(List.of(RECIPIENT), this.noticed);
    }

    @Test
    void anUnknownRecipientIsRejectedAndNothingIsEnqueued() {
        DiscordMailService.SendResult result = mailService().send(SENDER, "Nobody", "hello");

        Assertions.assertTrue(Assertions.assertInstanceOf(DiscordMailService.SendResult.Rejected.class, result)
                .reason().contains("Nobody"));
        Assertions.assertEquals(List.of(), this.service.enqueued());
        Assertions.assertEquals(List.of(), this.noticed);
    }

    @Test
    void aBlankMessageIsRejected() {
        Assertions.assertInstanceOf(DiscordMailService.SendResult.Rejected.class,
                mailService().send(SENDER, "Steve", "   "));
        Assertions.assertEquals(List.of(), this.service.enqueued());
    }

    @Test
    void anOverLongMessageIsRejectedRatherThanTruncated() {
        // Silently dropping the end of someone's sentence is worse than asking them to shorten it.
        DiscordMailService.SendResult result =
                mailService().send(SENDER, "Steve", "x".repeat(MailPayload.MAX_MESSAGE_LENGTH + 1));

        Assertions.assertTrue(Assertions.assertInstanceOf(DiscordMailService.SendResult.Rejected.class, result)
                .reason().contains(String.valueOf(MailPayload.MAX_MESSAGE_LENGTH)));
        Assertions.assertEquals(List.of(), this.service.enqueued());
    }

    @Test
    void aFormattingTagIsStoredEscapedSoItCanOnlyRenderAsText() {
        // Discord mail passes through no permission gate, so it must be able to render only as the
        // literal text it was — the same rule the EssentialsX importer follows.
        mailService().send(SENDER, "Steve", "<red>hello");

        String stored = payloadOf(this.service.enqueued().get(0)).message();
        Assertions.assertNotEquals("<red>hello", stored, "an unescaped tag would become live formatting");
        Assertions.assertEquals("<red>hello", renderedText(stored),
                "the recipient must see the tag as the text the sender typed");
    }

    @Test
    void aClickTagIsStoredEscapedSoItCannotRunACommand() {
        mailService().send(SENDER, "Steve", "<click:run_command:/op me>free op");

        String stored = payloadOf(this.service.enqueued().get(0)).message();
        Component rendered = MiniMessage.miniMessage().deserialize(stored);
        Assertions.assertEquals("<click:run_command:/op me>free op", renderedText(stored));
        Assertions.assertNull(rendered.clickEvent(), "an imported click event would run a command on click");
    }

    @Test
    void aFailedEnqueueIsReportedAndTheNoticeDoesNotFire() {
        // Announcing mail that was never stored would send the recipient to an empty inbox.
        this.service.failEnqueueWith(new IllegalStateException("database down"));

        DiscordMailService.SendResult result = mailService().send(SENDER, "Steve", "hello");

        Assertions.assertInstanceOf(DiscordMailService.SendResult.Failed.class, result);
        Assertions.assertEquals(List.of(), this.noticed);
    }
}
