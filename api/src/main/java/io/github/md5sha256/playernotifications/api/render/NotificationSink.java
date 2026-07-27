package io.github.md5sha256.playernotifications.api.render;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Delivers a {@link RenderableNotification} over a single medium (chat, a dialog, Essentials mail,
 * Discord, ...). One sink is written per medium, independent of the payload types that flow through it
 * — this is the other half of the N + M split described by {@link NotificationRenderer}.
 */
public interface NotificationSink {

    /**
     * The medium key this sink is registered under in the {@link io.github.md5sha256.playernotifications.api.NotificationSinkRegistry}
     * (e.g. {@code "chat"}, {@code "dialog"}, {@code "essentials-mail"}, {@code "discord"}).
     */
    @NotNull String mediumKey();

    /**
     * Attempts to deliver the given notification to the given target over this sink's medium.
     */
    @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification, @NotNull UUID target);

}
