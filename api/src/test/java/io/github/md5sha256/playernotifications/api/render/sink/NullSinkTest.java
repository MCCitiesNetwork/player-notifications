package io.github.md5sha256.playernotifications.api.render.sink;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.api.render.RenderingProcessor;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

class NullSinkTest {

    private static final UUID TARGET = UUID.randomUUID();
    private static final RenderableNotification RENDERED =
            new RenderableNotification(Component.text("title"), Component.text("body"));

    @Test
    @DisplayName("reports DELIVERED so a muted player's notifications are consumed, not queued")
    void reportsDelivered() {
        Assertions.assertEquals(DeliveryResult.DELIVERED, new NullSink().deliver(RENDERED, TARGET));
    }

    @Test
    @DisplayName("a player preferring only 'none' has the notification deleted")
    void mutedTargetDeletesNotification() {
        NotificationSinkRegistry sinks = new NotificationSinkRegistry();
        sinks.registerSink(new NullSink());

        NotificationRenderer<String> renderer = (payload, target) -> RENDERED;
        NotificationPreferences muted = target -> Set.of(NullSink.MEDIUM_KEY);
        RenderingProcessor<String> processor =
                new RenderingProcessor<>(renderer, sinks, muted, "test-type", Logger.getLogger("test"));

        Assertions.assertEquals(NotificationDisposition.DELETE,
                processor.receiveNotification("payload", TARGET));
    }

    @Test
    @DisplayName("default display name title-cases the medium key")
    void defaultDisplayNameIsTitleCased() {
        Assertions.assertEquals(Component.text("Essentials Mail"), displayNameOf("essentials-mail"));
        Assertions.assertEquals(Component.text("Chat"), displayNameOf("chat"));
        Assertions.assertEquals(Component.text("Web Push Alert"), displayNameOf("web_push_alert"));
    }

    @Test
    @DisplayName("a sink may override its display name and description")
    void overriddenDisplayName() {
        NullSink sink = new NullSink();
        Assertions.assertEquals(Component.text("None"), sink.displayName());
        Assertions.assertNotEquals(Component.empty(), sink.description());
    }

    private static Component displayNameOf(String mediumKey) {
        NotificationSink sink = new NotificationSink() {
            @Override
            public String mediumKey() {
                return mediumKey;
            }

            @Override
            public DeliveryResult deliver(RenderableNotification notification, UUID target) {
                return DeliveryResult.DELIVERED;
            }
        };
        return sink.displayName();
    }
}
