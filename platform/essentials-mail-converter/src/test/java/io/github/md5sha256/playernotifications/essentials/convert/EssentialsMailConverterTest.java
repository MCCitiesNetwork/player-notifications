package io.github.md5sha256.playernotifications.essentials.convert;

import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.ResolvedNotification;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Covers every mapping rule in the design doc's table. This is where the converter's behaviour is
 * actually pinned: the reader, the binding and the command around it hold no decisions.
 */
class EssentialsMailConverterTest {

    private static final Logger LOGGER = Logger.getLogger(EssentialsMailConverterTest.class.getName());
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final Instant SENT_AT = Instant.parse("2019-04-01T12:00:00Z");

    /** Records enqueues and markSeen calls; optionally throws on a nominated message. */
    private static final class RecordingService implements NotificationService {

        private final List<TypedNotification<?>> enqueued = new ArrayList<>();
        private final List<String> seen = new ArrayList<>();
        private String throwOnMessage;
        private String throwOnMarkSeenMessage;

        @Override
        public void enqueueNotification(@NotNull ResolvedNotification notification, boolean overwriteAllowed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void enqueueNotification(@NotNull TypedNotification<T> notification, boolean overwriteAllowed) {
            MailPayload payload = (MailPayload) notification.notifPayload();
            if (payload.message().equals(this.throwOnMessage)) {
                throw new IllegalStateException("simulated database failure");
            }
            this.enqueued.add(notification);
        }

        @Override
        public void markSeen(@NotNull String key, @NotNull UUID playerId) {
            TypedNotification<?> match = this.enqueued.stream()
                    .filter(n -> n.notifKey().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("markSeen for a key that was never enqueued: " + key));
            if (((MailPayload) match.notifPayload()).message().equals(this.throwOnMarkSeenMessage)) {
                throw new IllegalStateException("simulated markSeen failure");
            }
            Assertions.assertEquals(RECIPIENT, playerId);
            this.seen.add(key);
        }

        @Override
        public void markUnread(@NotNull String key, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void registerJsonPayload(@NotNull String dataType, @NotNull Class<T> type,
                                            @NotNull NotificationProcessor<T> processor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void registerJsonRenderable(@NotNull String dataType, @NotNull Class<T> type,
                                               @NotNull NotificationRenderer<T> renderer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull List<ResolvedNotification> resolveNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotification(@NotNull String notificationKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTarget(@NotNull String notificationKey, @NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteNotificationTargets(@NotNull String notificationKey, @NotNull Collection<UUID> playerIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearNotifications(@NotNull String notificationDataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearExpiredNotifications() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull InboxPage inbox(@NotNull UUID playerId, int page, int pageSize, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int unreadCount(@NotNull UUID playerId, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAllSeen(@NotNull UUID playerId, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dismissSeen(@NotNull UUID playerId, @Nullable String dataType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void pruneOrphanedTargets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationDataTypeRegistry dataTypeRegistry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NotNull NotificationCategoryRegistry categoryRegistry() {
            throw new UnsupportedOperationException();
        }
    }

    private static EssentialsMailConverter converter(RecordingService service) {
        return new EssentialsMailConverter(service, new MailSender(service), LOGGER);
    }

    private static ImportedMail mail(String message) {
        return new ImportedMail(RECIPIENT, SENDER, "Alex", message, SENT_AT, false, false);
    }

    @Test
    @DisplayName("an ordinary mail is enqueued with its original send time and no expiry")
    void ordinaryMail() {
        RecordingService service = new RecordingService();

        ConversionReport report = converter(service).convert(List.of(mail("hello")));

        Assertions.assertEquals(new ConversionReport(1, 0, 0, 0), report);
        Assertions.assertEquals(1, service.enqueued.size());
        TypedNotification<?> notification = service.enqueued.get(0);
        Assertions.assertEquals(MailPayload.DATA_TYPE, notification.notifPayloadType());
        Assertions.assertEquals(SENT_AT, notification.notifScheduledTime());
        Assertions.assertNull(notification.notifExpiryTime());
        Assertions.assertEquals(List.of(RECIPIENT), notification.notifTarget().playerUUIDs());
        MailPayload payload = (MailPayload) notification.notifPayload();
        Assertions.assertEquals(SENDER, payload.sender());
        Assertions.assertEquals("Alex", payload.senderName());
        Assertions.assertEquals("hello", payload.message());
        Assertions.assertTrue(service.seen.isEmpty());
    }

    @Test
    @DisplayName("an expired mail is skipped and nothing is enqueued for it")
    void expiredIsSkipped() {
        RecordingService service = new RecordingService();
        ImportedMail expired = new ImportedMail(RECIPIENT, SENDER, "Alex", "old", SENT_AT, false, true);

        ConversionReport report = converter(service).convert(List.of(expired));

        Assertions.assertEquals(new ConversionReport(0, 1, 0, 0), report);
        Assertions.assertTrue(service.enqueued.isEmpty());
    }

    @Test
    @DisplayName("a blank message is skipped rather than enqueued as an invalid payload")
    void blankIsSkipped() {
        RecordingService service = new RecordingService();

        ConversionReport report = converter(service).convert(List.of(mail("   ")));

        Assertions.assertEquals(new ConversionReport(0, 0, 1, 0), report);
        Assertions.assertTrue(service.enqueued.isEmpty());
    }

    @Test
    @DisplayName("an over-length message is imported whole, not truncated")
    void overLongMessageIsImportedWhole() {
        RecordingService service = new RecordingService();
        String long_ = "x".repeat(MailPayload.MAX_MESSAGE_LENGTH + 50);

        ConversionReport report = converter(service).convert(List.of(mail(long_)));

        Assertions.assertEquals(1, report.imported());
        Assertions.assertEquals(long_, ((MailPayload) service.enqueued.get(0).notifPayload()).message());
    }

    @Test
    @DisplayName("a mail read in EssentialsX is marked seen; an unread one is not")
    void readStateIsCarried() {
        RecordingService service = new RecordingService();
        ImportedMail read = new ImportedMail(RECIPIENT, SENDER, "Alex", "read one", SENT_AT, true, false);

        converter(service).convert(List.of(read, mail("unread one")));

        Assertions.assertEquals(2, service.enqueued.size());
        Assertions.assertEquals(1, service.seen.size());
        String seenKey = service.seen.get(0);
        TypedNotification<?> seenNotification = service.enqueued.stream()
                .filter(n -> n.notifKey().equals(seenKey))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("read one", ((MailPayload) seenNotification.notifPayload()).message());
    }

    @Test
    @DisplayName("a failed enqueue is counted and does not stop the mails after it")
    void failureIsIsolated() {
        RecordingService service = new RecordingService();
        service.throwOnMessage = "boom";

        ConversionReport report = converter(service).convert(
                List.of(mail("first"), mail("boom"), mail("third")));

        Assertions.assertEquals(new ConversionReport(2, 0, 0, 1), report);
        Assertions.assertEquals(2, service.enqueued.size());
    }

    @Test
    @DisplayName("a failed markSeen leaves the mail imported, since it is stored and readable")
    void markSeenFailureIsNotAnImportFailure() {
        RecordingService service = new RecordingService();
        service.throwOnMarkSeenMessage = "read one";
        ImportedMail read = new ImportedMail(RECIPIENT, SENDER, "Alex", "read one", SENT_AT, true, false);

        ConversionReport report = converter(service).convert(List.of(read));

        Assertions.assertEquals(new ConversionReport(1, 0, 0, 0), report);
        Assertions.assertEquals(1, service.enqueued.size());
    }

    @Test
    @DisplayName("MiniMessage tags in imported mail are escaped, not left live")
    void miniMessageTagsAreEscaped() {
        RecordingService service = new RecordingService();

        converter(service).convert(List.of(mail("<red>hi</red> <click:run_command:'/op me'>x</click>")));

        String stored = ((MailPayload) service.enqueued.get(0).notifPayload()).message();
        // Stored mail is MiniMessage source, parsed on read by MailRenderer. EssentialsX text was never
        // gated by MailFormatting's permissions, so it must render as the literal text it always was.
        Assertions.assertEquals("<red>hi</red> <click:run_command:'/op me'>x</click>",
                PlainTextComponentSerializer.plainText()
                        .serialize(MiniMessage.miniMessage().deserialize(stored)));
    }

    @Test
    @DisplayName("a message that is nothing but escaped tags is still imported, not skipped as blank")
    void tagOnlyMessageIsImported() {
        RecordingService service = new RecordingService();

        ConversionReport report = converter(service).convert(List.of(mail("<red>")));

        Assertions.assertEquals(1, report.imported());
        Assertions.assertEquals("<red>", PlainTextComponentSerializer.plainText()
                .serialize(MiniMessage.miniMessage()
                        .deserialize(((MailPayload) service.enqueued.get(0).notifPayload()).message())));
    }

    @Test
    @DisplayName("preview reports the same counts as convert but writes nothing")
    void previewWritesNothing() {
        List<ImportedMail> mail = List.of(
                mail("hello"),
                new ImportedMail(RECIPIENT, SENDER, "Alex", "old", SENT_AT, false, true),
                mail("  "));

        RecordingService previewService = new RecordingService();
        ConversionReport preview = converter(previewService).preview(mail);
        RecordingService convertService = new RecordingService();
        ConversionReport converted = converter(convertService).convert(mail);

        Assertions.assertEquals(converted, preview);
        Assertions.assertTrue(previewService.enqueued.isEmpty());
        Assertions.assertFalse(convertService.enqueued.isEmpty());
    }

    @Test
    @DisplayName("total counts every mail considered, however it was classified")
    void totalCountsEverything() {
        Assertions.assertEquals(10, new ConversionReport(4, 3, 2, 1).total());
    }
}
