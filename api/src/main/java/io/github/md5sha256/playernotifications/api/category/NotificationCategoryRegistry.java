package io.github.md5sha256.playernotifications.api.category;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Lets module authors declare notification categories and claim {@code dataType}s under them in code,
 * the same way payload types, processors, renderers, and sinks are registered. Categories are purely a
 * display/grouping concept for the preference dialogs — never a delivery-time lookup key — so a
 * {@code dataType} may be claimed by any number of categories with no collision to resolve.
 *
 * <p>{@code categories.yml} is merged with this registry at read time by
 * {@code core.category.NotificationCategories}; a module's code registrations and an operator's config
 * entries are both just claims layered on top of each other.
 */
public interface NotificationCategoryRegistry {

    /**
     * Registers (or re-registers) a category's display label and description. Calling this again for an
     * already-registered key overwrites its label/description, not its claimed data types.
     */
    void registerCategory(@NotNull String categoryKey, @NotNull String label, @NotNull String description);

    /**
     * Claims a {@code dataType} for a category. If {@code categoryKey} has not been registered via
     * {@link #registerCategory}, it is registered with an empty label/description rather than throwing —
     * mirroring how {@code NotificationDataTypeRegistry#registerPayloadMapping} has no precondition on
     * prior state.
     */
    void claimDataType(@NotNull String categoryKey, @NotNull String dataType);

    /**
     * Removes one category's claim on one {@code dataType}. A no-op if the category never claimed it.
     */
    void unclaimDataType(@NotNull String categoryKey, @NotNull String dataType);

    /**
     * Every category key registered in code, whether via {@link #registerCategory} or as a side effect
     * of {@link #claimDataType}.
     */
    @NotNull Set<String> categoryKeys();

    /**
     * Every {@code dataType} claimed by the given category. Empty for an unregistered category.
     */
    @NotNull Set<String> dataTypesFor(@NotNull String categoryKey);

    /**
     * The category's display label, or {@code ""} if it was never registered or only implicitly
     * registered via {@link #claimDataType}.
     */
    @NotNull String label(@NotNull String categoryKey);

    /**
     * The category's display description, or {@code ""} if it was never registered or only implicitly
     * registered via {@link #claimDataType}.
     */
    @NotNull String description(@NotNull String categoryKey);
}
