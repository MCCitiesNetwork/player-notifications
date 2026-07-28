package io.github.md5sha256.playernotifications.api.category;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link NotificationCategoryRegistry}. Not thread-confined — like
 * {@code NotificationDataTypeRegistry}, its backing maps are synchronized so registration calls from
 * different module class loaders at startup cannot race.
 */
public final class DefaultNotificationCategoryRegistry implements NotificationCategoryRegistry {

    private final Map<String, String> labels = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> descriptions = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Set<String>> dataTypesByCategory = Collections.synchronizedMap(new HashMap<>());

    @Override
    public void registerCategory(@NotNull String categoryKey, @NotNull String label, @NotNull String description) {
        this.labels.put(categoryKey, label);
        this.descriptions.put(categoryKey, description);
        this.dataTypesByCategory.computeIfAbsent(categoryKey, key -> Collections.synchronizedSet(new HashSet<>()));
    }

    @Override
    public void claimDataType(@NotNull String categoryKey, @NotNull String dataType) {
        this.labels.putIfAbsent(categoryKey, "");
        this.descriptions.putIfAbsent(categoryKey, "");
        this.dataTypesByCategory
                .computeIfAbsent(categoryKey, key -> Collections.synchronizedSet(new HashSet<>()))
                .add(dataType);
    }

    @Override
    public void unclaimDataType(@NotNull String categoryKey, @NotNull String dataType) {
        Set<String> claimed = this.dataTypesByCategory.get(categoryKey);
        if (claimed != null) {
            claimed.remove(dataType);
        }
    }

    @Override
    public @NotNull Set<String> categoryKeys() {
        return Set.copyOf(this.dataTypesByCategory.keySet());
    }

    @Override
    public @NotNull Set<String> dataTypesFor(@NotNull String categoryKey) {
        Set<String> claimed = this.dataTypesByCategory.get(categoryKey);
        return claimed != null ? Set.copyOf(claimed) : Set.of();
    }

    @Override
    public @NotNull String label(@NotNull String categoryKey) {
        return this.labels.getOrDefault(categoryKey, "");
    }

    @Override
    public @NotNull String description(@NotNull String categoryKey) {
        return this.descriptions.getOrDefault(categoryKey, "");
    }
}
