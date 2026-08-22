package io.github.md5sha256.playernotifications.api.category;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory {@link NotificationCategoryRegistry}. Not thread-confined — like
 * {@code NotificationDataTypeRegistry}, its backing maps are synchronized so registration calls from
 * different module class loaders at startup cannot race.
 *
 * <p>Mutations notify every listener added via {@link #addChangeListener}, which is what lets the host
 * rebuild its merged category snapshot when a plugin registers after startup.
 */
public final class DefaultNotificationCategoryRegistry implements NotificationCategoryRegistry {

    private final Map<String, String> labels = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> descriptions = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Set<String>> dataTypesByCategory = Collections.synchronizedMap(new HashMap<>());
    // Copy-on-write: listeners are added a handful of times at startup but fired on every mutation,
    // and firing must not hold a lock -- a listener rebuilding the merged snapshot reads this very
    // registry back, which would deadlock against a synchronized listener list.
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    @Override
    public void registerCategory(@NotNull String categoryKey, @NotNull String label, @NotNull String description) {
        this.labels.put(categoryKey, label);
        this.descriptions.put(categoryKey, description);
        this.dataTypesByCategory.computeIfAbsent(categoryKey, key -> Collections.synchronizedSet(new HashSet<>()));
        fireChanged();
    }

    @Override
    public void claimDataType(@NotNull String categoryKey, @NotNull String dataType) {
        this.labels.putIfAbsent(categoryKey, "");
        this.descriptions.putIfAbsent(categoryKey, "");
        this.dataTypesByCategory
                .computeIfAbsent(categoryKey, key -> Collections.synchronizedSet(new HashSet<>()))
                .add(dataType);
        fireChanged();
    }

    @Override
    public void unclaimDataType(@NotNull String categoryKey, @NotNull String dataType) {
        Set<String> claimed = this.dataTypesByCategory.get(categoryKey);
        if (claimed != null) {
            claimed.remove(dataType);
        }
        fireChanged();
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

    @Override
    public void addChangeListener(@NotNull Runnable listener) {
        this.changeListeners.add(listener);
    }

    /**
     * Runs every listener, isolating failures: a listener that throws is logged and the remaining
     * listeners still run. A registration must never be lost because something observing it broke.
     */
    private void fireChanged() {
        for (Runnable listener : this.changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                Logger.getLogger(DefaultNotificationCategoryRegistry.class.getName())
                        .log(Level.WARNING, "A category change listener threw", ex);
            }
        }
    }
}
