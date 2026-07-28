package io.github.md5sha256.playernotifications.discord;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copy-defaults-then-merge config loading for a feature module.
 *
 * <p>This is the same idiom as {@code PlayerNotificationsPlugin.copyDefaultsYaml}, reproduced rather
 * than reused: that helper is private and resolves its bundled default through the <em>host</em>
 * plugin's {@code getResource}, which cannot see a resource inside a module jar.
 */
public final class ModuleConfigs {

    private ModuleConfigs() {
    }

    /**
     * Ensures {@code file} exists (writing the bundled default on first run), loads it, merges in
     * any newly-added default keys, saves it back, and returns the root node. An operator's own
     * values always win over the defaults; merging only fills in keys they do not have yet.
     *
     * @param file           where the module's config lives in the data folder
     * @param bundledDefault opens the bundled default resource; may yield {@code null} if absent
     */
    public static @NotNull ConfigurationNode load(
            @NotNull Path file, @NotNull ResourceSupplier bundledDefault) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }

        if (!Files.exists(file)) {
            try (InputStream defaults = bundledDefault.open()) {
                if (defaults != null) {
                    try (OutputStream out = Files.newOutputStream(file)) {
                        defaults.transferTo(out);
                    }
                }
            }
        }

        YamlConfigurationLoader loader = yamlLoader().path(file).build();
        ConfigurationNode existing = loader.load();
        try (InputStream defaults = bundledDefault.open()) {
            if (defaults != null) {
                YamlConfigurationLoader defaultsLoader = yamlLoader()
                        .source(() -> new BufferedReader(
                                new InputStreamReader(defaults, StandardCharsets.UTF_8)))
                        .build();
                existing.mergeFrom(defaultsLoader.load());
                loader.save(existing);
            }
        }
        return existing;
    }

    private static YamlConfigurationLoader.Builder yamlLoader() {
        return YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK);
    }

    /**
     * Opens the bundled default resource. A plain {@link java.util.function.Supplier} cannot declare
     * {@link IOException}.
     */
    @FunctionalInterface
    public interface ResourceSupplier {
        @Nullable InputStream open() throws IOException;
    }
}
