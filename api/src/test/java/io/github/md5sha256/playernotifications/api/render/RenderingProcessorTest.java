package io.github.md5sha256.playernotifications.api.render;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

class RenderingProcessorTest {

    private static final UUID TARGET = UUID.randomUUID();
    private static final RenderableNotification RENDERED =
            new RenderableNotification(Component.text("title"), Component.text("body"));
    private static final NotificationRenderer<String> RENDERER = (payload, target) -> RENDERED;

    @Test
    @DisplayName("delivers to every sink the target prefers")
    void deliversToEverySink() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        RecordingSink discord = new RecordingSink("discord", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);
        sinks.registerSink(discord);

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, fixedPreferences("chat", "discord"), "test-type", Logger.getLogger("test"));

        NotificationDisposition disposition = processor.receiveNotification("payload", TARGET);

        Assertions.assertEquals(NotificationDisposition.DELETE, disposition);
        Assertions.assertEquals(List.of(RENDERED), chat.received);
        Assertions.assertEquals(List.of(RENDERED), discord.received);
    }

    @Test
    @DisplayName("DELETE wins when at least one sink delivers")
    void deleteWinsOnMixedResults() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink("chat", DeliveryResult.UNREACHABLE));
        sinks.registerSink(new RecordingSink("discord", DeliveryResult.DELIVERED));

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, fixedPreferences("chat", "discord"), "test-type", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.DELETE,
                processor.receiveNotification("payload", TARGET));
    }

    @Test
    @DisplayName("RETAIN when no sink delivers")
    void retainWhenNoneDeliver() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink("chat", DeliveryResult.UNREACHABLE));
        sinks.registerSink(new RecordingSink("discord", DeliveryResult.UNSUPPORTED));

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, fixedPreferences("chat", "discord"), "test-type", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.RETAIN,
                processor.receiveNotification("payload", TARGET));
    }

    @Test
    @DisplayName("warns when nothing is delivered and at least one medium is UNSUPPORTED")
    void warnsWhenAllUnsupported() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink("chat", DeliveryResult.UNREACHABLE));
        sinks.registerSink(new RecordingSink("discord", DeliveryResult.UNSUPPORTED));

        Logger logger = Logger.getLogger("test.warnsWhenAllUnsupported");
        logger.setUseParentHandlers(false);
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = capturingHandler(captured);
        logger.addHandler(handler);

        try {
            RenderingProcessor<String> processor = new RenderingProcessor<>(
                    RENDERER, sinks, fixedPreferences("chat", "discord"), "test-type", logger);

            Assertions.assertEquals(NotificationDisposition.RETAIN,
                    processor.receiveNotification("payload", TARGET));

            boolean warned = captured.stream()
                    .anyMatch(record -> record.getLevel() == Level.WARNING
                            && record.getMessage().contains("discord"));
            Assertions.assertTrue(warned, "expected a warning naming the unsupported medium 'discord'");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("does not warn when nothing is delivered but all media were only UNREACHABLE")
    void noWarningWhenAllUnreachable() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new RecordingSink("chat", DeliveryResult.UNREACHABLE));
        sinks.registerSink(new RecordingSink("discord", DeliveryResult.UNREACHABLE));

        Logger logger = Logger.getLogger("test.noWarningWhenAllUnreachable");
        logger.setUseParentHandlers(false);
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = capturingHandler(captured);
        logger.addHandler(handler);

        try {
            RenderingProcessor<String> processor = new RenderingProcessor<>(
                    RENDERER, sinks, fixedPreferences("chat", "discord"), "test-type", logger);

            Assertions.assertEquals(NotificationDisposition.RETAIN,
                    processor.receiveNotification("payload", TARGET));

            boolean warned = captured.stream().anyMatch(record -> record.getLevel() == Level.WARNING);
            Assertions.assertFalse(warned, "did not expect a warning when every medium was UNREACHABLE");
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static Handler capturingHandler(List<LogRecord> sink) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                sink.add(record);
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }

    @Test
    @DisplayName("an unknown medium with no registered sink is skipped, not fatal")
    void unknownMediumSkipped() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, fixedPreferences("chat", "carrier-pigeon"), "test-type", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.DELETE,
                processor.receiveNotification("payload", TARGET));
        Assertions.assertEquals(List.of(RENDERED), chat.received);
    }

    @Test
    @DisplayName("a throwing sink is contained and does not prevent delivery to the remaining sinks")
    void throwingSinkIsContained() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        NotificationSink throwing = new NotificationSink() {
            @Override
            public String mediumKey() {
                return "broken";
            }

            @Override
            public DeliveryResult deliver(RenderableNotification notification, UUID target) {
                throw new RuntimeException("boom");
            }
        };
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(throwing);
        sinks.registerSink(chat);

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, fixedPreferences("broken", "chat"), "test-type", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.DELETE,
                processor.receiveNotification("payload", TARGET));
        Assertions.assertEquals(List.of(RENDERED), chat.received);
    }

    @Test
    @DisplayName("no preferred media retains the notification without rendering")
    void noPreferredMediaRetains() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, fixedPreferences(), "test-type", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.RETAIN,
                processor.receiveNotification("payload", TARGET));
    }

    @Test
    @DisplayName("passes the given dataType through to the two-argument preference lookup")
    void passesDataTypeToPreferenceLookup() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        RecordingSink chat = new RecordingSink("chat", DeliveryResult.DELIVERED);
        sinks.registerSink(chat);

        List<String> dataTypesSeen = new ArrayList<>();
        NotificationPreferences preferences = new NotificationPreferences() {
            @Override
            public Set<String> preferredMedia(UUID player) {
                dataTypesSeen.add(null);
                return Set.of();
            }

            @Override
            public Set<String> preferredMedia(UUID player, String dataType) {
                dataTypesSeen.add(dataType);
                return Set.of("chat");
            }
        };

        RenderingProcessor<String> processor = new RenderingProcessor<>(
                RENDERER, sinks, preferences, "economy-payout", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.DELETE,
                processor.receiveNotification("payload", TARGET));
        Assertions.assertEquals(List.of("economy-payout"), dataTypesSeen);
    }

    private static NotificationPreferences fixedPreferences(String... media) {
        Set<String> set = Set.of(media);
        return target -> set;
    }

    private static final class RecordingSink implements NotificationSink {

        private final String mediumKey;
        private final DeliveryResult result;
        private final List<RenderableNotification> received = new ArrayList<>();

        private RecordingSink(String mediumKey, DeliveryResult result) {
            this.mediumKey = mediumKey;
            this.result = result;
        }

        @Override
        public String mediumKey() {
            return this.mediumKey;
        }

        @Override
        public DeliveryResult deliver(RenderableNotification notification, UUID target) {
            this.received.add(notification);
            return this.result;
        }
    }
}
