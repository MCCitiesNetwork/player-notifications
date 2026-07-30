package io.github.md5sha256.playernotifications.essentials;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleInitializationException;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.PluginModule;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

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
    public void initialize(@NotNull PlayerNotificationsPlugin plugin, @NotNull Path dataPath) throws ModuleInitializationException {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Essentials")) {
            throw new ModuleInitializationException(
                    "EssentialsX is not installed; cannot enable notifications module");
        }
        // Every EssentialsX-typed reference lives in EssentialsMailBinding, so this method verifies
        // without loading a single EssentialsX class and the guard above is actually reachable. See
        // that class for why touching them here would take the whole host plugin down.
        EssentialsMailBinding.register(plugin);
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
