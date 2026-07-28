package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves a registered {@code dataType} to every player-facing category that claims it — a read-side
 * merge, built once at startup (and on {@code /notifications reload}), of {@code categories.yml} and the
 * programmatic {@link NotificationCategoryRegistry}. A data type no category claims resolves to
 * {@link #UNCATEGORIZED}, which is always a real, selectable category — a newly installed module's
 * notifications are configurable immediately, without an operator editing config first.
 *
 * <p>A {@code dataType} may be claimed by any number of categories; membership is a set, so there is no
 * collision to resolve. A collision on the category <em>key</em> itself (code and config both defining
 * {@code economy}) is not an error: config's label/description wins, and the collision is logged at
 * {@code fine}.
 */
public final class NotificationCategories {

    public static final String UNCATEGORIZED = "uncategorized";

    private final Map<String, Set<String>> dataTypeToCategories;
    private final Map<String, String> labels;
    private final Map<String, String> descriptions;
    private final Set<String> categoryKeys;
    private final String uncategorizedLabel;

    public NotificationCategories(@NotNull NotificationCategoriesConfig config,
                                  @NotNull NotificationCategoryRegistry registry,
                                  @NotNull Logger logger) {
        this.uncategorizedLabel = config.uncategorizedLabel();

        Map<String, String> labels = new HashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, Set<String>> dataTypeToCategories = new HashMap<>();

        for (String categoryKey : registry.categoryKeys()) {
            keys.add(categoryKey);
            labels.put(categoryKey, registry.label(categoryKey));
            descriptions.put(categoryKey, registry.description(categoryKey));
            for (String dataType : registry.dataTypesFor(categoryKey)) {
                dataTypeToCategories.computeIfAbsent(dataType, key -> new HashSet<>()).add(categoryKey);
            }
        }

        for (Map.Entry<String, NotificationCategoryDefinition> entry : config.categories().entrySet()) {
            String categoryKey = entry.getKey();
            if (keys.contains(categoryKey)) {
                logger.fine(() -> "Category '" + categoryKey
                        + "' is defined both in code and in categories.yml; using config's label/description");
            }
            keys.add(categoryKey);
            labels.put(categoryKey, entry.getValue().label());
            descriptions.put(categoryKey, entry.getValue().description());
            for (String dataType : entry.getValue().types()) {
                dataTypeToCategories.computeIfAbsent(dataType, key -> new HashSet<>()).add(categoryKey);
            }
        }

        this.categoryKeys = Set.copyOf(keys);
        this.labels = Map.copyOf(labels);
        this.descriptions = Map.copyOf(descriptions);
        Map<String, Set<String>> frozen = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : dataTypeToCategories.entrySet()) {
            frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        this.dataTypeToCategories = Map.copyOf(frozen);
    }

    /**
     * Every category (config- and code-claimed) that claims the given data type. Never empty —
     * an unclaimed data type resolves to {@code {UNCATEGORIZED}}.
     */
    @NotNull
    public Set<String> resolve(@NotNull String dataType) {
        Set<String> claimed = this.dataTypeToCategories.get(dataType);
        return claimed != null && !claimed.isEmpty() ? claimed : Set.of(UNCATEGORIZED);
    }

    /**
     * Every selectable category key, including {@link #UNCATEGORIZED}.
     */
    @NotNull
    public Set<String> categoryKeys() {
        Set<String> keys = new LinkedHashSet<>(this.categoryKeys);
        keys.add(UNCATEGORIZED);
        return Set.copyOf(keys);
    }

    /**
     * The player-facing label for a category key, falling back to the key itself if the category has
     * vanished from both config and the code registry since a player last saw it.
     */
    @NotNull
    public String label(@NotNull String categoryKey) {
        if (UNCATEGORIZED.equals(categoryKey)) {
            return this.uncategorizedLabel;
        }
        return this.labels.getOrDefault(categoryKey, categoryKey);
    }

    /**
     * The player-facing description for a category key, or an empty string for {@link #UNCATEGORIZED}
     * or a vanished category.
     */
    @NotNull
    public String description(@NotNull String categoryKey) {
        if (UNCATEGORIZED.equals(categoryKey)) {
            return "";
        }
        return this.descriptions.getOrDefault(categoryKey, "");
    }

    /**
     * Every data type claimed by some category (config or code) that the given registry has no payload
     * mapping for. Intended to be checked once at startup, after feature modules have registered their
     * payload mappings and their category claims, so an operator sees a standing misconfiguration rather
     * than a silent no-op.
     */
    @NotNull
    public Set<String> typesWithNoPayloadMapping(@NotNull NotificationDataTypeRegistry registry) {
        Set<String> unmapped = new HashSet<>();
        for (String dataType : this.dataTypeToCategories.keySet()) {
            if (registry.resolvePayloadClass(dataType).isEmpty()) {
                unmapped.add(dataType);
            }
        }
        return Set.copyOf(unmapped);
    }

    /**
     * Every member of {@code allKnownDataTypes} that {@link #resolve} claims for {@code categoryKey}.
     * For {@link #UNCATEGORIZED}, this is every data type no other category claims — the complement, not
     * a stored set — which is why the full universe of known data types must be supplied by the caller
     * (there is no other source of it here). Used by the preference dialogs' "by notification type"
     * bulk-edit fan-out.
     */
    @NotNull
    public Set<String> dataTypesForCategory(@NotNull String categoryKey, @NotNull Set<String> allKnownDataTypes) {
        Set<String> result = new HashSet<>();
        for (String dataType : allKnownDataTypes) {
            if (resolve(dataType).contains(categoryKey)) {
                result.add(dataType);
            }
        }
        return Set.copyOf(result);
    }
}
