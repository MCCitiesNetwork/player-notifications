package io.github.md5sha256.playernotifications.api.render;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * A medium-neutral intermediate form of a notification: a title and a body, both {@link Component}s.
 *
 * <p>Deliberately holds no {@link org.bukkit.entity.Player} and no
 * {@link net.kyori.adventure.audience.Audience}, because some sinks (Essentials mail, Discord) deliver
 * to players who are not currently online. Sinks that are not Minecraft clients serialize the
 * components down to plain text or markdown, so the body must not rely on in-game-only affordances
 * such as click events — a sink is free to drop them.
 *
 * <p>Actions/buttons are out of scope; a dialog sink renders this as read-and-dismiss.
 *
 * @param title the notification's title
 * @param body  the notification's body
 */
public record RenderableNotification(@NotNull Component title, @NotNull Component body) {
}
