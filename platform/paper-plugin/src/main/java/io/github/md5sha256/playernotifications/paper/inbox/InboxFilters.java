package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the category a player picked on the inbox filter screen into the data-type set the inbox
 * queries take.
 *
 * <p>Deliberately holds no Bukkit type, so it is unit-testable without a live server — the same device
 * {@code TypeNames} and {@code MailRecipients} use.
 *
 * <p>Both the {@link NotificationCategories} merge and the {@link NotificationDataTypeRegistry} are read
 * <b>live on every call</b> rather than snapshotted, matching {@code TypeNames}: a module registering a
 * data type late, or a {@code /notifications reload}, is picked up by the next screen open with no
 * change listener.
 */
public final class InboxFilters {

    /** The label for the unfiltered scope. A dialog string, so hardcoded — see CLAUDE.md's "Messages". */
    private static final Component ALL_LABEL = Component.text("All notifications");

    private final NotificationDataTypeRegistry registry;
    private volatile NotificationCategories categories;

    public InboxFilters(@NotNull NotificationCategories categories,
                        @NotNull NotificationDataTypeRegistry registry) {
        this.categories = categories;
        this.registry = registry;
    }

    /**
     * The data types a category filter matches.
     *
     * <p><b>{@code null} and empty are different, and that distinction is the point.</b> A {@code null}
     * {@code categoryKey} returns {@code null}, meaning <em>unfiltered</em> — every notification. Any
     * other key returns a set, possibly <b>empty</b>, meaning <em>match nothing</em>: a category may
     * claim only data types nothing has registered, and an unknown key (one removed by a reload while a
     * player was filtered to it) resolves the same way. Collapsing empty onto {@code null} would show a
     * player every notification they have under a heading claiming to contain none of them.
     */
    @Nullable
    public Set<String> resolve(@Nullable String categoryKey) {
        if (categoryKey == null) {
            return null;
        }
        return this.categories.dataTypesForCategory(categoryKey, this.registry.dataTypes());
    }

    /**
     * Whether a filter scope holds anything the player has not read, given one unread count per data
     * type. Backs the asterisk the filter picker puts on a row.
     *
     * <p>Scoping goes through {@link #resolve}, so the {@code null}/empty distinction holds here too: a
     * {@code null} key is unfiltered and asks only whether the player has any unread notification at
     * all, while a category claiming nothing registered — or a key removed by a reload — matches no
     * data type and is therefore never marked, however much unread mail sits in other categories.
     *
     * <p>The map is expected to omit data types with nothing unread, but a zero is read as a zero
     * rather than as presence, so it cannot matter which way the query renders one.
     */
    public boolean hasUnread(@Nullable String categoryKey, @NotNull Map<String, Integer> unreadByDataType) {
        Set<String> dataTypes = resolve(categoryKey);
        if (dataTypes == null) {
            return unreadByDataType.values().stream().anyMatch(count -> count > 0);
        }
        return dataTypes.stream().anyMatch(dataType -> unreadByDataType.getOrDefault(dataType, 0) > 0);
    }

    /**
     * Every selectable category key, sorted, with {@link NotificationCategories#UNCATEGORIZED} last
     * regardless of where the sort would otherwise put it — it is the catch-all, so it reads as the
     * bottom of the list rather than as a category among the others.
     */
    @NotNull
    public List<String> categoryKeys() {
        List<String> keys = new ArrayList<>(this.categories.categoryKeys());
        keys.sort(Comparator
                .comparing((String key) -> NotificationCategories.UNCATEGORIZED.equals(key))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(keys);
    }

    /**
     * The player-facing label for a filter scope: "All notifications" for {@code null} (unfiltered),
     * otherwise the category's own label.
     */
    @NotNull
    public Component label(@Nullable String categoryKey) {
        if (categoryKey == null) {
            return ALL_LABEL;
        }
        return Component.text(this.categories.label(categoryKey));
    }

    /**
     * Swaps in a reloaded category merge, the same idiom {@code PreferenceDialogRouter.reloadCategories}
     * uses — a mutable field rather than reconstructing an object other code already holds.
     */
    public void reloadCategories(@NotNull NotificationCategories categories) {
        this.categories = categories;
    }
}
