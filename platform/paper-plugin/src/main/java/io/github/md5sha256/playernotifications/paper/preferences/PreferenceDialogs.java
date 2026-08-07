package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.github.md5sha256.playernotifications.paper.ui.DialogSupport;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Helpers shared by the five preference dialog screens and their router: session loading, labels, and
 * the callback options every button uses.
 */
final class PreferenceDialogs {

    private PreferenceDialogs() {
    }

    @NotNull
    static ClickCallback.Options callbackOptions() {
        return DialogSupport.callbackOptions();
    }

    /**
     * The line every screen shows while the session holds unapplied edits, or empty when it is clean.
     *
     * <p>It exists because staging was previously invisible: Save wrote into the session and navigated
     * away, and the only sign anything was pending was an Apply button on the root screen the player had
     * already left. Shown on every screen, so "how do I save this?" is answerable without navigating.
     */
    @NotNull
    static Optional<Component> stagedSummary(@NotNull PreferenceEditSession session) {
        if (!session.isDirty()) {
            return Optional.empty();
        }
        int count = session.dirtyCount();
        String text = count == 1
                ? "You have 1 unsaved change. Press Apply to save it."
                : "You have " + count + " unsaved changes. Press Apply to save them.";
        return Optional.of(Component.text(text, NamedTextColor.YELLOW));
    }

    static void message(@NotNull Plugin plugin, @NotNull Player player, @NotNull Component component) {
        DialogSupport.message(plugin, player, component);
    }

    static void onMainThread(@NotNull Plugin plugin, @NotNull Player player, @NotNull Runnable action) {
        DialogSupport.onMainThread(plugin, player, action);
    }

    /**
     * The Apply button's label: bare while nothing is staged, and carrying the pending count once
     * something is. The count is what tells a player on a picker screen that edits made elsewhere are
     * still waiting.
     */
    @NotNull
    static Component applyLabel(@NotNull PreferenceEditSession session) {
        return session.isDirty()
                ? Component.text("Apply (" + session.dirtyCount() + " changed)")
                : Component.text("Apply");
    }

    /**
     * Appends {@code Apply}/{@code Discard} to a screen with no inputs of its own, but only while the
     * session holds unapplied edits — with nothing staged there is nothing for either button to do.
     */
    static void addStagedButtons(@NotNull PreferenceDialogRouter router,
                                 @NotNull Player player,
                                 @NotNull PreferenceEditSession session,
                                 @NotNull List<ActionButton> buttons,
                                 @NotNull Runnable reopen) {
        if (!session.isDirty()) {
            return;
        }
        addCommitButtons(router, player, session, buttons, reopen, reopen, response -> {});
    }

    /**
     * Appends {@code Apply}/{@code Discard} to an editor screen. They are the editor's <em>only</em>
     * buttons — there is deliberately no separate Save. A Save that staged without persisting, sitting
     * next to an Apply that did both, was a third option whose difference from Apply nobody could state.
     *
     * <p>Unlike {@link #addStagedButtons} these show unconditionally, because an editor's checkboxes
     * live in the dialog response until a button is pressed: a first edit on a clean session has nothing
     * staged yet, so a dirty-gated Apply would be missing at exactly the moment it is needed.
     *
     * @param onApplied   reopens whatever should follow a successful write, and {@code onDiscarded} the
     *                    same for a discard. Both must be <em>reloading</em> router entry points: either
     *                    action drops the session, so the instance the caller holds is dead afterwards.
     * @param commit      folds this screen's checkbox state into the session, given the response from the
     *                    Apply click itself.
     */
    static void addEditorCommitButtons(@NotNull PreferenceDialogRouter router,
                                       @NotNull Player player,
                                       @NotNull PreferenceEditSession session,
                                       @NotNull List<ActionButton> buttons,
                                       @NotNull Runnable onApplied,
                                       @NotNull Runnable onDiscarded,
                                       @NotNull Consumer<DialogResponseView> commit) {
        addCommitButtons(router, player, session, buttons, onApplied, onDiscarded, commit);
    }

    private static void addCommitButtons(@NotNull PreferenceDialogRouter router,
                                         @NotNull Player player,
                                         @NotNull PreferenceEditSession session,
                                         @NotNull List<ActionButton> buttons,
                                         @NotNull Runnable onApplied,
                                         @NotNull Runnable onDiscarded,
                                         @NotNull Consumer<DialogResponseView> commit) {
        buttons.add(ActionButton.builder(applyLabel(session))
                .action(DialogAction.customClick((response, audience) -> {
                    commit.accept(response);
                    router.apply(player, session, onApplied);
                }, callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(Component.text("Discard changes"))
                .action(DialogAction.customClick((response, audience) ->
                        router.discard(player, onDiscarded), callbackOptions()))
                .build());
    }

    /**
     * The media offered as checkboxes/buttons: every registered medium except the muted medium, whose
     * meaning is already carried by an empty selection. Sorted so dialog row order is stable between
     * openings.
     */
    @NotNull
    static List<String> selectableMedia(@NotNull NotificationSinkRegistry sinkRegistry) {
        Set<String> sorted = new TreeSet<>(sinkRegistry.registeredMedia());
        sorted.remove(NotificationPreferences.MUTED_MEDIUM);
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
        return sinkRegistry.displayName(medium);
    }

    @NotNull
    static Component categoryLabel(@NotNull NotificationCategories categories, @NotNull String category) {
        return Component.text(categories.label(category));
    }

    /**
     * A data type's row label in the delivery-method editor: its primary category's label, prefixed for
     * readability, followed by the data type itself.
     *
     * <p>The data type is title-cased rather than shown raw. A registry key such as
     * {@code essentials-mail} is an identifier meant for module authors, and a player reading a checkbox
     * list has no way to know it is the same thing as the "Essentials Mail" named everywhere else.
     */
    @NotNull
    static Component dataTypeLabel(@NotNull NotificationCategories categories, @NotNull String dataType) {
        String category = primaryCategoryFor(categories, dataType);
        return Component.text(categories.label(category) + ": " + titleCase(dataType));
    }

    /**
     * Title-cases a registry key: {@code '-'} and {@code '_'} separate words, each word is capitalized,
     * and words are rejoined with spaces. A deliberate third copy of the helper on
     * {@code NotificationSink} and {@code AccountLinkProvider} — those live in {@code api} and neither
     * should become public API for the sake of fifteen lines, which is the reasoning their own javadoc
     * already records.
     */
    @NotNull
    private static String titleCase(@NotNull String key) {
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
            PreferenceEditSession session = sessions.getOrCreate(uuid, () ->
                    new PreferenceEditSession(uuid, effective, Instant.now()));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    onLoaded.accept(session);
                }
            });
        });
    }
}
