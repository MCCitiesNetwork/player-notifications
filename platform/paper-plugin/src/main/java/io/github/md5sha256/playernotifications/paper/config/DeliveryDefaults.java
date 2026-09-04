package io.github.md5sha256.playernotifications.paper.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads {@code delivery-defaults.yml}: a flat map of {@code dataType} → the media a player who has
 * <em>not</em> chosen for themselves receives that type on.
 *
 * <p>It is an <b>override layer</b>, not a replacement for {@code settings.yml}'s {@code default-media}.
 * A type named here overrides the global default for that type alone; a type not named here falls back
 * to it exactly as before. The global default deliberately stays: the set of {@code dataType}s is
 * open-ended — the host ships three, any feature module can register more, and
 * {@code notification-types.yml} declares more still — so this file can never be complete, and a type
 * with no entry and no global fallback would resolve to no media at all: stored, unread and silent,
 * with nothing in the log saying so.
 *
 * <p>Read with {@code childrenMap()} rather than deserialized into a {@code @ConfigSerializable}
 * record, for the reason {@code type-names.yml} is: the keys are the operator's own and unknown at
 * compile time. It is likewise excluded from {@code warnAboutMissingConfigKeys} — a partial override
 * map has no missing keys.
 *
 * <p><b>Nothing here throws.</b> A malformed entry costs that one type and warns naming the key, never
 * the file and never startup. Deciding it at load rather than at resolution is the {@code TypeNames}
 * operator-layer rule: the console line then appears at the moment the edit is read, and
 * {@code /notifications reload} is what reprints it after an attempted fix.
 *
 * <p><b>Nothing is validated against a registry.</b> A medium key and a {@code dataType} can both name
 * something that registers later — a module's sink, a module's type — so an unknown value here is left
 * alone. A medium with no registered sink is already skipped at delivery with a {@code fine} log,
 * exactly as a mistyped {@code default-media} entry is.
 */
public final class DeliveryDefaults {

    /**
     * {@code DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY}. Refused as a key here: that string is
     * already a <em>player's</em> blanket preference row, and a file entry spelled the same way would be
     * a third thing named {@code *} with its own precedence question. The single-argument
     * {@code preferredMedia(UUID)} resolves under it and so never consults this map at all.
     */
    private static final String BLANKET_KEY = "*";

    /** {@code NotificationPreferences.SILENCED_MEDIUM} — accepted, and the way to make a type opt-in. */
    private static final String SILENCED_MEDIUM = "none";

    private DeliveryDefaults() {
    }

    /**
     * Reads the root node of {@code delivery-defaults.yml}.
     *
     * <p><b>Load, not merge</b> — the caller replaces the live map wholesale, so deleting a block from
     * the file and reloading really does withdraw the override.
     *
     * @return an unmodifiable {@code dataType} → media map holding only the entries that survived
     */
    @NotNull
    public static Map<String, Set<String>> load(@NotNull ConfigurationNode root,
                                                @NotNull Logger logger) {
        Map<String, Set<String>> loaded = new HashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            Set<String> media = read(key, entry.getValue(), logger);
            if (media != null) {
                loaded.put(key, media);
            }
        }
        return Map.copyOf(loaded);
    }

    /** {@code null} when the entry is unusable; every rejection warns naming the key. */
    private static Set<String> read(@NotNull String key, @NotNull ConfigurationNode node,
                                    @NotNull Logger logger) {
        if (BLANKET_KEY.equals(key)) {
            reject(logger, key, "'" + BLANKET_KEY + "' is a player's blanket preference row, not a "
                    + "notification type. Name each type you want to route.");
            return null;
        }
        if (!node.isList()) {
            reject(logger, key, "it must be a list of media, such as a line '- chat' beneath it. Use "
                    + "the single entry '" + SILENCED_MEDIUM + "' to make the type opt-in.");
            return null;
        }
        List<String> values;
        try {
            values = node.getList(String.class, List.of());
        } catch (SerializationException e) {
            reject(logger, key, "its media could not be read as a list of names");
            return null;
        }
        Set<String> media = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                media.add(value);
            }
        }
        if (media.isEmpty()) {
            // Deliberately not read as "push nothing": an empty list is far more likely a half-finished
            // edit than an intention, and 'none' already says it unambiguously.
            reject(logger, key, "it names no medium. Remove the entry to fall back to default-media, "
                    + "or use '" + SILENCED_MEDIUM + "' to make the type opt-in.");
            return null;
        }
        return Set.copyOf(media);
    }

    private static void reject(@NotNull Logger logger, @NotNull String key, @NotNull String reason) {
        logger.log(Level.WARNING,
                "Ignoring delivery default for '" + key + "' in delivery-defaults.yml: " + reason);
    }
}
