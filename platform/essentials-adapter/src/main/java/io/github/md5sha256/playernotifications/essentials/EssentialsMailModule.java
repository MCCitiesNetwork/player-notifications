package io.github.md5sha256.playernotifications.essentials;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleInitializationException;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.PluginModule;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import org.jetbrains.annotations.NotNull;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;

import java.nio.file.Path;

/**
 * Plugin module that delivers notifications through Essentials mail.
 *
 * <p>It registers a single {@link EssentialsMailSink} in the host's {@link NotificationSinkRegistry},
 * making Essentials mail a delivery <em>medium</em> a player can select for any notification type — it
 * owns no data type, no payload class and no processor. That is the whole of it for now; a future
 * "you have been sent mail" notification would add a payload type and renderer of its own <em>alongside</em>
 * this sink, not in place of it.
 */
public final class EssentialsMailModule implements PluginModule<PlayerNotificationsPlugin> {

    /**
     * The medium key Essentials mail is offered under in preferences.
     */
    public static final String MAIL_MEDIUM = EssentialsMailSink.MEDIUM_KEY;

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
        plugin.getLogger().info("Essentials mail adapter registered as medium '" + MAIL_MEDIUM + "'");
    }

    @Override
    public void shutdown(@NotNull PlayerNotificationsPlugin plugin) {
        // Unregistering by key rather than by instance: the sink is constructed inside the
        // EssentialsX-typed binding class, which this method must not name (see EssentialsMailBinding).
        // Players keep their stored preference rows for the medium; the host renders an unregistered
        // medium by its raw key and RenderingProcessor skips it, which is the intended behaviour for a
        // module that has been removed.
        plugin.sinkRegistry().unregisterSink(MAIL_MEDIUM);
    }
}
