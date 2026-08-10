package io.github.md5sha256.playernotifications.paper;

import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLoader;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.link.AccountLinkRegistry;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.api.processor.NotificationDisposition;
import io.github.md5sha256.playernotifications.api.render.sink.ChatSink;
import io.github.md5sha256.playernotifications.api.render.sink.DialogSink;
import io.github.md5sha256.playernotifications.paper.command.AccountLinkDispatcher;
import io.github.md5sha256.playernotifications.paper.command.MailCommand;
import io.github.md5sha256.playernotifications.paper.command.NotificationsCommand;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationPayload;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationRenderer;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationSender;
import io.github.md5sha256.playernotifications.paper.inbox.InboxEntryRenderer;
import io.github.md5sha256.playernotifications.paper.inbox.InboxQuitListener;
import io.github.md5sha256.playernotifications.paper.inbox.InboxRouter;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import io.github.md5sha256.playernotifications.paper.mail.MailRenderer;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogRouter;
import io.github.md5sha256.playernotifications.paper.preferences.PreferenceQuitListener;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.DatabaseSettings;
import io.github.md5sha256.playernotifications.core.DefaultNotificationService;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.maria.MariaDatabase;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.List;
import java.util.concurrent.Executor;
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
    @NotNull
    public NotificationCategories categories() {
        return this.categories;
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

        try {
            this.categories = loadCategories();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load categories.yml; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.accountLinkRegistry = new AccountLinkRegistry();
        this.sinkRegistry = new NotificationSinkRegistry();
        this.sinkRegistry.registerSink(new ChatSink(this));
        this.sinkRegistry.registerSink(new DialogSink(this));
        this.preferences =
                new DatabaseNotificationPreferences(mariaDatabase, pluginSettings.defaultMedia());
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

        registerCommands(pluginSettings.inboxPageSize());

        // Always registered, gated internally: /notifications reload can then flip deliver-on-join
        // without re-registering the listener. A supplier, not the instance — reload() replaces
        // notificationDelivery with a new object.
        this.joinDeliveryListener = new JoinDeliveryListener(
                this, () -> this.notificationDelivery, this.notificationService,
                pluginSettings.deliverOnJoin(), pluginSettings.joinDeliveryDelaySeconds());
        getServer().getPluginManager().registerEvents(this.joinDeliveryListener, this);

        schedulePruneTask(pluginSettings.pruneIntervalSeconds());

        // Start modules last so they can look up the registered NotificationService and register their
        // own category claims against it.
        startModules();

        // Rebuild the merged categories now that modules have had a chance to register, and swap the
        // rebuilt view into the dialog router — the same mechanism /notifications reload uses.
        try {
            this.categories = loadCategories();
            this.preferenceDialogRouter.reloadCategories(this.categories);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to rebuild categories after module startup.", ex);
        }
        warnAboutUnmappedCategoryTypes();
        getLogger().info("PlayerNotifications enabled");
    }

    /**
     * Logs a warning naming every data type declared under some category in {@code categories.yml} that
     * no registered payload mapping exists for, once feature modules have had a chance to register
     * theirs. A standing misconfiguration an operator should fix, not a startup-order race — modules
     * that register later than this check will simply be caught on the next server restart.
     */
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
                this, this.sinkRegistry, this.categories, this.notificationService.dataTypeRegistry(),
                this.preferences);
        getServer().getPluginManager().registerEvents(
                new PreferenceQuitListener(this.preferenceDialogRouter.sessions()), this);
        InboxEntryRenderer inboxRenderer = new InboxEntryRenderer(this.notificationService.dataTypeRegistry(), getLogger());
        this.inboxRouter = new InboxRouter(
                this, this.notificationService, inboxRenderer, inboxPageSize,
                null, Component.text("Notifications"));
        // A second, mail-filtered InboxRouter instance rather than one shared router with a per-call
        // filter: the cursor and last-listed maps are per-screen state, and /mail list 2 must not make
        // /notifications read 1 resolve against the mail page.
        this.mailRouter = new InboxRouter(
                this, this.notificationService, inboxRenderer, inboxPageSize,
                MailPayload.DATA_TYPE, Component.text("Mail"));
        getServer().getPluginManager().registerEvents(
                new InboxQuitListener(List.of(this.inboxRouter, this.mailRouter)), this);
        this.mailNotifier = new MailNotifier(this.sinkRegistry, this.preferences, getLogger());
        MailSender mailSender = new MailSender(this.notificationService);
        // A supplier, not the instance: reload() replaces notificationDelivery with a new object.
        TestNotificationSender testSender = new TestNotificationSender(
                this, this.notificationService, this.preferences, this.sinkRegistry,
                () -> this.notificationDelivery);
        // Built here, resolved per dispatch: modules register their providers during startModules(),
        // which runs after this method but before Paper fires the COMMANDS event.
        AccountLinkDispatcher linkDispatcher =
                new AccountLinkDispatcher(this.accountLinkRegistry, getLogger());
        Executor asyncExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(
                    NotificationsCommand.create(this.preferenceDialogRouter, this.inboxRouter,
                            this::reload, testSender,
                            linkDispatcher, asyncExecutor),
                    NotificationsCommand.DESCRIPTION,
                    List.of("notifs")
            );
            event.registrar().register(
                    MailCommand.create(this, this.mailRouter, mailSender, this.mailNotifier),
                    MailCommand.DESCRIPTION
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
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to reload configuration.", ex);
            sender.sendMessage(Component.text(
                    "Failed to reload configuration: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }

        this.categories = newCategories;
        this.preferenceDialogRouter.reloadCategories(newCategories);
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
        this.inboxRouter.reloadPageSize(newSettings.inboxPageSize());
        this.mailRouter.reloadPageSize(newSettings.inboxPageSize());
        reschedulePruneTask(newSettings.pruneIntervalSeconds());
        warnAboutUnmappedCategoryTypes();

        getLogger().info("Configuration reloaded by " + sender.getName());
        sender.sendMessage(Component.text(
                "PlayerNotifications configuration reloaded.", NamedTextColor.GREEN));
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
        this.preferenceDialogRouter = null;
        this.inboxRouter = null;
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

    private NotificationCategories loadCategories() throws IOException {
        ConfigurationNode root = copyDefaultsYaml("categories");
        NotificationCategoriesConfig config = root.get(NotificationCategoriesConfig.class);
        if (config == null) {
            throw new IOException("categories.yml could not be deserialized into NotificationCategoriesConfig");
        }
        return new NotificationCategories(config, this.notificationService.categoryRegistry(), getLogger());
    }

    /**
     * Ensures {@code <resourceName>.yml} exists in the data folder (copying the
     * bundled default on first run), then loads it, merges in any newly-added
     * default keys, saves it back, and returns the root node.
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

        YamlConfigurationLoader loader = yamlLoader().file(file).build();
        ConfigurationNode existing = loader.load();
        try (InputStream defaultStream = getResource(fileName)) {
            if (defaultStream != null) {
                YamlConfigurationLoader defaultsLoader = yamlLoader()
                        .source(() -> new BufferedReader(
                                new InputStreamReader(defaultStream, StandardCharsets.UTF_8)))
                        .build();
                existing.mergeFrom(defaultsLoader.load());
                loader.save(existing);
            }
        }
        return existing;
    }

    private YamlConfigurationLoader.Builder yamlLoader() {
        return YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK);
    }
}
