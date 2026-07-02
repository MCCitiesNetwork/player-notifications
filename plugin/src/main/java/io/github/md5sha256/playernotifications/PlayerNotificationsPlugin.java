package io.github.md5sha256.playernotifications;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.core.DefaultNotificationService;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerNotificationsPlugin extends JavaPlugin {

    private NotificationService notificationService;

    @Override
    public void onEnable() {
        this.notificationService = new DefaultNotificationService();
        getServer().getServicesManager().register(
                NotificationService.class,
                this.notificationService,
                this,
                ServicePriority.Normal
        );
        getLogger().info("PlayerNotifications enabled");
    }

    @Override
    public void onDisable() {
        if (this.notificationService != null) {
            getServer().getServicesManager().unregisterAll(this);
        }
        getLogger().info("PlayerNotifications disabled");
    }
}
