package io.github.md5sha256.playernotifications.paper.customtype;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The notification types an operator declared in {@code notification-types.yml} — the one way to
 * bring a {@code dataType} into existence without writing and shipping a feature module.
 *
 * <p>A flat map at the root, key → {@code {title, display-name}}, read with {@code childrenMap()}
 * rather than deserialized into a {@code @ConfigSerializable} record for the reason
 * {@code type-names.yml} is: the keys are the operator's own and are unknown at compile time.
 *
 * <p><b>The map is final and reloaded in place, never replaced</b>, so every holder takes this object
 * once at construction and {@code /notifications reload} reaches all of them — the idiom
 * {@code TypeNames}, {@code MessageContainer} and {@code DatabaseNotificationPreferences} already
 * use here. {@link #load} collects into a local map and swaps it in with {@code putAll} +
 * {@code retainAll} rather than {@code clear()}-then-fill, so a render on another thread mid-reload
 * sees the old declaration or the new one, never an empty map.
 *
 * <p><b>Nothing here throws.</b> A malformed entry costs that one type and warns naming the key,
 * never the file and never startup. Deciding it at load rather than at render is the
 * {@code TypeNames} operator-layer rule: the console line then appears at the moment the edit is
 * read, and a reload is what reprints it after an attempted fix.
 *
 * <p>The two malformed cases are not symmetric. A bad <b>title</b> drops the whole declaration —
 * there is no further layer to fall through to and a notification with no title cannot render on any
 * medium. A bad <b>display-name</b> drops only the name: {@code TypeNames} falls through to the
 * title-cased key, so the type still works and the operator loses a label rather than a feature.
 */
public final class CustomNotificationTypes {

    /**
     * {@code PlayerNotificationPreference.dataType} is {@code VARCHAR(64)}, so a longer key would
     * declare a type whose preference row cannot exist — the player could receive it and never be
     * able to silence it.
     */
    private static final int MAX_KEY_LENGTH = 64;

    /**
     * A {@code dataType} is a registry key shared with module authors, an inbox placeholder and a
     * Discord select option. A key carrying spaces, capitals or markup renders as itself in all
     * three, so the rule is the one module authors already follow by convention.
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9._-]+");

    private static final String TITLE_KEY = "title";
    private static final String DISPLAY_NAME_KEY = "display-name";

    private final Logger logger;

    /** Never reassigned — see the class javadoc. */
    private final Map<String, DeclaredNotificationType> declarations = new ConcurrentHashMap<>();

    public CustomNotificationTypes(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Replaces the declarations wholesale from the root node of {@code notification-types.yml}.
     *
     * <p><b>Load, not merge</b> — a key absent from the new node stops being declared, so deleting a
     * block from the file and reloading really does withdraw the type. Never throws.
     */
    public void load(@NotNull ConfigurationNode root) {
        Map<String, DeclaredNotificationType> loaded = new HashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            DeclaredNotificationType declared = read(key, entry.getValue());
            if (declared != null) {
                loaded.put(key, declared);
            }
        }
        this.declarations.putAll(loaded);
        this.declarations.keySet().retainAll(loaded.keySet());
    }

    /** {@code null} when the declaration is unusable; every rejection warns naming the key. */
    @Nullable
    private DeclaredNotificationType read(@NotNull String key, @NotNull ConfigurationNode node) {
        if (key.length() > MAX_KEY_LENGTH) {
            reject(key, "a notification type key may be at most " + MAX_KEY_LENGTH + " characters");
            return null;
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            reject(key, "a notification type key may only contain lower-case letters, digits, "
                    + "'.', '-' and '_'");
            return null;
        }
        if (!node.isMap()) {
            reject(key, "it must be a block with a '" + TITLE_KEY + "', not a single value");
            return null;
        }
        String title = node.node(TITLE_KEY).getString();
        if (title == null || title.isBlank()) {
            reject(key, "it has no '" + TITLE_KEY + "', and a notification needs one on every medium");
            return null;
        }
        if (!parses(title)) {
            reject(key, "its '" + TITLE_KEY + "' is not valid MiniMessage");
            return null;
        }
        String displayName = node.node(DISPLAY_NAME_KEY).getString();
        if (displayName != null && (displayName.isBlank() || !parses(displayName))) {
            // Only the name is dropped: the type still works, named by type-names.yml or its key.
            this.logger.log(Level.WARNING, "Ignoring the '" + DISPLAY_NAME_KEY + "' of notification "
                    + "type '" + key + "' in notification-types.yml: it is not valid MiniMessage");
            displayName = null;
        }
        return new DeclaredNotificationType(key, title, displayName);
    }

    private boolean parses(@NotNull String value) {
        try {
            MiniMessage.miniMessage().deserialize(value);
            return true;
        } catch (ParsingException ex) {
            return false;
        }
    }

    private void reject(@NotNull String key, @NotNull String reason) {
        this.logger.log(Level.WARNING,
                "Ignoring notification type '" + key + "' in notification-types.yml: " + reason);
    }

    /** The declaration for {@code key}, or empty when the operator declared no such type. */
    @NotNull
    public Optional<DeclaredNotificationType> get(@NotNull String key) {
        return Optional.ofNullable(this.declarations.get(key));
    }

    /** Every currently declared {@code dataType}. */
    @NotNull
    public Set<String> keys() {
        return Set.copyOf(this.declarations.keySet());
    }

    /** The raw MiniMessage title for {@code key}, or empty when no such type is declared. */
    @NotNull
    public Optional<String> title(@NotNull String key) {
        return get(key).map(DeclaredNotificationType::title);
    }
}
