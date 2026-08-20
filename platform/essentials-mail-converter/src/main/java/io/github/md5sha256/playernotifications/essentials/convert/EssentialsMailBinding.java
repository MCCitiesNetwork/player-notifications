package io.github.md5sha256.playernotifications.essentials.convert;

import com.earth2me.essentials.IEssentials;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleInitializationException;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * Holds every reference to an EssentialsX type in this module, apart from {@link EssentialsMailReader}
 * which it constructs.
 *
 * <p>This class exists purely to keep those references out of {@link EssentialsMailConverterModule}. The
 * module loader resolves the manifest's entry class with
 * {@code Class.forName(name, true, moduleClassLoader)}, which links and verifies every method on it.
 * Verifying a method that casts to, or passes, an EssentialsX type forces that type to load — before any
 * runtime guard in the method body can run. With EssentialsX absent that throws
 * {@link NoClassDefFoundError}, which the module loader does not catch (it catches only
 * {@code ModuleLoadException}), so it propagates out of {@code onEnable} and takes the entire host plugin
 * down rather than skipping one optional module.
 *
 * <p>Because {@link #register} is only ever called through its EssentialsX-free signature, this class is
 * not loaded until control actually reaches the call — after the caller has confirmed EssentialsX is
 * enabled.
 */
final class EssentialsMailBinding {

    private EssentialsMailBinding() {
    }

    @SuppressWarnings("UnstableApiUsage")
    static void register(@NotNull PlayerNotificationsPlugin plugin) throws ModuleInitializationException {
        Plugin essentialsPlugin = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (!(essentialsPlugin instanceof IEssentials essentials)) {
            throw new ModuleInitializationException(
                    "EssentialsX reported itself enabled but its plugin instance is missing or is not an "
                            + "EssentialsX build this module understands");
        }
        EssentialsMailReader reader = new EssentialsMailReader(plugin, essentials, plugin.getLogger());
        EssentialsMailConverter converter = new EssentialsMailConverter(
                plugin.notificationService(),
                new MailSender(plugin.notificationService()),
                plugin.getLogger());
        Executor asyncExecutor =
                runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable);
        // Modules start inside onEnable (startModules(), after the host's own registerCommands()), and
        // Paper fires COMMANDS after enable — so a handler registered here is still in time. This is the
        // same ordering the host relies on for link providers registered by modules.
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        ConvertMailCommand.create(reader, converter, asyncExecutor, plugin.getLogger()),
                        ConvertMailCommand.DESCRIPTION));
    }
}
