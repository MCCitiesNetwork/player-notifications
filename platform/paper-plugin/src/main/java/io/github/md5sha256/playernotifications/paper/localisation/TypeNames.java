package io.github.md5sha256.playernotifications.paper.localisation;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a {@code dataType} registry key to the name a player should see.
 *
 * <p>Three layers, consulted in order on <em>every</em> call:
 *
 * <ol>
 *   <li>the operator's {@code type-names.yml} entry, read by {@link #load(ConfigurationNode)};</li>
 *   <li>the module default registered on {@link NotificationDataTypeRegistry#displayName(String)};</li>
 *   <li>{@link #titleCase(String)} over the registry key itself.</li>
 * </ol>
 *
 * <p>Both configured layers are <b>MiniMessage</b>, matching {@code messages.yml}; the fallback is
 * plain text. There is no frozen snapshot — a module registering a name after startup is picked up by
 * the next render, which is why this needs neither a change listener nor a rebuild the way the
 * category registry does.
 *
 * <p><b>The override map is final and reloaded in place, never replaced.</b> Every holder takes this
 * object once at construction, so {@code /notifications reload} reaches all of them with no
 * re-registration — the idiom {@code MessageContainer} and
 * {@code DatabaseNotificationPreferences.reloadDefaultMedia} already use here. It is a
 * {@link ConcurrentHashMap}, so a read racing a reload is a stale read at worst, never a corrupt map.
 *
 * <p><b>Nothing here throws.</b> A missing, blank or malformed value falls through to the next layer,
 * because a bad tag in a hand-written file must cost one label rather than break the preference
 * screen — the same trade {@code MessageContainer.messageFor} makes by rendering a broken template as
 * its own key. The two malformed cases are warned about differently on purpose:
 *
 * <ul>
 *   <li>An <b>operator</b> value is parse-checked in {@link #load(ConfigurationNode)} and dropped if
 *       it fails, with one {@code WARNING} naming the key. Warning at render instead would reprint the
 *       same line every time a preference screen opened and bury it; deciding it at load means the
 *       console line appears at the moment the edit is read, and a reload is what reprints it after an
 *       attempted fix. Because load already rejected anything unparseable, {@link #name(String)} needs
 *       no {@code try} for this layer at all.</li>
 *   <li>A <b>module</b> default is a code bug in someone else's jar and cannot be validated at load,
 *       since a module may register after the file is read. It is caught at render, falls through to
 *       the title-cased key, and is warned <b>once per {@code dataType}</b> — guarded by a set that
 *       {@link #load(ConfigurationNode)} clears, so a reload re-reports whatever is still broken.</li>
 * </ul>
 *
 * <p>A <em>blank</em> value at either layer falls through <b>silently</b>: emptying an entry is how an
 * operator deletes an override, not a mistake worth a console line.
 */
public final class TypeNames {

    private final NotificationDataTypeRegistry registry;
    private final Logger logger;

    /**
     * Operator overrides, raw MiniMessage, only ever holding values that already parsed once.
     * Never reassigned — see the class javadoc.
     */
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    /**
     * {@code dataType}s whose <em>module</em> default has already been reported as unparseable.
     * Cleared by {@link #load(ConfigurationNode)} so a reload re-reports a still-broken jar.
     */
    private final Set<String> warnedModuleDefaults = ConcurrentHashMap.newKeySet();

    public TypeNames(@NotNull NotificationDataTypeRegistry registry, @NotNull Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    /**
     * Replaces the override map wholesale from the root node of {@code type-names.yml}, and clears the
     * warned-once set.
     *
     * <p><b>Load, not merge</b> — a key absent from the new node stops overriding, so deleting a line
     * from the file and reloading really does undo the rename. This mirrors
     * {@code MessageContainer.load}, including its rule that a structurally odd node is skipped rather
     * than allowed to fail the whole reload: a non-scalar value here costs one wrong label.
     *
     * <p>Every remaining value is parse-checked and dropped if it fails, so one bad entry never costs
     * the rest of the file. Never throws.
     *
     * <p>The new values are collected first and then swapped in with {@code putAll} + {@code retainAll}
     * rather than {@code clear()}-then-fill, so the map is <b>never observably empty</b>: a preference
     * screen opening on another thread mid-reload sees either the old override or the new one, never a
     * title-cased fallback that flickers back. {@code MessageContainer.load} takes the same care for
     * the same reason.
     */
    public void load(@NotNull ConfigurationNode root) {
        Map<String, String> loaded = new HashMap<>();
        this.warnedModuleDefaults.clear();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            ConfigurationNode child = entry.getValue();
            if (child.isMap() || child.isList()) {
                // a structurally odd node: skipped silently, as MessageContainer.load does.
                continue;
            }
            String key = String.valueOf(entry.getKey());
            String value = child.getString();
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                MiniMessage.miniMessage().deserialize(value);
            } catch (ParsingException ex) {
                this.logger.log(Level.WARNING, "Ignoring type name override for '" + key
                        + "' in type-names.yml: it is not valid MiniMessage (" + ex.getMessage() + ")");
                continue;
            }
            loaded.put(key, value);
        }
        this.overrides.putAll(loaded);
        this.overrides.keySet().retainAll(loaded.keySet());
    }

    /**
     * The display name for a {@code dataType}, resolved through the chain in the class javadoc. Never
     * throws.
     */
    @NotNull
    public Component name(@NotNull String dataType) {
        String override = this.overrides.get(dataType);
        if (override != null) {
            // load() already proved this parses, so no try is needed here.
            return MiniMessage.miniMessage().deserialize(override);
        }
        Optional<String> moduleDefault = this.registry.displayName(dataType);
        if (moduleDefault.isPresent()) {
            String value = moduleDefault.get();
            if (!value.isBlank()) {
                try {
                    return MiniMessage.miniMessage().deserialize(value);
                } catch (ParsingException ex) {
                    if (this.warnedModuleDefaults.add(dataType)) {
                        this.logger.log(Level.WARNING, "The module default display name for data type '"
                                + dataType + "' is not valid MiniMessage (" + ex.getMessage()
                                + "); falling back to the title-cased key");
                    }
                }
            }
        }
        return Component.text(titleCase(dataType));
    }

    /**
     * {@link #name(String)} flattened to plain text, for surfaces that cannot render a
     * {@link Component} — a Discord select option label, say. The same trade
     * {@code NotificationSink#displayName} already makes there: colour is dropped, the words survive.
     */
    @NotNull
    public String plainName(@NotNull String dataType) {
        return PlainTextComponentSerializer.plainText().serialize(name(dataType));
    }

    /**
     * The raw operator override for a {@code dataType}, if the loaded file supplied a usable one.
     *
     * <p>Exists for the generated {@code defaults/type-names.yml}, which marks which entries are the
     * operator's own; nothing in the resolution chain reads it.
     */
    @NotNull
    public Optional<String> override(@NotNull String dataType) {
        return Optional.ofNullable(this.overrides.get(dataType));
    }

    /**
     * Title-cases a registry key: {@code '-'} and {@code '_'} separate words, each word is capitalized,
     * and words are rejoined with spaces.
     *
     * <p>A registry key such as {@code essentials-mail} is an identifier meant for module authors, and
     * a player reading a checkbox list has no way to know it is the same thing as the "Essentials Mail"
     * named everywhere else.
     *
     * <p>A deliberate third copy of the helper on {@code NotificationSink} and
     * {@code AccountLinkProvider} — those live in {@code api} and neither should become public API for
     * the sake of fifteen lines, which is the reasoning their own javadoc already records. It lives
     * here rather than in {@code PreferenceDialogs} so that {@code paper}'s copy stays at one now that
     * two callers want it.
     */
    @NotNull
    public static String titleCase(@NotNull String key) {
        StringBuilder builder = new StringBuilder(key.length());
        boolean startOfWord = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-' || c == '_') {
                builder.append(' ');
                startOfWord = true;
                continue;
            }
            builder.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = false;
        }
        String titled = builder.toString();
        return titled.isEmpty() ? key : titled;
    }
}
