package io.github.md5sha256.playernotifications.core.config;

import io.leangen.geantyref.TypeToken;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders one value as a YAML document under a single root key, prefixed with a plain-text header, and
 * writes it to a file.
 *
 * <p>It exists because there are now <b>two</b> generated files — {@code defaults/categories.yml} and
 * the forthcoming {@code defaults/type-names.yml} — and they differ only in their header, their root
 * key and the type of the value under that key. Everything else about writing them, including the
 * three contracts below, is identical, so it lives here once rather than being copied per file.
 *
 * <p>A generated file of this kind is <b>written and never read</b>: nothing loads it back, so its
 * only consumer is an operator reading or diffing it by hand. That is what makes the failure and
 * atomicity decisions below acceptable.
 */
public final class GeneratedYaml {

    /**
     * The data-folder subdirectory every generated file lives in.
     *
     * <p>A folder rather than a {@code -defaults} filename suffix, so that each generated file shares
     * its live counterpart's basename: the operator's whole workflow is comparing the two, and
     * {@code diff defaults/categories.yml categories.yml} says what it does without either name being
     * decorated. It also keeps the data folder's top level to files the operator owns and the plugin
     * reads, with everything it writes and never reads gathered in one place.
     */
    public static final String DIRECTORY_NAME = "defaults";

    private GeneratedYaml() {
    }

    /**
     * Writes {@code content} to {@code file} as {@code header} followed by a YAML document whose single
     * root key is {@code rootKey}.
     *
     * <p>The header is prepended verbatim as <b>plain text rather than a Configurate node comment</b>,
     * because YAML comment emission is a Configurate-version-dependent capability and this needs to be
     * certain; a string prefix is.
     *
     * <p><b>Never throws.</b> Failing to write an operator's reference copy must not fail a reload or a
     * module registration, so an {@link IOException} — including the {@code ConfigurateException}
     * subtype the render itself can raise — is logged at {@link Level#WARNING} naming the path and
     * swallowed. An unwritable data folder degrades to "no reference copy", not to a broken reload.
     *
     * <p>The write is deliberately not atomic: nothing reads the file, so a torn write costs the
     * operator one reload to correct, which does not justify the extra failure mode of a temp file.
     *
     * @param type the token for {@code content}'s type, needed because Configurate cannot recover a
     *             generic value's type at runtime
     */
    public static <T> void write(@NotNull Path file,
                                 @NotNull String header,
                                 @NotNull String rootKey,
                                 @NotNull TypeToken<T> type,
                                 @NotNull T content,
                                 @NotNull Logger logger) {
        try {
            StringWriter out = new StringWriter();
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .nodeStyle(NodeStyle.BLOCK)
                    .sink(() -> new BufferedWriter(out))
                    .build();
            ConfigurationNode root = loader.createNode();
            root.node(rootKey).set(type, content);
            loader.save(root);
            // The `defaults` folder is not created anywhere else, so the first write on a fresh
            // install has to make it. Null-guarded because a bare relative path has no parent.
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, header + out);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to write " + file, ex);
        }
    }
}
