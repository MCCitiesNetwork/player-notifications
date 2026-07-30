package io.github.md5sha256.playernotifications.discord;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleInitializationException;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.PluginModule;
import io.github.md5sha256.playernotifications.discord.schema.DiscordSchemaMigrator;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.Executor;
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
    private static final String CONFIG_RESOURCE = "discord.yml";

    private DiscordBot bot;

    /** Guards teardown, so shutdown is idempotent and does not undo what was never registered. */
    private boolean linkCommandRegistered;
    private boolean permissionRegistered;

    @Override
    public void initialize(@NotNull PlayerNotificationsPlugin plugin, @NotNull Path dataPath)
            throws ModuleInitializationException {
        Logger logger = plugin.getLogger();
        DiscordSettings settings = loadSettings(dataPath);

        if (settings.isTokenBlank()) {
            throw new ModuleInitializationException(
                    "No Discord bot token configured in " + CONFIG_RESOURCE
                            + "; cannot enable the Discord adapter");
        }

        // This module owns its schema, so it migrates it itself — over the host's connection pool, but
        // tracked in its own discord_schema_version chain. Before the store is constructed: the store must
        // never be handed to the provider before its table exists.
        try {
            DiscordSchemaMigrator.migrate(plugin.database(), logger);
        } catch (IOException | SQLException exception) {
            // A Discord adapter whose table is missing is strictly worse than no Discord adapter: every
            // link lookup would fail on every delivery.
            throw new ModuleInitializationException(
                    "Failed to migrate the Discord adapter schema: " + exception.getMessage());
        }

        DiscordAccountLinkStore linkStore =
                new DatabaseDiscordAccountLinkStore(plugin.database(), Clock.systemUTC());

        DiscordAccountProviderRegistry providers = new DiscordAccountProviderRegistry();
        providers.register(new EmbeddedDiscordAccountProvider(linkStore));
        providers.register(new DiscordSrvAccountProvider(logger));
        ChainedDiscordAccountProvider accounts =
                ChainedDiscordAccountProvider.of(settings.linkProviders(), providers, logger);
        accounts.reportAvailability();

        // The link flow only exists when the operator has asked for the embedded provider; otherwise there
        // is nothing for a code to be redeemed into, so neither command is registered.
        Executor asyncExecutor = runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        DiscordLinkFlow linkFlow = null;
        Object[] eventListeners = new Object[0];
        if (settings.usesEmbeddedProvider()) {
            LinkCodeService codes = new LinkCodeService(settings.resolvedLinkCodeExpiry(), Clock.systemUTC());
            linkFlow = new DiscordLinkFlow(linkStore, codes, logger);
            eventListeners = new Object[]{new LinkSlashCommandListener(linkFlow, asyncExecutor, logger)};
        }

        try {
            this.bot = DiscordBot.start(settings.botToken(), eventListeners);
        } catch (RuntimeException exception) {
            // A rejected token surfaces from build(); report it as a module failure rather than an
            // unhandled exception out of the lifecycle manager.
            throw new ModuleInitializationException(
                    "Failed to start the Discord bot: " + exception.getMessage());
        }

        if (linkFlow != null) {
            registerLinkCommand(plugin, linkFlow, asyncExecutor, logger);
        }

        DiscordMessageFactory factory = new DiscordMessageFactory(
                settings.resolvedMessageFormat(), settings.resolvedEmbedColor());
        DiscordMessenger messenger =
                new JdaDiscordMessenger(this.bot, settings.deliveryTimeoutSeconds(), logger);

        plugin.sinkRegistry().registerSink(new DiscordDmSink(accounts, factory, messenger, logger));
        logger.info("Discord adapter registered for medium '" + DiscordMedia.DM
                + "' (format: " + settings.resolvedMessageFormat() + ")");
    }

    /**
     * Registers {@code /discordlink} and its permission.
     *
     * <p>Both are done programmatically because the module jar carries no plugin descriptor: the permission
     * cannot go in the host's {@code paper-plugin.yml}, and the command is registered through the host's
     * lifecycle manager during the host's own enable.
     */
    private void registerLinkCommand(@NotNull PlayerNotificationsPlugin plugin,
                                     @NotNull DiscordLinkFlow flow,
                                     @NotNull Executor asyncExecutor,
                                     @NotNull Logger logger) {
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        if (pluginManager.getPermission(DiscordLinkCommand.PERMISSION) == null) {
            pluginManager.addPermission(new Permission(DiscordLinkCommand.PERMISSION,
                    "Link your Minecraft account to Discord with /discordlink",
                    PermissionDefault.TRUE));
            this.permissionRegistered = true;
        }
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        DiscordLinkCommand.create(flow, asyncExecutor),
                        DiscordLinkCommand.DESCRIPTION,
                        DiscordLinkCommand.aliases()
                ));
        this.linkCommandRegistered = true;
        logger.info("/" + DiscordLinkCommand.LITERAL + " is available for Discord account linking");
    }

    @Override
    public void shutdown(@NotNull PlayerNotificationsPlugin plugin) {
        plugin.sinkRegistry().unregisterSink(DiscordMedia.DM);

        // Remove the command and permission before the bot goes down, so there is no window in which
        // /discordlink dispatches into a shut-down bot on a module stop/start cycle.
        if (this.linkCommandRegistered) {
            DiscordLinkCommand.unregister(plugin.getLogger());
            this.linkCommandRegistered = false;
        }
        if (this.permissionRegistered) {
            plugin.getServer().getPluginManager().removePermission(DiscordLinkCommand.PERMISSION);
            this.permissionRegistered = false;
        }

        if (this.bot != null) {
            this.bot.shutdown();
            this.bot = null;
        }
    }

    private @NotNull DiscordSettings loadSettings(@NotNull Path dataPath)
            throws ModuleInitializationException {
        Path file = dataPath.resolve(CONFIG_RESOURCE);
        try {
            ConfigurationNode root = ModuleConfigs.load(file, DiscordModule::bundledDefault);
            DiscordSettings settings = root.get(DiscordSettings.class);
            if (settings == null) {
                throw new ModuleInitializationException(
                        CONFIG_RESOURCE + " could not be deserialized into DiscordSettings");
            }
            return settings;
        } catch (SerializationException exception) {
            throw new ModuleInitializationException(
                    "Failed to read " + CONFIG_RESOURCE + ": " + exception.getMessage());
        } catch (IOException exception) {
            throw new ModuleInitializationException(
                    "Failed to load " + CONFIG_RESOURCE + ": " + exception.getMessage());
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
