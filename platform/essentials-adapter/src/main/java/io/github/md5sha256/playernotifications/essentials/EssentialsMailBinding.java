package io.github.md5sha256.playernotifications.essentials;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleInitializationException;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Holds every reference to an EssentialsX type in this module.
 *
 * <p>This class exists purely to keep those references out of {@link EssentialsMailModule}. The
 * module loader resolves the manifest's entry class with
 * {@code Class.forName(name, true, moduleClassLoader)}, which links and verifies every method on it.
 * Verifying a method that casts to, or passes, an EssentialsX type forces that type to load — before
 * any runtime guard in the method body can run. With EssentialsX absent that throws
 * {@link NoClassDefFoundError}, which the module loader does not catch (it catches only
 * {@code ModuleLoadException}), so it propagates out of {@code onEnable} and takes the entire host
 * plugin down rather than skipping one optional module.
 *
 * <p>Because {@link #register} is only ever called through its EssentialsX-free signature, this
 * class is not loaded until control actually reaches the call — after the caller has confirmed
 * EssentialsX is enabled.
 */
final class EssentialsMailBinding {

    private EssentialsMailBinding() {
    }

    static void register(@NotNull PlayerNotificationsPlugin plugin) throws ModuleInitializationException {
        com.earth2me.essentials.Essentials essentials = (com.earth2me.essentials.Essentials) plugin.getServer()
                .getPluginManager()
                .getPlugin("Essentials");
        if (essentials == null) {
            throw new ModuleInitializationException(
                    "EssentialsX reported itself enabled but its plugin instance is missing");
        }

        NotificationService service = plugin.notificationService();
        EssentialsMailProcessor processor = new EssentialsMailProcessor(plugin, essentials);
        // registerJsonPayload, not the registry directly: a custom payload record needs a serializer
        // registered alongside the mapping, and the host only pre-registers one for String.
        service.registerJsonPayload(
                EssentialsMailModule.MAIL_DATA_TYPE, EssentialsMailPayload.class, processor);
    }
}
