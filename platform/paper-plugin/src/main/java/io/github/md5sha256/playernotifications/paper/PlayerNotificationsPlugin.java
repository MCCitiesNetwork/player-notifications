package io.github.md5sha256.playernotifications.paper;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.sink.ChatSink;
import io.github.md5sha256.playernotifications.api.render.sink.DialogSink;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.paper.command.NotificationsCommand;
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
import net.democracrycraft.pluginInfrastructure.modules.ModuleLifecycleManager;
import net.democracrycraft.pluginInfrastructure.modules.ModuleLoader;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
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
import java.util.logging.Level;

public final class PlayerNotificationsPlugin extends JavaPlugin {

    private static final Path MIGRATIONS_DIR = Path.of("sql/migrations");
    private static final String MODULES_DIR_NAME = "modules";

    private Database database;
    private DefaultNotificationService notificationService;
    private NotificationSinkRegistry sinkRegistry;
    private DatabaseNotificationPreferences preferences;
    private NotificationDelivery notificationDelivery;
    private NotificationCategories categories;
    private PreferenceDialogRouter preferenceDialogRouter;
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
     * Resolves a registered {@code dataType} to its player-facing category, as declared in
     * {@code categories.yml}.
     */
    @NotNull
    public NotificationCategories categories() {
        return this.categories;
    }

    @Override
    public void onEnable() {
        DatabaseSettings databaseSettings;
        PluginSettings pluginSettings;
        NotificationCategories categories;
        try {
            databaseSettings = loadDatabaseSettings();
            pluginSettings = loadPluginSettings();
            categories = loadCategories();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load configuration; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.categories = categories;

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

        this.sinkRegistry = new NotificationSinkRegistry();
        this.sinkRegistry.registerSink(new ChatSink(this));
        this.sinkRegistry.registerSink(new DialogSink(this));
        // Backs an explicit mute; not offered as a choice in the preferences dialog.
        this.sinkRegistry.registerSink(new NullSink());
        this.preferences =
                new DatabaseNotificationPreferences(mariaDatabase, pluginSettings.defaultMedia());
        this.notificationDelivery = new NotificationDelivery(
                mariaDatabase,
                this.notificationService.dataTypeRegistry(),
                this.sinkRegistry,
                this.preferences,
                this.categories,
                getLogger()
        );

        registerCommands();
        schedulePruneTask(pluginSettings.pruneIntervalSeconds());

        // Start modules last so they can look up the registered NotificationService.
        startModules();
        warnAboutUnmappedCategoryTypes();
        getLogger().info("PlayerNotifications enabled");
    }

    /**
     * Logs a warning naming every data type declared under some category in {@code categories.yml} that
     * no registered payload mapping exists for, once feature modules have had a chance to register
     * theirs. A standing misconfiguration an operator should fix, not a startup-order race — modules
     * that register later than this check will simply be caught on the next server restart.
     */
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
    private void registerCommands() {
        this.preferenceDialogRouter = new PreferenceDialogRouter(
                this, this.sinkRegistry, this.categories, this.preferences);
        getServer().getPluginManager().registerEvents(
                new PreferenceQuitListener(this.preferenceDialogRouter.sessions()), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        NotificationsCommand.create(this.preferenceDialogRouter),
                        NotificationsCommand.DESCRIPTION,
                        List.of("notifs")
                ));
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
     */
    private void schedulePruneTask(long intervalSeconds) {
        long periodTicks = Math.max(1L, intervalSeconds * 20L);
        getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> this.notificationService.clearExpiredNotifications(),
                periodTicks,
                periodTicks
        );
        getLogger().info("Pruning expired notifications every " + intervalSeconds + "s");
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
        if (this.notificationService != null) {
            getServer().getServicesManager().unregisterAll(this);
            this.notificationService = null;
        }
        this.sinkRegistry = null;
        this.preferences = null;
        this.notificationDelivery = null;
        this.categories = null;
        this.preferenceDialogRouter = null;
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
        return new NotificationCategories(config, getLogger());
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
