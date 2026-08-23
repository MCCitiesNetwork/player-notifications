package io.github.md5sha256.playernotifications.paper.localisation;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.core.config.GeneratedYaml;
import io.leangen.geantyref.TypeToken;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Writes {@code type-names-defaults.yml}: every registered {@code dataType} alongside the display name
 * it would get if the operator changed nothing, so they can see what is renameable and what it
 * currently says.
 *
 * <p><b>Written and never read</b>, the same contract {@code CategoryDefaultsWriter} carries — nothing
 * loads it, and deleting it costs only the reference copy until the next write. The live answer comes
 * from {@link TypeNames}, whose operator layer is {@code type-names.yml}.
 *
 * <p><b>It lists every registered type, not only those a module named.</b> This diverges from
 * {@code categories-defaults.yml}, which dumps only what code registered, and the divergence is the
 * point: this file answers "what can I rename, and what does it say now", and a module-only dump would
 * omit exactly the types most worth renaming — the ones with an ugly key and nobody supplying a name.
 * A type is "registered" when it has a payload mapping; a display name alone does not qualify it, for
 * the reason {@link NotificationDataTypeRegistry#displayName} records.
 *
 * <p><b>Provenance lives in the header, not in per-entry comments.</b> Configurate's YAML comment
 * emission is version-dependent, and the body has to stay a flat map anyway so a line can be copied
 * straight into {@code type-names.yml} — splitting it into {@code from-modules} / {@code title-cased}
 * sections would make every copied line need un-nesting first. So the header carries three sorted
 * lists: which names a module supplied, which are title-cased guesses, and which the operator has
 * already overridden.
 *
 * <p>Keys sorted and the header's lists sorted, so two writes of unchanged state are byte-identical —
 * the operator's workflow is diffing this file, and unstable ordering would make every reload produce
 * a meaningless diff.
 */
public final class TypeNameDefaultsWriter {

    public static final String FILE_NAME = "type-names-defaults.yml";

    private final Path file;
    private final NotificationDataTypeRegistry registry;
    private final TypeNames typeNames;
    private final Logger logger;

    public TypeNameDefaultsWriter(@NotNull Path file,
                                  @NotNull NotificationDataTypeRegistry registry,
                                  @NotNull TypeNames typeNames,
                                  @NotNull Logger logger) {
        this.file = file;
        this.registry = registry;
        this.typeNames = typeNames;
        this.logger = logger;
    }

    /**
     * Renders the current registry to {@link #FILE_NAME}. Never throws — see
     * {@link GeneratedYaml#write}, which carries that contract along with the plain-text header and
     * the deliberately non-atomic write.
     */
    public void write() {
        GeneratedYaml.write(this.file,
                header(),
                "types",
                new TypeToken<Map<String, String>>() {},
                defaults(),
                this.logger);
    }

    /**
     * Each registered {@code dataType} mapped to its default name: the module's if one was supplied,
     * else the title-cased key.
     *
     * <p>Deliberately <b>not</b> {@link TypeNames#name}, which would resolve the operator's own
     * override and echo it back here as though a module had supplied it — destroying the one thing
     * this file is for, which is showing what the defaults are so an override can be compared against
     * them.
     */
    @NotNull
    Map<String, String> defaults() {
        Map<String, String> defaults = new TreeMap<>();
        for (String dataType : this.registry.dataTypes()) {
            defaults.put(dataType,
                    this.registry.displayName(dataType).orElseGet(() -> TypeNames.titleCase(dataType)));
        }
        return defaults;
    }

    @NotNull
    private String header() {
        List<String> fromModules = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        List<String> overridden = new ArrayList<>();
        for (String dataType : new TreeSet<>(this.registry.dataTypes())) {
            if (this.registry.displayName(dataType).isPresent()) {
                fromModules.add(dataType);
            } else {
                fallbacks.add(dataType);
            }
            if (this.typeNames.override(dataType).isPresent()) {
                overridden.add(dataType);
            }
        }
        return """
                # GENERATED FILE - DO NOT EDIT.
                #
                # Written by PlayerNotifications on startup and on /notifications reload, and never read
                # back. Editing it changes nothing; your edits are overwritten on the next write.
                #
                # Every notification type this server knows about, with the name players see if you
                # change nothing. To rename one, copy its line into type-names.yml and edit it there.
                # Values are MiniMessage, so a name may carry colour: "<gold>Personal Mail</gold>".
                #
                # Supplied by a module: %s
                # Title-cased fallback (no module supplied a name): %s
                # Already overridden in type-names.yml: %s
                """.formatted(list(fromModules), list(fallbacks), list(overridden));
    }

    /** Renders one header list, naming the empty case rather than trailing off after the colon. */
    @NotNull
    private static String list(@NotNull List<String> values) {
        return values.isEmpty() ? "(none)" : String.join(", ", values);
    }
}
