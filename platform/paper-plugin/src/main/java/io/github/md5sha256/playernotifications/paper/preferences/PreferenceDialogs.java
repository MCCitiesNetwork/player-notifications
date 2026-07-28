package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Helpers shared by the five preference dialog screens and their router: session loading, labels, and
 * the callback options every button uses.
 */
final class PreferenceDialogs {

    /**
     * How long a dialog button stays clickable after the dialog is shown. A dialog left open past this
     * makes its buttons inert; the player simply reopens it.
     */
    static final Duration CALLBACK_LIFETIME = Duration.ofHours(1);

    private PreferenceDialogs() {
    }

    @NotNull
    static ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(CALLBACK_LIFETIME)
                .build();
    }

    static void message(@NotNull Plugin plugin, @NotNull Player player, @NotNull Component component) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(component);
            }
        });
    }

    /**
     * The media offered as checkboxes/buttons: every registered medium except {@link NullSink}, whose
     * meaning is already carried by an empty selection. Sorted so dialog row order is stable between
     * openings.
     */
    @NotNull
    static List<String> selectableMedia(@NotNull NotificationSinkRegistry sinkRegistry) {
        Set<String> sorted = new TreeSet<>(sinkRegistry.registeredMedia());
        sorted.remove(NullSink.MEDIUM_KEY);
        return List.copyOf(sorted);
    }

    /**
     * Every selectable category key (including {@link NotificationCategories#UNCATEGORIZED}), sorted so
     * dialog row order is stable between openings.
     */
    @NotNull
    static List<String> sortedCategoryKeys(@NotNull NotificationCategories categories) {
        return List.copyOf(new TreeSet<>(categories.categoryKeys()));
    }

    /**
     * Every known data type, sorted by its primary category's label (see {@link #primaryCategoryFor})
     * then by the data type itself, so the "by delivery method" editor's flat checkbox list reads as
     * grouped by category even though the dialog API has no true section headers.
     */
    @NotNull
    static List<String> sortedDataTypes(@NotNull NotificationCategories categories,
                                        @NotNull NotificationDataTypeRegistry dataTypeRegistry) {
        List<String> dataTypes = new ArrayList<>(dataTypeRegistry.dataTypes());
        dataTypes.sort(Comparator
                .comparing((String dataType) -> categories.label(primaryCategoryFor(categories, dataType)))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(dataTypes);
    }

    /**
     * The category a data type is grouped under for display purposes when it's claimed by more than
     * one — the alphabetically-first category key it resolves to. Deterministic, not meaningful beyond
     * sorting/labeling.
     */
    @NotNull
    static String primaryCategoryFor(@NotNull NotificationCategories categories, @NotNull String dataType) {
        return new TreeSet<>(categories.resolve(dataType)).first();
    }

    @NotNull
    static Component mediumLabel(@NotNull NotificationSinkRegistry sinkRegistry, @NotNull String medium) {
        return sinkRegistry.getSink(medium)
                .map(NotificationSink::displayName)
                .orElseGet(() -> Component.text(medium));
    }

    @NotNull
    static Component categoryLabel(@NotNull NotificationCategories categories, @NotNull String category) {
        return Component.text(categories.label(category));
    }

    /**
     * A data type's row label in the "by delivery method" editor: its primary category's label,
     * prefixed for readability, followed by the raw data type key.
     */
    @NotNull
    static Component dataTypeLabel(@NotNull NotificationCategories categories, @NotNull String dataType) {
        String category = primaryCategoryFor(categories, dataType);
        return Component.text(categories.label(category) + ": " + dataType);
    }

    /**
     * A dialog input key for the row at the given position under the given prefix. Positional rather
     * than derived from the medium/category key: those keys are arbitrary strings, so any sanitising
     * transform risks two distinct keys colliding onto one input key. The dialog builder keeps a
     * key-to-value map alongside these.
     */
    @NotNull
    static String inputKey(@NotNull String prefix, int index) {
        return prefix + "_" + index;
    }

    /**
     * Resolves the player's current {@link PreferenceEditSession}, loading it from the database off the
     * main thread if none is currently staged, then invokes {@code onLoaded} back on the main thread.
     * Safe to call from a command executor or a dialog button callback.
     */
    static void withSession(@NotNull Plugin plugin,
                            @NotNull PreferenceSessionManager sessions,
                            @NotNull NotificationDataTypeRegistry dataTypeRegistry,
                            @NotNull DatabaseNotificationPreferences preferences,
                            @NotNull Player player,
                            @NotNull Consumer<PreferenceEditSession> onLoaded) {
        var uuid = player.getUniqueId();
        var existing = sessions.get(uuid);
        if (existing.isPresent()) {
            onLoaded.accept(existing.get());
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> dataTypes = dataTypeRegistry.dataTypes();
            Map<String, Set<String>> effective = preferences.effectiveMediaByDataType(uuid, dataTypes);
            Set<String> explicitAtLoad = preferences.explicitlyConfiguredDataTypes(uuid, dataTypes);
            Set<String> fallback = preferences.preferredMedia(uuid, DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY);
            PreferenceEditSession session = sessions.getOrCreate(uuid, () ->
                    new PreferenceEditSession(uuid, effective, explicitAtLoad, fallback, Instant.now()));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    onLoaded.accept(session);
                }
            });
        });
    }
}
