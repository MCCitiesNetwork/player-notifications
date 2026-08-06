package io.github.md5sha256.playernotifications.paper.diagnostic;

import io.github.md5sha256.playernotifications.api.render.NotificationRenderer;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Function;

/**
 * Renders a {@link TestNotificationPayload} into the medium-neutral form every sink consumes.
 *
 * <p>The rendering is written to be unmistakable to whoever receives it. A test notification lands in
 * the same inbox as real ones — a Discord DM, Essentials mail — where it has no context at all, so the
 * title is marked {@code [Test]} and the first line of the body says what it is and why it arrived.
 * Without that it reads as a stray debug message from a broken plugin.
 *
 * <p>The message itself is deliberately bold: a plain unstyled body would exercise nothing in a sink's
 * style handling, and the point of this payload is to prove a sink works end to end. It uses no click
 * events, matching the {@link RenderableNotification} contract that sinks may drop them.
 */
public final class TestNotificationRenderer implements NotificationRenderer<TestNotificationPayload> {

    private static final Component TITLE = Component.text()
            .append(Component.text("[Test] ", NamedTextColor.YELLOW))
            .append(Component.text("Test Notification", NamedTextColor.GOLD))
            .build();

    private static final Component EXPLANATION = Component.text(
            "This is a test notification, sent by the /notifications test command. "
                    + "Nothing is wrong — it confirms that notifications can reach you here.",
            NamedTextColor.GRAY);

    private final Function<UUID, String> nameLookup;

    /**
     * @param nameLookup resolves a target to a display name, returning {@code null} when the server has
     *                   never seen that player. Injected rather than calling {@link Bukkit} directly so
     *                   the renderer stays testable — {@code Bukkit.getOfflinePlayer} needs a live server
     *                   and throws otherwise. {@link #usingServerNames()} is the production wiring.
     */
    public TestNotificationRenderer(@NotNull Function<UUID, String> nameLookup) {
        this.nameLookup = nameLookup;
    }

    /** The renderer as the plugin wires it: names resolved through the server's offline player cache. */
    public static @NotNull TestNotificationRenderer usingServerNames() {
        return new TestNotificationRenderer(uuid -> Bukkit.getOfflinePlayer(uuid).getName());
    }

    @Override
    public @NotNull RenderableNotification render(@NotNull TestNotificationPayload payload,
                                                  @NotNull UUID target) {
        Component body = Component.empty()
                .append(EXPLANATION)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(payload.message())
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(Component.text("Sent to " + nameOf(target), NamedTextColor.GRAY));
        return new RenderableNotification(TITLE, body);
    }

    /**
     * The target's name, falling back to the raw UUID when the server has never seen it. Naming the
     * target keeps the per-target proof the old {@code target: <uuid>} line existed for, without shipping
     * a raw UUID into a Discord DM.
     */
    private @NotNull String nameOf(@NotNull UUID target) {
        String name = this.nameLookup.apply(target);
        return name == null ? target.toString() : name;
    }
}
