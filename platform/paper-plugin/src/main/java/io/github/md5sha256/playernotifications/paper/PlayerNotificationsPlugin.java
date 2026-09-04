package io.github.md5sha256.playernotifications.paper;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLoader;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.link.AccountLinkRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.render.sink.ChatSink;
import io.github.md5sha256.playernotifications.api.render.sink.DialogSink;
import io.github.md5sha256.playernotifications.paper.broadcast.BroadcastPayload;
import io.github.md5sha256.playernotifications.paper.broadcast.BroadcastAudience;
import io.github.md5sha256.playernotifications.paper.broadcast.BroadcastRenderer;
import io.github.md5sha256.playernotifications.paper.broadcast.Broadcaster;
import io.github.md5sha256.playernotifications.paper.broadcast.LuckPermsBinding;
import io.github.md5sha256.playernotifications.paper.broadcast.OfflineBroadcastAudience;
import io.github.md5sha256.playernotifications.paper.broadcast.PersistentBroadcaster;
import io.github.md5sha256.playernotifications.paper.broadcast.OnlineBroadcastAudience;
import io.github.md5sha256.playernotifications.paper.command.AccountLinkDispatcher;
import io.github.md5sha256.playernotifications.paper.command.BroadcastCommand;
import io.github.md5sha256.playernotifications.paper.command.MailCommand;
import io.github.md5sha256.playernotifications.paper.command.NotificationsCommand;
import io.github.md5sha256.playernotifications.paper.command.SendCommand;
import io.github.md5sha256.playernotifications.paper.config.ConfigKeyGaps;
import io.github.md5sha256.playernotifications.paper.config.DeliveryDefaults;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationPayload;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationRenderer;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationSender;
import io.github.md5sha256.playernotifications.paper.inbox.InboxChatRow;
import io.github.md5sha256.playernotifications.paper.inbox.InboxEntryRenderer;
import io.github.md5sha256.playernotifications.paper.inbox.InboxQuitListener;
import io.github.md5sha256.playernotifications.paper.inbox.InboxFilters;
import io.github.md5sha256.playernotifications.paper.inbox.InboxRouter;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import io.github.md5sha256.playernotifications.paper.localisation.TypeNameDefaultsWriter;
import io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationTypes;
import io.github.md5sha256.playernotifications.paper.customtype.CustomTypeRegistrar;
import io.github.md5sha256.playernotifications.paper.localisation.TypeNames;
import io.github.md5sha256.playernotifications.paper.mail.MailChatRow;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import io.github.md5sha256.playernotifications.paper.mail.MailRenderer;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogRouter;
import io.github.md5sha256.playernotifications.paper.preferences.PreferenceQuitListener;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.DatabaseSettings;
import io.github.md5sha256.playernotifications.core.DefaultNotificationService;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import io.github.md5sha256.playernotifications.core.category.CategoryDefaultsWriter;
import io.github.md5sha256.playernotifications.core.config.GeneratedYaml;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.maria.MariaDatabase;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class PlayerNotificationsPlugin extends JavaPlugin {

    private static final Path MIGRATIONS_DIR = Path.of("sql/migrations");
    private static final String MODULES_DIR_NAME = "modules";

    private Database database;
    private DefaultNotificationService notificationService;
    private NotificationSinkRegistry sinkRegistry;
    private AccountLinkRegistry accountLinkRegistry;
    private DatabaseNotificationPreferences preferences;
    private NotificationDelivery notificationDelivery;
    private NotificationCategories categories;
    /**
     * Writes {@code defaults/categories.yml}, the generated reference copy of everything the code-side
     * category registry holds. Written, never read — see {@link CategoryDefaultsWriter}.
     */
    private CategoryDefaultsWriter categoryDefaultsWriter;
    /**
     * Resolves a {@code dataType} to the name players see: operator override, module default,
     * title-cased key. Reloaded in place like {@link #messages}, never replaced, so every holder keeps
     * a valid reference across {@code /notifications reload}.
     */
    private TypeNames typeNames;
    private CustomNotificationTypes customTypes;
    private CustomTypeRegistrar customTypeRegistrar;
    private TypeNameDefaultsWriter typeNameDefaultsWriter;
    /** Guards {@link #scheduleCategoryRebuild()} so a burst of late claims causes one rebuild, not one each. */
    private final AtomicBoolean categoryRebuildPending = new AtomicBoolean();
    /**
     * Player-facing text from {@code messages.yml}.
     *
     * <p>Final and reloaded <em>in place</em>, never replaced: every command and listener takes this
     * reference at construction and holds it for the plugin's lifetime, so {@code /notifications
     * reload} reaches all of them without re-registering anything — the same idiom
     * {@link DatabaseNotificationPreferences#reloadDefaultMedia} uses for {@code default-media}.
     */
    private final MessageContainer messages = new MessageContainer();
    private PreferenceDialogRouter preferenceDialogRouter;
    private BukkitTask pruneTask;
    private JoinDeliveryListener joinDeliveryListener;
    private ModuleLifecycleManager<PlayerNotificationsPlugin> moduleLifecycleManager;

    @NotNull
    public Database database() {
        return this.database;
    }

    @NotNull
    public NotificationService notificationService() {
        return this.notificationService;
    }

    /**
     * The registry feature modules (e.g. the future Discord adapter) register their own
     * {@link io.github.md5sha256.playernotifications.api.render.NotificationSink}s against.
     */
    @NotNull
    public NotificationSinkRegistry sinkRegistry() {
        return this.sinkRegistry;
    }

    /**
     * The registry feature modules register their own
     * {@link io.github.md5sha256.playernotifications.api.link.AccountLinkProvider}s against, surfacing
     * them as {@code /notifications link <provider>}.
     */
    @NotNull
    public AccountLinkRegistry accountLinkRegistry() {
        return this.accountLinkRegistry;
    }

    @NotNull
    public NotificationDelivery notificationDelivery() {
        return this.notificationDelivery;
    }

    /**
     * The persisted per-player medium preferences the {@code /notifications} dialog reads and writes.
     */
    @NotNull
    public DatabaseNotificationPreferences preferences() {
        return this.preferences;
    }

    /**
     * Resolves a registered {@code dataType} to every player-facing category (config- and
     * code-claimed) that claims it.
     */
    /**
     * How many inbox entries one page holds, from {@code settings.yml} and re-read on reload. Exposed
     * so a feature module paging the inbox itself agrees with the in-game screens.
     */
    public int inboxPageSize() {
        return this.inboxPageSize;
    }

    @NotNull
    public NotificationCategories categories() {
        return this.categories;
    }

    /** Player-facing text from {@code messages.yml}, reloaded in place by {@link #reload}. */
    @NotNull
    public MessageContainer messages() {
        return this.messages;
    }

    /**
     * Resolves a {@code dataType} to its player-facing name. Exposed so a feature module labelling
     * types on its own surface agrees with the in-game screens — the Discord adapter's preference
     * command is the one caller today.
     */
    @NotNull
    public TypeNames typeNames() {
        return this.typeNames;
    }

    /** The notification types the operator declared in {@code notification-types.yml}. */
    @NotNull
    public CustomNotificationTypes customTypes() {
        return this.customTypes;
    }

    @Override
    public void onEnable() {
        DatabaseSettings databaseSettings;
        PluginSettings pluginSettings;
        try {
            databaseSettings = loadDatabaseSettings();
            pluginSettings = loadPluginSettings();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load configuration; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MariaDatabase mariaDatabase = new MariaDatabase(databaseSettings, getLogger());
        this.database = mariaDatabase;
        try {
            mariaDatabase.initializeSchema(MIGRATIONS_DIR);
        } catch (IOException | SQLException ex) {
            getLogger().log(Level.SEVERE,
                    "Database schema migration failed; disabling plugin.",
                    ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.notificationService = new DefaultNotificationService(mariaDatabase);
        getServer().getServicesManager().register(
                NotificationService.class,
                this.notificationService,
                this,
                ServicePriority.Normal
        );

        // Everything the plugin generates and never reads goes in one folder, named after the live
        // file it mirrors -- see GeneratedYaml.DIRECTORY_NAME. The folder itself is created by the
        // first write, not here, so a failure to create it is reported the same way a failure to
        // write is: logged, never fatal.
        Path defaultsDir = getDataFolder().toPath().resolve(GeneratedYaml.DIRECTORY_NAME);
        this.categoryDefaultsWriter = new CategoryDefaultsWriter(
                defaultsDir.resolve(CategoryDefaultsWriter.FILE_NAME), getLogger());
        this.typeNames = new TypeNames(this.notificationService.dataTypeRegistry(), getLogger());
        this.customTypes = new CustomNotificationTypes(getLogger());
        this.typeNameDefaultsWriter = new TypeNameDefaultsWriter(
                defaultsDir.resolve(TypeNameDefaultsWriter.FILE_NAME),
                this.notificationService.dataTypeRegistry(), this.typeNames, getLogger());

        try {
            this.categories = loadCategories();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load categories.yml; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Before registerCommands(): every command class takes the container as a constructor
        // argument, so an unloaded one would leave the whole tree replying with bare key names.
        try {
            reloadMessages();
            this.typeNames.load(copyDefaultsYaml("type-names"));
            this.customTypes.load(copyDefaultsYaml("notification-types"));
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load messages.yml; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.accountLinkRegistry = new AccountLinkRegistry();
        this.sinkRegistry = new NotificationSinkRegistry();
        this.sinkRegistry.registerSink(new ChatSink(this));
        this.sinkRegistry.registerSink(new DialogSink(this));
        this.preferences =
                new DatabaseNotificationPreferences(mariaDatabase, pluginSettings.defaultMedia());
        // After the preferences object exists, not with the other config loads above: the map is
        // owned by core and pushed in, so there is nowhere to put it before this line.
        loadDeliveryDefaults();
        this.notificationDelivery = new NotificationDelivery(
                mariaDatabase,
                this.notificationService.dataTypeRegistry(),
                this.sinkRegistry,
                this.preferences,
                getLogger()
        );

        // Register the built-in test payload before registerCommands(): the preference dialogs enumerate
        // dataTypeRegistry().dataTypes(), so "test" must already be mapped to appear as configurable.
        this.notificationService.registerJsonRenderable(
                TestNotificationPayload.TEST_DATA_TYPE,
                TestNotificationPayload.class,
                TestNotificationRenderer.usingServerNames());

        // Mail is stored but never delivered: an explicit processor wins dispatch precedence and
        // bypasses preferences and sinks. RETAIN also leaves seenTime unset, so mail stays unread
        // until the player opens it in /mail. See the design doc, "Mail is stored, but never
        // delivered" — do not "simplify" this away.
        this.notificationService.registerJsonRenderable(
                MailPayload.DATA_TYPE, MailPayload.class, new MailRenderer());
        this.notificationService.dataTypeRegistry().registerProcessor(MailPayload.class,
                (payload, target) -> NotificationDisposition.RETAIN);

        // A full renderable registration since --persistent: mapping, JSON serializer and renderer, so
        // a stored broadcast round-trips and reads properly in the inbox. Deliberately NO processor —
        // an explicit processor wins dispatch and would bypass preferences and sinks entirely, which is
        // right for mail and wrong here. The mapping alone is also what makes "broadcast" enumerate in
        // dataTypeRegistry().dataTypes(), which the preference dialogs walk, so a player can silence it.
        this.notificationService.registerJsonRenderable(Broadcaster.BROADCAST_DATA_TYPE,
                BroadcastPayload.class, new BroadcastRenderer(this.messages));

        this.inboxPageSize = pluginSettings.inboxPageSize();
        registerCommands(pluginSettings.inboxPageSize());

        // Always registered, gated internally: /notifications reload can then flip deliver-on-join
        // without re-registering the listener. A supplier, not the instance — reload() replaces
        // notificationDelivery with a new object.
        this.joinDeliveryListener = new JoinDeliveryListener(
                this.messages,
                this, () -> this.notificationDelivery, this.notificationService, this.preferences,
                pluginSettings.deliverOnJoin(), pluginSettings.joinDeliveryDelaySeconds());
        getServer().getPluginManager().registerEvents(this.joinDeliveryListener, this);

        schedulePruneTask(pluginSettings.pruneIntervalSeconds());

        // Start modules last so they can look up the registered NotificationService and register their
        // own category claims against it.
        startModules();

        // After startModules(), so a module's claim on a key is already visible and an operator who
        // declared a colliding type is warned rather than silently replacing the module's mapping.
        this.customTypeRegistrar =
                new CustomTypeRegistrar(this.notificationService, this.customTypes, getLogger());
        this.customTypeRegistrar.sync();

        // Rebuild the merged categories now that modules have had a chance to register, and swap the
        // rebuilt view into the dialog router — the same mechanism /notifications reload uses.
        rebuildCategories("after module startup");

        // Our own modules are covered by the rebuild above, but a separate plugin registering from its
        // own onEnable runs strictly after ours has returned, so its claims would land in the registry
        // after this snapshot was frozen and every one of its data types would show as uncategorized.
        // Subscribing here means such a registration rebuilds the snapshot instead of being lost.
        this.notificationService.categoryRegistry().addChangeListener(this::scheduleCategoryRebuild);

        warnAboutUnmappedCategoryTypes();
        warnAboutMissingConfigKeys();

        // One tick: this runs on the server's first tick, after every other plugin's onEnable has
        // returned, so whatever the registry holds once the server is fully up is what the operator's
        // reference copy says. The change listener above does not make this redundant --
        // NotificationCategoryRegistry#addChangeListener has a default no-op body (deliberately, for
        // binary compatibility), so a third-party registry implementation never notifies us at all and
        // its defaults file would otherwise be frozen at whatever startModules() produced.
        //
        // In the ordinary case the listener has already scheduled a rebuild that also lands on this
        // tick, so this simply writes the same bytes again -- the output is sorted and deterministic,
        // so which of the two runs last cannot matter. A flag to suppress it would add ordering
        // reasoning to save one file write per server start.
        getServer().getScheduler().runTask(this, () -> {
            if (!isEnabled()) {
                return;
            }
            this.categoryDefaultsWriter.write(this.notificationService.categoryRegistry());
            this.typeNameDefaultsWriter.write();
        });

        getLogger().info("PlayerNotifications enabled");
    }

    /**
     * Logs a warning naming every data type declared under some category in {@code categories.yml} that
     * no registered payload mapping exists for, once feature modules have had a chance to register
     * theirs. A standing misconfiguration an operator should fix, not a startup-order race — modules
     * that register later than this check will simply be caught on the next server restart.
     */
    /**
     * Read by feature modules that page the inbox themselves. Volatile because {@code reload} replaces
     * it while a module holds a reference — the same idiom as the routers' own reloadable page size.
     */
    private volatile int inboxPageSize = 7;

    private InboxFilters inboxFilters;
    private InboxRouter inboxRouter;
    private InboxRouter mailRouter;
    private MailNotifier mailNotifier;

    private void warnAboutUnmappedCategoryTypes() {
        var unmapped = this.categories.typesWithNoPayloadMapping(this.notificationService.dataTypeRegistry());
        if (!unmapped.isEmpty()) {
            getLogger().warning(
                    "categories.yml references data types with no registered payload mapping: " + unmapped);
        }
    }

    /**
     * Registers the player-facing commands through Paper's Brigadier lifecycle event. This plugin ships
     * a {@code paper-plugin.yml}, which has no {@code commands:} block, so Brigadier is the only
     * registration path available.
     */
    @SuppressWarnings("UnstableApiUsage")
    private void registerCommands(int inboxPageSize) {
        this.preferenceDialogRouter = new PreferenceDialogRouter(
                this.messages, this, this.sinkRegistry, this.categories,
                this.notificationService.dataTypeRegistry(), this.typeNames, this.preferences);
        getServer().getPluginManager().registerEvents(
                new PreferenceQuitListener(this.preferenceDialogRouter.sessions()), this);
        InboxEntryRenderer inboxRenderer = new InboxEntryRenderer(this.notificationService.dataTypeRegistry(), getLogger());
        this.inboxFilters = new InboxFilters(this.categories, this.notificationService.dataTypeRegistry());
        this.inboxRouter = new InboxRouter(
                this.messages,
                this, this.notificationService, inboxRenderer, inboxPageSize,
                (java.util.Set<String>) null, this.inboxFilters, "notifications",
                this.messages.messageFor(MessageKeys.INBOX_TITLE),
                InboxChatRow.titleOnly(this.messages));
        // A second, mail-filtered InboxRouter instance rather than one shared router with a per-call
        // filter: the cursor and last-listed maps are per-screen state, and /mail list 2 must not make
        // /notifications read 1 resolve against the mail page.
        this.mailRouter = new InboxRouter(
                this.messages,
                this, this.notificationService, inboxRenderer, inboxPageSize,
                java.util.Set.of(MailPayload.DATA_TYPE), null, "mail",
                this.messages.messageFor(MessageKeys.MAIL_TITLE),
                new MailChatRow(this.messages, inboxRenderer::decodePayload, ZoneId.systemDefault(), Instant::now));
        getServer().getPluginManager().registerEvents(
                new InboxQuitListener(List.of(this.inboxRouter, this.mailRouter)), this);
        this.mailNotifier = new MailNotifier(this.sinkRegistry, this.preferences, this.messages, getLogger());
        MailSender mailSender = new MailSender(this.notificationService);
        // A supplier, not the instance: reload() replaces notificationDelivery with a new object.
        TestNotificationSender testSender = new TestNotificationSender(
                this.messages,
                this, this.notificationService, this.preferences, this.sinkRegistry,
                () -> this.notificationDelivery);
        // Built here, resolved per dispatch: modules register their providers during startModules(),
        // which runs after this method but before Paper fires the COMMANDS event.
        AccountLinkDispatcher linkDispatcher =
                new AccountLinkDispatcher(this.messages, this.accountLinkRegistry, getLogger());
        Executor asyncExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);
        Broadcaster broadcaster = new Broadcaster(this.messages, this.sinkRegistry, this.preferences, getLogger());
        OnlineBroadcastAudience broadcastAudience = new OnlineBroadcastAudience(this);
        // A supplier for the same reason TestNotificationSender takes one: reload() replaces
        // notificationDelivery with a new object, and the push must reach the current one.
        PersistentBroadcaster persistentBroadcaster = new PersistentBroadcaster(
                this.notificationService,
                uuid -> this.notificationDelivery.deliver(uuid),
                broadcaster, getLogger());
        // Absent when LuckPerms is not installed: --offline then explains its own unavailability rather
        // than the node disappearing, the same shape as /notifications link on a server with no module.
        BroadcastAudience offlineBroadcastAudience = LuckPermsBinding.tryCreate(this)
                .map(lookup -> (BroadcastAudience) new OfflineBroadcastAudience(getServer(), lookup))
                .orElse(null);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(
                    NotificationsCommand.create(this.messages, this.preferenceDialogRouter, this.inboxRouter,
                            this::reload, testSender,
                            linkDispatcher, asyncExecutor,
                            SendCommand.create(this.messages, this, broadcaster, persistentBroadcaster,
                                    this.customTypes)),
                    NotificationsCommand.DESCRIPTION,
                    List.of("notifs")
            );
            event.registrar().register(
                    MailCommand.create(this.messages, this, this.mailRouter, mailSender, this.mailNotifier),
                    MailCommand.DESCRIPTION
            );
            event.registrar().register(
                    BroadcastCommand.create(this.messages, this, broadcaster, persistentBroadcaster,
                            broadcastAudience, offlineBroadcastAudience, this.customTypes),
                    BroadcastCommand.DESCRIPTION
            );
        });
    }

    /**
     * Reloads {@code categories.yml} and {@code settings.yml} without a server restart:
     * {@code database.yml} is intentionally left alone, since reloading it would mean rebuilding the
     * MariaDB connection pool mid-request. Swaps the category resolver into
     * {@link #notificationDelivery} and the {@link #preferenceDialogRouter} (open dialogs keep whatever
     * category set they staged against — their category keys remain valid strings to write even if a
     * reload renamed or removed one), refreshes the configured default media and the
     * {@link JoinDeliveryListener}'s toggle and delay, and reschedules the prune task if its interval
     * changed.
     */
    public void reload(@NotNull CommandSender sender) {
        NotificationCategories newCategories;
        PluginSettings newSettings;
        try {
            newCategories = loadCategories();
            newSettings = loadPluginSettings();
            // In place, so every command and listener holding the container sees the new wording.
            reloadMessages();
            this.typeNames.load(copyDefaultsYaml("type-names"));
            this.customTypes.load(copyDefaultsYaml("notification-types"));
            loadDeliveryDefaults();
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to reload configuration.", ex);
            // Safe to read from the container: a failure here means load() was never reached, so
            // it still holds the wording the server started with.
            sender.sendMessage(this.messages.messageFor(MessageKeys.RELOAD_FAILED,
                    MessageContainer.value("error", String.valueOf(ex.getMessage()))));
            return;
        }

        // Before the category rebuild below: a newly declared type must already be mapped when the
        // merged categories are rebuilt, or it would show as uncategorized until the next reload.
        this.customTypeRegistrar.sync();

        this.categories = newCategories;
        this.preferenceDialogRouter.reloadCategories(newCategories);
        this.inboxFilters.reloadCategories(newCategories);
        this.categoryDefaultsWriter.write(this.notificationService.categoryRegistry());
        this.typeNameDefaultsWriter.write();
        this.notificationDelivery = new NotificationDelivery(
                this.database,
                this.notificationService.dataTypeRegistry(),
                this.sinkRegistry,
                this.preferences,
                getLogger()
        );
        this.preferences.reloadDefaultMedia(newSettings.defaultMedia());
        this.joinDeliveryListener.reloadSettings(
                newSettings.deliverOnJoin(), newSettings.joinDeliveryDelaySeconds());
        this.inboxPageSize = newSettings.inboxPageSize();
        this.inboxRouter.reloadPageSize(newSettings.inboxPageSize());
        this.mailRouter.reloadPageSize(newSettings.inboxPageSize());
        reschedulePruneTask(newSettings.pruneIntervalSeconds());
        warnAboutUnmappedCategoryTypes();
        warnAboutMissingConfigKeys();

        getLogger().info("Configuration reloaded by " + sender.getName());
        sender.sendMessage(this.messages.messageFor(MessageKeys.RELOAD_SUCCESS));
    }

    /**
     * Loads and initializes feature modules from {@code <dataFolder>/modules}. Module
     * failures are logged by the lifecycle manager and never fail the host plugin.
     */
    private void startModules() {
        Path moduleDir = getDataFolder().toPath().resolve(MODULES_DIR_NAME);
        try {
            Files.createDirectories(moduleDir);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING,
                    "Could not create the modules directory; skipping module loading.",
                    ex);
            return;
        }
        this.moduleLifecycleManager = new ModuleLifecycleManager<>(this,
                new ModuleLoader(moduleDir));
        try {
            this.moduleLifecycleManager.start();
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to load plugin modules.", ex);
        }
    }

    /**
     * Schedules an async task that prunes expired notifications on the given
     * interval. Runs off the main thread since it performs database I/O.
     *
     * <p>Orphaned target rows are pruned in the same pass: deleting a notification does not cascade
     * into {@code NotificationTarget}, and inbox rows now live long enough for that leak to matter.
     */
    private void schedulePruneTask(long intervalSeconds) {
        long periodTicks = Math.max(1L, intervalSeconds * 20L);
        this.pruneTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    this.notificationService.clearExpiredNotifications();
                    this.notificationService.pruneOrphanedTargets();
                },
                periodTicks,
                periodTicks
        );
        getLogger().info("Pruning expired notifications every " + intervalSeconds + "s");
    }

    /**
     * Cancels the current prune task, if any, and schedules a new one at the given interval. Used by
     * {@link #reload(CommandSender)} when {@code prune-interval-seconds} changes.
     */
    private void reschedulePruneTask(long intervalSeconds) {
        if (this.pruneTask != null) {
            this.pruneTask.cancel();
        }
        schedulePruneTask(intervalSeconds);
    }

    @Override
    public void onDisable() {
        // Shut modules down first: they may still use the service and database below.
        if (this.moduleLifecycleManager != null) {
            this.moduleLifecycleManager.stop();
            this.moduleLifecycleManager = null;
        }
        // Cancel the prune task so it cannot run against a closing database.
        getServer().getScheduler().cancelTasks(this);
        this.pruneTask = null;
        if (this.notificationService != null) {
            getServer().getServicesManager().unregisterAll(this);
            this.notificationService = null;
        }
        this.sinkRegistry = null;
        this.accountLinkRegistry = null;
        this.preferences = null;
        this.notificationDelivery = null;
        this.categories = null;
        this.categoryDefaultsWriter = null;
        this.typeNames = null;
        this.customTypes = null;
        this.customTypeRegistrar = null;
        this.typeNameDefaultsWriter = null;
        this.preferenceDialogRouter = null;
        this.inboxRouter = null;
        this.inboxFilters = null;
        this.mailRouter = null;
        this.mailNotifier = null;
        if (this.database != null) {
            try {
                this.database.close();
            } catch (IOException ex) {
                getLogger().warning("Failed to close the database cleanly: " + ex.getMessage());
            }
            this.database = null;
        }
        getLogger().info("PlayerNotifications disabled");
    }

    private DatabaseSettings loadDatabaseSettings() throws IOException {
        ConfigurationNode root = copyDefaultsYaml("database");
        DatabaseSettings settings = root.get(DatabaseSettings.class);
        if (settings == null) {
            throw new IOException("database.yml could not be deserialized into DatabaseSettings");
        }
        return settings;
    }

    private PluginSettings loadPluginSettings() throws IOException {
        ConfigurationNode root = copyDefaultsYaml("settings");
        PluginSettings settings = root.get(PluginSettings.class);
        if (settings == null) {
            throw new IOException("settings.yml could not be deserialized into PluginSettings");
        }
        return settings;
    }

    /**
     * Reloads {@code messages.yml} into the existing container, with the bundled defaults underneath
     * the operator's file — in memory only, writing nothing to disk.
     *
     * <p>{@code messages.yml} is the one config file that gets this treatment, because a missing key
     * there is not a neutral absence: {@code MessageContainer.messageFor} renders an unknown key as
     * {@code Component.text(key)}, so a message added by a later release would print {@code mail.sent}
     * to players on every existing install, and {@code MessageKeysTest} cannot catch it — it checks the
     * shipped resource, not the operator's file.
     *
     * <p>The asymmetry with the other three files is deliberate: here an absent key means "I did not
     * override this", because every key has a shipped default that is a sensible value. In
     * {@code categories.yml} an absent category means "I do not want this category", because a category
     * is a whole object the operator composes rather than a slot with a natural default.
     */
    private void reloadMessages() throws IOException {
        ConfigurationNode merged = bundledNode("messages");
        // mergeFrom fills keys ABSENT from the receiver, so the receiver is the loser: merging the
        // operator's node into the bundled one is what makes the operator win. Reversing these two
        // silently makes every shipped default override the operator's edit.
        merged.mergeFrom(copyDefaultsYaml("messages"));
        this.messages.load(merged);
    }

    private NotificationCategories loadCategories() throws IOException {
        ConfigurationNode root = copyDefaultsYaml("categories");
        NotificationCategoriesConfig config = root.get(NotificationCategoriesConfig.class);
        if (config == null) {
            throw new IOException("categories.yml could not be deserialized into NotificationCategoriesConfig");
        }
        return new NotificationCategories(config, this.notificationService.categoryRegistry(), getLogger());
    }

    /**
     * Re-reads {@code categories.yml}, re-merges it with the code registry, and swaps the result into
     * the dialog router. A failure is logged and the previous snapshot kept: a stale category view is a
     * far better outcome than a null one, which would break the preference dialogs outright.
     *
     * @param when names the trigger, so the log says which rebuild failed
     */
    private void rebuildCategories(@NotNull String when) {
        try {
            this.categories = loadCategories();
            this.preferenceDialogRouter.reloadCategories(this.categories);
            // Null-guarded rather than assumed: this runs from a registry change listener, which can
            // fire before the inbox router is built and again while the plugin is tearing down.
            if (this.inboxFilters != null) {
                this.inboxFilters.reloadCategories(this.categories);
            }
            this.categoryDefaultsWriter.write(this.notificationService.categoryRegistry());
            this.typeNameDefaultsWriter.write();
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to rebuild categories " + when + ".", ex);
        }
    }

    /**
     * Requests a category rebuild on behalf of a registration that arrived after startup.
     *
     * <p>Coalescing is the point. Listeners fire once per mutating call, so a plugin claiming five data
     * types fires five times, and each rebuild re-reads {@code categories.yml} and rewrites
     * {@code defaults/categories.yml}; without this guard a single registrant would cause five file
     * round-trips. The flag is cleared inside the scheduled task, so claims arriving after it runs
     * schedule a fresh rebuild rather than being swallowed.
     *
     * <p>Note the rebuild no longer <em>writes</em> {@code categories.yml} — no config file is
     * rewritten after its first copy (see {@link #copyDefaultsYaml}).
     *
     * <p>The rebuild is marshalled onto the main thread because it touches the dialog router, and is
     * skipped entirely once the plugin is disabled — scheduling against a disabled plugin throws, and a
     * rebuild during shutdown has nothing left to serve.
     */
    private void scheduleCategoryRebuild() {
        if (!isEnabled()) {
            return;
        }
        if (!this.categoryRebuildPending.compareAndSet(false, true)) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            this.categoryRebuildPending.set(false);
            rebuildCategories("after a late category registration");
        });
    }

    /**
     * Ensures {@code <resourceName>.yml} exists in the data folder (copying the bundled default in on
     * first run only), loads it, and returns the root node.
     *
     * <p><b>Writes nothing after that first copy.</b> This used to merge the bundled defaults back in
     * and save the result, which rewrote the operator's file on every call — at startup, on every
     * {@code /notifications reload}, and, for {@code categories.yml}, on every late-registration
     * category rebuild. Each rewrite was a Configurate re-emit, so it stripped their comments,
     * reordered their keys and normalised their quoting. The file is theirs; the plugin reads it.
     *
     * <p>What the merge used to guarantee — that an upgrade's new key reached every install — is now
     * reported instead by {@link #warnAboutMissingConfigKeys()}, and {@code messages.yml} additionally
     * gets a defaults-underlay in memory (see {@link #reloadMessages()}).
     */
    private ConfigurationNode copyDefaultsYaml(@NotNull String resourceName) throws IOException {
        String fileName = resourceName + ".yml";
        File dataFolder = getDataFolder();
        if (!dataFolder.isDirectory()) {
            Files.createDirectories(dataFolder.toPath());
        }
        File file = new File(dataFolder, fileName);
        if (!file.exists()) {
            try (InputStream inputStream = getResource(fileName);
                 FileOutputStream out = new FileOutputStream(file)) {
                if (inputStream == null) {
                    getLogger().severe("Failed to find bundled default resource: " + fileName);
                } else {
                    inputStream.transferTo(out);
                }
            }
        }

        return yamlLoader().file(file).build().load();
    }

    /**
     * Loads {@code <resourceName>.yml} from the plugin jar, touching no file on disk. Returns an empty
     * node when the resource is missing, so a packaging fault degrades to "no defaults to compare
     * against" rather than an enable failure — every caller has already loaded the operator's own file
     * successfully by the time this runs.
     */
    private @NotNull ConfigurationNode bundledNode(@NotNull String resourceName) throws IOException {
        String fileName = resourceName + ".yml";
        try (InputStream stream = getResource(fileName)) {
            if (stream == null) {
                getLogger().severe("Failed to find bundled default resource: " + fileName);
                return yamlLoader().build().createNode();
            }
            return yamlLoader()
                    .source(() -> new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
                    .build()
                    .load();
        }
    }

    /**
     * Logs one warning per config file naming any key the bundled default has and the operator's file
     * lacks.
     *
     * <p>This replaces what copy-defaults-then-merge used to do silently. Without it a key added by a
     * later release arrives as a primitive {@code 0}/{@code false} — which {@link PluginSettings}'
     * compact constructor absorbs today, but would not for a future boolean whose correct default is
     * {@code true} — or, for a {@code @Required} reference key such as {@code settings.yml}'s
     * {@code default-media}, fails deserialization and disables the plugin with nothing in the log
     * pointing at the cause.
     *
     * <p>{@code messages.yml} is excluded: it has no gaps by construction, since
     * {@link #reloadMessages()} resolves an absent key from the bundled defaults.
     *
     * <p>{@code type-names.yml}, {@code notification-types.yml} and {@code delivery-defaults.yml} are
     * excluded too: each is a partial override map keyed by the operator's own {@code dataType}s, so it
     * has no missing keys to report and every bundled example ships commented out.
     *
     * <p>Comparison only — neither file is modified, which is the entire point of the change this
     * belongs to.
     */
    /**
     * Reads {@code delivery-defaults.yml} into the preferences object, replacing whatever was there.
     *
     * <p>Never fatal: a file that cannot be read leaves the previous overrides in place and the plugin
     * resolving through {@code settings.yml}'s {@code default-media}, which is what an absent file
     * means anyway. Every per-entry rejection is warned about by {@link DeliveryDefaults#load} itself.
     */
    private void loadDeliveryDefaults() {
        try {
            this.preferences.reloadTypeDefaults(
                    DeliveryDefaults.load(copyDefaultsYaml("delivery-defaults"), getLogger()));
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to load delivery-defaults.yml; per-type delivery "
                    + "defaults are unchanged.", ex);
        }
    }

    private void warnAboutMissingConfigKeys() {
        for (String name : List.of("database", "settings", "categories")) {
            try {
                List<String> missing = ConfigKeyGaps.missingKeys(bundledNode(name), copyDefaultsYaml(name));
                if (!missing.isEmpty()) {
                    getLogger().warning(name + ".yml is missing keys added by a newer version of the "
                            + "plugin; add them by hand (the defaults are in the plugin jar): " + missing);
                }
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "Could not check " + name + ".yml for missing keys.", ex);
            }
        }
    }

    private YamlConfigurationLoader.Builder yamlLoader() {
        return YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK);
    }
}
