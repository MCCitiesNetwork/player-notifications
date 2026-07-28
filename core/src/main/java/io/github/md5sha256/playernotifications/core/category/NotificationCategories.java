package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves a registered {@code dataType} to the player-facing category it belongs to, as declared in
 * {@code categories.yml}. A data type no category claims resolves to {@link #UNCATEGORIZED}, which is
 * always a real, selectable category — a newly installed module's notifications are configurable
 * immediately, without an operator editing config first.
 *
 * <p>A data type claimed by two categories is a config error: the first category in declaration order
 * wins, and the collision is logged as a warning.
 */
public final class NotificationCategories {

    public static final String UNCATEGORIZED = "uncategorized";

    private final Map<String, String> dataTypeToCategory;
    private final Map<String, NotificationCategoryDefinition> definitions;
    private final String uncategorizedLabel;

    public NotificationCategories(@NotNull NotificationCategoriesConfig config, @NotNull Logger logger) {
        this.uncategorizedLabel = config.uncategorizedLabel();
        this.definitions = Map.copyOf(config.categories());

        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<String, NotificationCategoryDefinition> entry : config.categories().entrySet()) {
            String categoryKey = entry.getKey();
            for (String dataType : entry.getValue().types()) {
                String existing = mapping.putIfAbsent(dataType, categoryKey);
                if (existing != null) {
                    logger.warning("Data type '" + dataType + "' is claimed by both category '" + existing
                            + "' and '" + categoryKey + "'; keeping '" + existing + "'");
                }
            }
        }
        this.dataTypeToCategory = Map.copyOf(mapping);
    }

    /**
     * The category the given data type belongs to, or {@link #UNCATEGORIZED} if no category claims it.
     */
    @NotNull
    public String resolve(@NotNull String dataType) {
        return this.dataTypeToCategory.getOrDefault(dataType, UNCATEGORIZED);
    }

    /**
     * Every selectable category key, including {@link #UNCATEGORIZED}.
     */
    @NotNull
    public Set<String> categoryKeys() {
        Set<String> keys = new LinkedHashSet<>(this.definitions.keySet());
        keys.add(UNCATEGORIZED);
        return Set.copyOf(keys);
    }

    /**
     * The player-facing label for a category key, falling back to the key itself if the category has
     * vanished from config since a player last saw it.
     */
    @NotNull
    public String label(@NotNull String categoryKey) {
        if (UNCATEGORIZED.equals(categoryKey)) {
            return this.uncategorizedLabel;
        }
        NotificationCategoryDefinition definition = this.definitions.get(categoryKey);
        return definition != null ? definition.label() : categoryKey;
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
        NotificationCategoryDefinition definition = this.definitions.get(categoryKey);
        return definition != null ? definition.description() : "";
    }

    /**
     * Every data type declared under some category in config that the given registry has no payload
     * mapping for. Intended to be checked once at startup, after feature modules have registered their
     * payload mappings, so an operator sees a standing misconfiguration rather than a silent no-op.
     */
    @NotNull
    public Set<String> typesWithNoPayloadMapping(@NotNull NotificationDataTypeRegistry registry) {
        Set<String> unmapped = new HashSet<>();
        for (String dataType : this.dataTypeToCategory.keySet()) {
            if (registry.resolvePayloadClass(dataType).isEmpty()) {
                unmapped.add(dataType);
            }
        }
        return Set.copyOf(unmapped);
    }
}
