package io.github.md5sha256.playernotifications.core;

import io.github.md5sha256.playernotifications.api.NotificationService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Default {@link NotificationService} implementation that delivers notifications
 * as chat messages via the Adventure API.
 */
public final class DefaultNotificationService implements NotificationService {

    @Override
    public void notify(Player player, String message) {
        player.sendMessage(Component.text(message));
    }
}
