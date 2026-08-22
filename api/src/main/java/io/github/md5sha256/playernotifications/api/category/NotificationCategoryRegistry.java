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
 *
 * <p><b>Registering after startup.</b> That merge produces an immutable snapshot, so claims made after
 * it is built are invisible to the preference dialogs until it is rebuilt. PN's own modules avoid this
 * by starting before the snapshot is taken, but a <em>separate plugin</em> registering from its own
 * {@code onEnable} necessarily runs later, and would otherwise see every one of its data types fall
 * into {@code uncategorized}. {@link #addChangeListener} exists for that case: the host subscribes and
 * rebuilds, so late registration is picked up automatically and no registrant has to know it was late.
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

    /**
     * Subscribes to mutations of this registry: {@code listener} runs after every
     * {@link #registerCategory}, {@link #claimDataType} and {@link #unclaimDataType}.
     *
     * <p>Intended for the host, not for registrants — PN itself subscribes so that a claim arriving
     * after the merged category snapshot was built still reaches the preference dialogs. Registrants
     * simply register their categories whenever they are ready and need not call anything else.
     *
     * <p>Listeners fire synchronously on the mutating thread, once per mutating call, so a registrant
     * claiming five data types fires five times. A listener that rebuilds anything expensive should
     * therefore coalesce rather than rebuild per callback. A listener that throws must not prevent the
     * mutation it observes from taking effect, nor stop other listeners running.
     *
     * <p>The default implementation does nothing, so an existing custom registry stays source- and
     * binary-compatible — at the cost of not supporting late registration until it implements this.
     */
    default void addChangeListener(@NotNull Runnable listener) {
    }
}
