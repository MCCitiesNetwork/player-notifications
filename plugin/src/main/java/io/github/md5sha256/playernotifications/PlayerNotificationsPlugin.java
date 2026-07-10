package io.github.md5sha256.playernotifications;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.core.DatabaseSettings;
import io.github.md5sha256.playernotifications.core.DefaultNotificationService;
import io.github.md5sha256.playernotifications.core.database.Database;
import io.github.md5sha256.playernotifications.core.database.maria.MariaDatabase;
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
import java.util.logging.Level;

public final class PlayerNotificationsPlugin extends JavaPlugin {

    private static final Path MIGRATIONS_DIR = Path.of("sql/migrations");

    private Database database;
    private NotificationService notificationService;

    @Override
    public void onEnable() {
        DatabaseSettings settings;
        try {
            settings = loadDatabaseSettings();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to load database configuration; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MariaDatabase mariaDatabase = new MariaDatabase(settings, getLogger());
        this.database = mariaDatabase;
        try {
            mariaDatabase.initializeSchema(MIGRATIONS_DIR);
        } catch (IOException | SQLException ex) {
            getLogger().log(Level.SEVERE, "Database schema migration failed; disabling plugin.", ex);
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
        getLogger().info("PlayerNotifications enabled");
    }

    @Override
    public void onDisable() {
        if (this.notificationService != null) {
            getServer().getServicesManager().unregisterAll(this);
            this.notificationService = null;
        }
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
