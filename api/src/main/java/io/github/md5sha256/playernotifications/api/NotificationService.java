package io.github.md5sha256.playernotifications.api;

import org.bukkit.entity.Player;

/**
 * Public API entry point for sending notifications to players.
 */
public interface NotificationService {

    /**
     * Sends a notification message to the given player.
     *
     * @param player  the recipient
     * @param message the message to deliver
     */
    void notify(Player player, String message);
}
