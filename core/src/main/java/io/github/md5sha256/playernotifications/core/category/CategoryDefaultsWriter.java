package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.core.config.GeneratedYaml;
import io.leangen.geantyref.TypeToken;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Writes {@code defaults/categories.yml}: a generated snapshot of everything the code-side
 * {@link NotificationCategoryRegistry} holds, so an operator can see what a module registered and
 * reconcile it into {@code categories.yml} by hand.
 *
 * <p><b>The file is written and never read.</b> Nothing loads it, nothing merges it, and deleting it
 * changes no behaviour beyond removing the operator's reference copy until the next write. Live
 * category resolution is unchanged — {@link NotificationCategories} is still built from
 * {@code categories.yml} merged with the registry. Reading this file back as a middle layer was
 * rejected: it is regenerated from the registry on every reload, so it would be a loaded copy of state
 * already in memory, and anything the operator deleted from it would reappear on the next write.
 *
 * <p>Its shape is the {@code categories:} subtree of {@code categories.yml} and nothing else, so a
 * block copied out of it is valid where it lands. {@code uncategorized-label} is deliberately absent:
 * it is a host setting, not something a module registers, and emitting it would invite the operator to
 * copy the whole file over {@code categories.yml} rather than the one block they mean.
 *
 * <p>It is <b>generated, not a bundled resource</b> — {@code PlayerNotificationsPlugin.copyDefaultsYaml}'s
 * copy-then-merge-then-save idiom does not apply, since there is no bundled default to copy and no
 * operator edits to preserve.
 */
public final class CategoryDefaultsWriter {

    /**
     * The file's name inside {@link GeneratedYaml#DIRECTORY_NAME}. Deliberately the <em>same</em>
     * basename as the live {@code categories.yml}: the folder is what distinguishes them, which makes
     * the operator's comparison a plain {@code diff defaults/categories.yml categories.yml}.
     */
    public static final String FILE_NAME = "categories.yml";

    /**
     * Prepended verbatim to the rendered YAML. It is plain text rather than a Configurate node comment
     * because YAML comment emission is a Configurate-version-dependent capability and this needs to be
     * certain; a string prefix is.
     */
    private static final String HEADER = """
            # GENERATED FILE - DO NOT EDIT.
            #
            # This file is written by PlayerNotifications on startup and on /notifications reload, and
            # is never read back. Editing it changes nothing; your edits are overwritten on the next
            # write. Everything in this "defaults" folder works that way.
            #
            # It lists every notification category that a module or another plugin registered in code.
            # The categories.yml one folder up is the live file -- the one the plugin actually reads.
            # To make one of the blocks below visible to players, copy it by hand into the
            # "categories:" section of that file and edit it there.
            #
            # A blank "label" means a module claimed data types without ever naming its category. Those
            # are the blocks most worth copying across and giving a readable name.
            """;

    private final Path file;
    private final Logger logger;

    public CategoryDefaultsWriter(@NotNull Path file, @NotNull Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /**
     * Renders the registry to {@link #FILE_NAME} at this writer's path.
     *
     * <p>Delegates to {@link GeneratedYaml#write}, which carries the contracts this write depends on:
     * it <b>never throws</b> (an unwritable data folder must not fail a reload or a module
     * registration), its header is plain text rather than a Configurate node comment, and the write is
     * deliberately not atomic because nothing reads the file back.
     */
    public void write(@NotNull NotificationCategoryRegistry registry) {
        GeneratedYaml.write(this.file,
                HEADER,
                "categories",
                new TypeToken<Map<String, NotificationCategoryDefinition>>() {},
                snapshot(registry),
                this.logger);
    }

    /**
     * The registry's contents as the same {@link NotificationCategoryDefinition} record
     * {@code categories.yml} deserializes into, so the emitted schema cannot drift from the one the live
     * file uses.
     *
     * <p><b>Sorted, and that is load-bearing rather than cosmetic.</b> A {@link TreeMap} orders the
     * keys and each definition's {@code types} list is sorted, so two writes of the same registry are
     * byte-identical. The operator's whole workflow is diffing this file against their
     * {@code categories.yml}, and {@code HashMap}/{@code HashSet} iteration order would churn the file
     * on every reload, producing diffs that mean nothing.
     *
     * <p>A category registered only via {@link NotificationCategoryRegistry#claimDataType} carries empty
     * strings for label and description, and is emitted rather than skipped: it is precisely the case
     * the operator most needs to see and name.
     *
     * <p>Package-private — it exists as a seam for the tests, which assert on the record rather than on
     * parsed YAML.
     */
    static @NotNull Map<String, NotificationCategoryDefinition> snapshot(
            @NotNull NotificationCategoryRegistry registry) {
        Map<String, NotificationCategoryDefinition> snapshot = new TreeMap<>();
        for (String key : registry.categoryKeys()) {
            snapshot.put(key, new NotificationCategoryDefinition(
                    registry.label(key),
                    registry.description(key),
                    registry.dataTypesFor(key).stream().sorted().toList()));
        }
        return snapshot;
    }
}
