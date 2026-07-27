package io.github.md5sha256.playernotifications.api.render.sink;

import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Delivers a {@link RenderableNotification} as in-game chat messages: the title and body are each sent
 * as a chat component. Only reaches players who are currently online.
 *
 * <p>Sending is marshalled onto the server main thread when this sink is invoked off it, as
 * {@code EssentialsMailProcessor} already does — delivery runs on the async prune/join path.
 */
public final class ChatSink implements NotificationSink {

    public static final String MEDIUM_KEY = "chat";

    private final Plugin plugin;

    public ChatSink(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String mediumKey() {
        return MEDIUM_KEY;
    }

    @Override
    public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification, @NotNull UUID target) {
        Player player = Bukkit.getPlayer(target);
        if (player == null) {
            return DeliveryResult.UNREACHABLE;
        }
        if (Bukkit.isPrimaryThread()) {
            send(player, notification);
        } else {
            Bukkit.getScheduler().runTask(this.plugin, () -> send(player, notification));
        }
        return DeliveryResult.DELIVERED;
    }

    private void send(@NotNull Player player, @NotNull RenderableNotification notification) {
        player.sendMessage(notification.title());
        player.sendMessage(notification.body());
    }
}
