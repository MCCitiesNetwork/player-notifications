package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import net.democracrycraft.pluginInfrastructure.modules.ModuleInitializationException;
import net.democracrycraft.pluginInfrastructure.modules.PluginModule;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Plugin module that delivers notifications as Discord direct messages.
 *
 * <p>Registers a {@link DiscordDmSink} under {@link DiscordMedia#DM} in the host's
 * {@link io.github.md5sha256.playernotifications.api.NotificationSinkRegistry}. Unlike the Essentials
 * adapter — which registers a <em>processor</em> and so bypasses preferences — a sink participates in
 * the player's stored preferences and in the fan-out, and appears automatically in the
 * {@code /notifications} dialogs, which enumerate the registered media.
 */
public final class DiscordModule implements PluginModule<PlayerNotificationsPlugin> {

    /** Where this module's config lives, relative to the host's data folder. */
    private static final String CONFIG_PATH = "modules/discord.yml";
    private static final String CONFIG_RESOURCE = "discord.yml";

    private DiscordBot bot;

    @Override
    public void initialize(@NotNull PlayerNotificationsPlugin plugin)
            throws ModuleInitializationException {
        Logger logger = plugin.getLogger();
        DiscordSettings settings = loadSettings(plugin);

        if (settings.isTokenBlank()) {
            throw new ModuleInitializationException(
                    "No Discord bot token configured in " + CONFIG_PATH
                            + "; cannot enable the Discord adapter");
        }

        DiscordAccountProviderRegistry providers = new DiscordAccountProviderRegistry();
        providers.register(new DiscordSrvAccountProvider(logger));
        ChainedDiscordAccountProvider accounts =
                ChainedDiscordAccountProvider.of(settings.linkProviders(), providers, logger);

        try {
            this.bot = DiscordBot.start(settings.botToken());
        } catch (RuntimeException exception) {
            // A rejected token surfaces from build(); report it as a module failure rather than an
            // unhandled exception out of the lifecycle manager.
            throw new ModuleInitializationException(
                    "Failed to start the Discord bot: " + exception.getMessage());
        }
        DiscordMessageFactory factory = new DiscordMessageFactory(
                settings.resolvedMessageFormat(), settings.resolvedEmbedColor());
        DiscordMessenger messenger =
                new JdaDiscordMessenger(this.bot, settings.deliveryTimeoutSeconds(), logger);

        plugin.sinkRegistry().registerSink(new DiscordDmSink(accounts, factory, messenger, logger));
        logger.info("Discord adapter registered for medium '" + DiscordMedia.DM
                + "' (format: " + settings.resolvedMessageFormat() + ")");
    }

    @Override
    public void shutdown(@NotNull PlayerNotificationsPlugin plugin) {
        plugin.sinkRegistry().unregisterSink(DiscordMedia.DM);
        if (this.bot != null) {
            this.bot.shutdown();
            this.bot = null;
        }
    }

    private @NotNull DiscordSettings loadSettings(@NotNull PlayerNotificationsPlugin plugin)
            throws ModuleInitializationException {
        Path file = plugin.getDataFolder().toPath().resolve(CONFIG_PATH);
        try {
            ConfigurationNode root = ModuleConfigs.load(file, DiscordModule::bundledDefault);
            DiscordSettings settings = root.get(DiscordSettings.class);
            if (settings == null) {
                throw new ModuleInitializationException(
                        CONFIG_PATH + " could not be deserialized into DiscordSettings");
            }
            return settings;
        } catch (SerializationException exception) {
            throw new ModuleInitializationException(
                    "Failed to read " + CONFIG_PATH + ": " + exception.getMessage());
        } catch (IOException exception) {
            throw new ModuleInitializationException(
                    "Failed to load " + CONFIG_PATH + ": " + exception.getMessage());
        }
    }

    /**
     * Opens the bundled default from <em>this module's</em> jar. The host's copy-defaults helper
     * reads the host jar's resources and so cannot see it.
     */
    private static InputStream bundledDefault() {
        return DiscordModule.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE);
    }
}
