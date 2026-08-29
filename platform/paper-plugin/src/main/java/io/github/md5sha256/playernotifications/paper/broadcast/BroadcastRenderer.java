package io.github.md5sha256.playernotifications.paper.broadcast;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Renders a stored ({@code --persistent}) broadcast for the inbox. Registered through
 * {@code NotificationService#registerJsonRenderable}, which is what turns {@code broadcast} from a
 * mapping-only key into a real, storable payload — see {@link BroadcastPayload}.
 *
 * <p><b>No processor is registered alongside it</b>, deliberately: an explicitly registered processor
 * wins dispatch and would bypass preferences and sinks entirely. That is right for {@code mail}, whose
 * content must never be pushed, and wrong here — a persistent broadcast should behave like every other
 * renderable notification.
 *
 * <p>The body is parsed as MiniMessage because that is what {@code /broadcast} accepts and what
 * {@link BroadcastPayload} stores — the raw source text, not a rendered component, so that how a stored
 * broadcast reads is decided here and only here. The parse is guarded for the reason
 * {@code MailRenderer}'s is: MiniMessage <em>throws</em> on a legacy {@code §} code, and a payload that
 * cannot be rendered must still produce something readable rather than take down the inbox screen.
 *
 * <p>{@code target} is ignored — a broadcast reads the same to everyone, which is the whole point of it.
 */
public final class BroadcastRenderer implements NotificationRenderer<BroadcastPayload> {

    private final MessageContainer messages;

    public BroadcastRenderer(@NotNull MessageContainer messages) {
        this.messages = messages;
    }

    @Override
    public @NotNull RenderableNotification render(@NotNull BroadcastPayload payload,
                                                  @NotNull UUID target) {
        // Read per render rather than held in a field, so /notifications reload takes effect — and so
        // this title matches the one Broadcaster gives a live broadcast.
        Component title = this.messages.messageFor(MessageKeys.BROADCAST_TITLE);
        Component body;
        try {
            body = MiniMessage.miniMessage().deserialize(payload.message());
        } catch (RuntimeException ex) {
            body = Component.text(payload.message());
        }
        return new RenderableNotification(title, body);
    }
}
