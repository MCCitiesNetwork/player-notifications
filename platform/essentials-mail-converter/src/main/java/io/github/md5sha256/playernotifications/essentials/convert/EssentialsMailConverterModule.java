package io.github.md5sha256.playernotifications.essentials.convert;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleInitializationException;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.PluginModule;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * A one-shot migration tool: imports a server's existing EssentialsX mailboxes into first-party mail,
 * so retiring EssentialsX does not throw away everybody's correspondence.
 *
 * <p>This is <em>not</em> an integration. The module registers no sink, no processor, no renderer, no
 * category and no schema — it owns exactly one command, {@code /essmailconvert}, and once an operator has
 * run it the module has no further reason to be installed. Delivery, preferences and rendering are
 * untouched by it.
 *
 * <p>Design: {@code docs/superpowers/specs/2026-08-20-essentials-mail-converter-design.md}.
 */
public final class EssentialsMailConverterModule implements PluginModule<PlayerNotificationsPlugin> {

    @Override
    public void initialize(@NotNull PlayerNotificationsPlugin plugin, @NotNull Path dataPath)
            throws ModuleInitializationException {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Essentials")) {
            // Not a ModuleInitializationException: a converter with no source is a no-op, not a broken
            // module. A server that no longer runs EssentialsX has simply finished with this module.
            plugin.getLogger().info("EssentialsX is not installed; nothing to convert");
            return;
        }
        // Every EssentialsX-typed reference lives in EssentialsMailBinding, so this method verifies
        // without loading a single EssentialsX class and the guard above is actually reachable. See that
        // class for why touching them here would take the whole host plugin down.
        EssentialsMailBinding.register(plugin);
        plugin.getLogger().info("EssentialsX mail converter ready; run /essmailconvert");
    }

    @Override
    public void shutdown(@NotNull PlayerNotificationsPlugin plugin) {
        // Nothing to undo. The module registers a command and no state, and it is declared
        // reloadable: false, so its only stop is server shutdown — at which point the command map is
        // discarded anyway. This is why a module-owned command is safe here where it was not for the
        // Discord adapter, whose teardown needed surgery through the live command map.
    }
}
