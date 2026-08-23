package io.github.md5sha256.playernotifications.paper.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares a bundled default config tree against the operator's own file and reports the keys the
 * bundle has and theirs lacks.
 *
 * <p>This exists because the plugin no longer merges bundled defaults back into a config file on
 * disk: the operator's file is theirs alone, comments and key order included. Dropping that merge
 * loses a real guarantee — an upgrade adding a key used to write it into every install — so the gap
 * report is what replaces it. Without one, a key added by a later release arrives as a primitive
 * {@code 0}/{@code false}, or, for a {@code @Required} reference key such as {@code settings.yml}'s
 * {@code default-media}, fails deserialization and disables the plugin, with nothing in the log
 * pointing at the cause.
 *
 * <p>It is a separate class rather than a private method on the plugin so that it can be unit
 * tested: it touches no Bukkit or Paper type, only Configurate. Keep it that way.
 *
 * <p>Comparison only — neither node is ever mutated.
 */
public final class ConfigKeyGaps {

    private ConfigKeyGaps() {
    }

    /**
     * The keys present in {@code bundled} and absent from {@code actual}, as sorted dotted paths.
     *
     * <p>The diff is one-directional and compares <em>keys</em>, never values: a key the operator
     * gave a different value is an override, which is the whole point of the file being theirs, and
     * a key they added that the bundle lacks is none of the plugin's business. Sorted so the warning
     * text is stable across runs and an operator can tell a genuinely new gap from a familiar one.
     *
     * @param bundled the tree loaded from the bundled resource in the plugin jar
     * @param actual  the tree loaded from the operator's file on disk
     * @return the missing keys, sorted; empty when the operator's file covers the bundle
     */
    public static @NotNull List<String> missingKeys(@NotNull ConfigurationNode bundled,
                                                    @NotNull ConfigurationNode actual) {
        Set<String> bundledKeys = new TreeSet<>();
        flatten("", bundled, bundledKeys);
        Set<String> actualKeys = new TreeSet<>();
        flatten("", actual, actualKeys);
        return bundledKeys.stream().filter(key -> !actualKeys.contains(key)).toList();
    }

    /**
     * Collects the leaf paths of {@code node}, mirroring {@code MessageContainer}'s own flatten so
     * that a gap here reads as the same key a message lookup would use.
     *
     * <p>A node is a leaf when its path is non-empty (the root is never a key) and it is neither a
     * map nor empty. <strong>A list is therefore a leaf too</strong>: recursing into its elements
     * would emit index paths, and an operator who trimmed one entry from {@code default-media} would
     * be warned that they are missing config keys for an ordinary edit.
     */
    private static void flatten(@NotNull String path,
                                @NotNull ConfigurationNode node,
                                @NotNull Set<String> target) {
        if (!path.isEmpty() && !node.empty() && !node.isMap()) {
            target.add(path);
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
            String key = String.valueOf(child.getKey());
            flatten(path.isEmpty() ? key : path + '.' + key, child.getValue(), target);
        }
    }
}
