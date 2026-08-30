package io.github.md5sha256.playernotifications.discord;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleInitializationException;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.PluginModule;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.discord.command.DiscordMailService;
import io.github.md5sha256.playernotifications.discord.command.DiscordUserResolver;
import io.github.md5sha256.playernotifications.discord.command.InboxInteractionListener;
import io.github.md5sha256.playernotifications.discord.command.InboxMessageFactory;
import io.github.md5sha256.playernotifications.discord.command.InboxView;
import io.github.md5sha256.playernotifications.discord.command.MailCommandListener;
import io.github.md5sha256.playernotifications.discord.command.NotificationsCommandListener;
import io.github.md5sha256.playernotifications.discord.command.PreferenceInteractionListener;
import io.github.md5sha256.playernotifications.discord.command.PreferenceMessageFactory;
import io.github.md5sha256.playernotifications.discord.command.PreferenceView;
import io.github.md5sha256.playernotifications.discord.command.SlashCommandRegistrar;
import io.github.md5sha256.playernotifications.paper.inbox.InboxEntryRenderer;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import io.github.md5sha256.playernotifications.discord.schema.DiscordSchemaMigrator;
import io.github.md5sha256.playernotifications.paper.PlayerNotificationsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Plugin module that delivers notifications as Discord direct messages.
 *
 * <p>Registers a {@link DiscordDmSink} under {@link DiscordMedia#DM} in the host's
 * {@link io.github.md5sha256.playernotifications.api.NotificationSinkRegistry}. Unlike a bespoke
 * <em>processor</em>, which wins dispatch precedence and so bypasses preferences, a sink participates in
 * the player's stored preferences and in the fan-out, and appears automatically in the
 * {@code /notifications} dialogs, which enumerate the registered media.
 */
public final class DiscordModule implements PluginModule<PlayerNotificationsPlugin> {

    /** Where this module's config lives, relative to the host's data folder. */
    private static final String CONFIG_RESOURCE = "discord.yml";

    private DiscordBot bot;

    /** Guards teardown, so shutdown is idempotent and does not undo what was never registered. */
    private boolean linkProviderRegistered;

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
        // is nothing for a code to be redeemed into, so neither the Discord /link command nor the host's
        // /notifications link discord is offered.
        Executor asyncExecutor = runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        DiscordLinkFlow linkFlow = null;
        List<Object> eventListeners = new ArrayList<>();
        // Registered unconditionally: it owns the single updateCommands() call, and decides from these
        // two toggles which commands that call carries.
        eventListeners.add(new SlashCommandRegistrar(
                settings.resolvedCommandsEnabled(), settings.usesEmbeddedProvider(), logger));
        if (settings.usesEmbeddedProvider()) {
            LinkCodeService codes = new LinkCodeService(settings.resolvedLinkCodeExpiry(), Clock.systemUTC());
            linkFlow = new DiscordLinkFlow(linkStore, codes, logger);
            eventListeners.add(new LinkSlashCommandListener(linkFlow, asyncExecutor, logger));
        }

        if (settings.resolvedCommandsEnabled()) {
            eventListeners.addAll(commandListeners(plugin, accounts, settings, asyncExecutor, logger));
        }

        try {
            this.bot = DiscordBot.start(settings.botToken(), eventListeners.toArray());
        } catch (RuntimeException exception) {
            // A rejected token surfaces from build(); report it as a module failure rather than an
            // unhandled exception out of the lifecycle manager.
            throw new ModuleInitializationException(
                    "Failed to start the Discord bot: " + exception.getMessage());
        }

        if (linkFlow != null) {
            plugin.accountLinkRegistry().registerProvider(new DiscordAccountLinkProvider(linkFlow));
            this.linkProviderRegistered = true;
            logger.info("/notifications link " + DiscordMedia.LINK_PROVIDER_KEY
                    + " is available for Discord account linking");
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
     * The {@code /mail} and {@code /notifications} listeners, built over the host's own mail, inbox and
     * preference machinery — this module adds a second client onto it, not a second copy of it.
     *
     * <p>Nothing here needs unregistering: the listeners die with the bot, and the only host
     * registrations this module makes are the sink and the link provider, both undone in
     * {@link #shutdown}.
     */
    private static @NotNull List<Object> commandListeners(@NotNull PlayerNotificationsPlugin plugin,
                                                          @NotNull DiscordAccountProvider accounts,
                                                          @NotNull DiscordSettings settings,
                                                          @NotNull Executor asyncExecutor,
                                                          @NotNull Logger logger) {
        DiscordUserResolver users = new DiscordUserResolver(accounts, logger);
        InboxEntryRenderer entryRenderer =
                new InboxEntryRenderer(plugin.notificationService().dataTypeRegistry(), logger);

        InboxView notifications = new InboxView(plugin.notificationService(), entryRenderer,
                null, "Notifications", plugin::inboxPageSize);
        InboxView mailInbox = new InboxView(plugin.notificationService(), entryRenderer,
                MailPayload.DATA_TYPE, "Mail", plugin::inboxPageSize);

        DiscordMailService mail = new DiscordMailService(
                new MailSender(plugin.notificationService()),
                new MailNotifier(plugin.sinkRegistry(), plugin.preferences(), plugin.messages(), logger),
                DiscordModule::resolveRecipient,
                playerId -> String.valueOf(Bukkit.getOfflinePlayer(playerId).getName()),
                logger);

        // A session manager this module owns, not the host's: sharing would let an Apply from Discord
        // commit a half-finished in-game dialog edit with nothing on the player's screen saying so.
        // Type labels come from the host, so a rename in type-names.yml reads the same here as it does
        // in game. Flattened to plain text: a Discord select option carries a string, not a component,
        // so a coloured name loses its colour and keeps its words.
        PreferenceView preferences = new PreferenceView(plugin.preferences(), plugin.sinkRegistry(),
                () -> plugin.notificationService().dataTypeRegistry().dataTypes(),
                new PreferenceSessionManager(), logger, plugin.typeNames()::plainName);

        InboxMessageFactory inboxMessages = new InboxMessageFactory(settings.resolvedEmbedColor());
        PreferenceMessageFactory preferenceMessages =
                new PreferenceMessageFactory(settings.resolvedEmbedColor());

        return List.of(
                new MailCommandListener(users, mail, mailInbox, inboxMessages, asyncExecutor, logger),
                new NotificationsCommandListener(users, notifications, inboxMessages, preferences,
                        asyncExecutor, logger),
                new InboxInteractionListener(users, notifications, mailInbox, inboxMessages,
                        asyncExecutor, logger),
                new PreferenceInteractionListener(users, preferences, preferenceMessages,
                        asyncExecutor, logger));
    }

    /**
     * The same rule {@code /mail send} applies in game: an online player by name, else one this server
     * has seen before. A never-joined name is rejected, because {@code getOfflinePlayer(String)}
     * fabricates a UUID for any string at all.
     */
    private static UUID resolveRecipient(@NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    @Override
    public void shutdown(@NotNull PlayerNotificationsPlugin plugin) {
        plugin.sinkRegistry().unregisterSink(DiscordMedia.DM);

        // Deregister before the bot goes down, so there is no window in which /notifications link discord
        // dispatches into a shut-down bot on a module stop/start cycle. The command node itself stays —
        // the host answers for an absent provider with "Discord linking is not available on this server",
        // which is also what a server that never installed this module shows.
        if (this.linkProviderRegistered) {
            plugin.accountLinkRegistry().unregisterProvider(DiscordMedia.LINK_PROVIDER_KEY);
            this.linkProviderRegistered = false;
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
