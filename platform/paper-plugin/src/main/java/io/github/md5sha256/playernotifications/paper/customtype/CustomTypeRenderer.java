package io.github.md5sha256.playernotifications.paper.customtype;

import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.localisation.TypeNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Renders a notification of any operator-declared type — the single renderer they all share, since
 * they all share {@link CustomNotificationPayload}.
 *
 * <p><b>The title is resolved from the declaration on every render</b>, not captured at enqueue time.
 * That is what makes editing a title in {@code notification-types.yml} and running
 * {@code /notifications reload} change how notifications already sitting in players' inboxes read —
 * the behaviour an operator expects from a file they think of as templates.
 *
 * <p>A payload whose type is <b>no longer declared</b> still renders, titled with the title-cased
 * key. The alternative — failing, and so showing the inbox's "unrenderable payload" placeholder —
 * would lose the message body, which is the part the player actually needs; the mapping being gone
 * already produces the placeholder for anything enqueued before the declaration existed.
 *
 * <p>The body parse is guarded for the reason {@code MailRenderer}'s and {@code BroadcastRenderer}'s
 * are: MiniMessage <em>throws</em> on a legacy {@code §} code, and an unrenderable payload must not
 * take down the inbox screen.
 *
 * <p>{@code target} is ignored — a declared type reads the same to everyone, as a broadcast does.
 */
public final class CustomTypeRenderer implements NotificationRenderer<CustomNotificationPayload> {

    private final CustomNotificationTypes types;

    public CustomTypeRenderer(@NotNull CustomNotificationTypes types) {
        this.types = types;
    }

    @Override
    public @NotNull RenderableNotification render(@NotNull CustomNotificationPayload payload,
                                                  @NotNull UUID target) {
        Component title = this.types.title(payload.typeKey())
                // load() already proved a declared title parses, so no guard is needed here.
                .map(raw -> MiniMessage.miniMessage().deserialize(raw))
                .orElseGet(() -> Component.text(TypeNames.titleCase(payload.typeKey())));
        Component body;
        try {
            body = MiniMessage.miniMessage().deserialize(payload.message());
        } catch (RuntimeException ex) {
            body = Component.text(payload.message());
        }
        return new RenderableNotification(title, body);
    }
}
