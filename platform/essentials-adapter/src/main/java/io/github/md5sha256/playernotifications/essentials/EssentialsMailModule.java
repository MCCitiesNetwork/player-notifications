package io.github.md5sha256.playernotifications.essentials;

import com.earth2me.essentials.Essentials;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import net.democracrycraft.pluginInfrastructure.modules.ModuleInitializationException;
import net.democracrycraft.pluginInfrastructure.modules.PluginModule;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin module that renders notifications through Essentials mail.
 *
 * <p>On initialization it registers a {@link EssentialsMailProcessor} for the
 * {@link #MAIL_DATA_TYPE} data type against the host's {@link NotificationService} registry, so
 * plain-text notifications of that type are delivered as Essentials mail.
 */
public final class EssentialsMailModule implements PluginModule<PlayerNotificationsPlugin> {

    /**
     * Data type under which plain-text mail notifications and their payload class are registered.
     */
    public static final String MAIL_DATA_TYPE = "essentials-mail";

    @Override
    public void initialize(@NotNull PlayerNotificationsPlugin plugin) throws ModuleInitializationException {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Essentials")) {
            throw new ModuleInitializationException(
                    "EssentialsX is not installed; cannot enable notifications module");
        }
        Essentials essentials = (Essentials) plugin.getServer()
                .getPluginManager()
                .getPlugin("Essentials");

        assert essentials != null;

        NotificationService service = plugin.notificationService();
        EssentialsMailProcessor processor = new EssentialsMailProcessor(plugin, essentials);
        service.dataTypeRegistry().registerPayloadMapping(MAIL_DATA_TYPE, String.class);
        service.dataTypeRegistry().registerProcessor(String.class, processor);
        plugin.getLogger()
                .info("Essentials mail adapter registered for data type '" + MAIL_DATA_TYPE + "'");
    }

    @Override
    public void shutdown(@NotNull PlayerNotificationsPlugin plugin) {
        // The registry exposes no unregister hook; the processor simply becomes unreachable once
        // the lifecycle manager closes this module's class loader.
        plugin.notificationService().dataTypeRegistry().unregisterPayloadMapping(MAIL_DATA_TYPE);
    }
}
