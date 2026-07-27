package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builds and shows the player-facing notification preferences dialog: one checkbox per user-selectable
 * registered medium, plus Save / Use server default / Cancel.
 *
 * <p>The dialog expresses all three preference states the storage layer distinguishes:
 * <ul>
 *     <li><b>Explicit selection</b> — Save with at least one box checked.</li>
 *     <li><b>Explicit mute</b> — Save with nothing checked, stored as the single medium
 *     {@value NullSink#MEDIUM_KEY} (see {@link NullSink}).</li>
 *     <li><b>Unconfigured</b> — Use server default, which clears the player's rows so
 *     {@code default-media} from {@code settings.yml} applies again.</li>
 * </ul>
 *
 * <p>Threading: {@link DatabaseNotificationPreferences} performs blocking JDBC, while
 * {@link Player#showDialog(Dialog)} must run on the main thread. Reads and writes are therefore
 * marshalled onto the async scheduler and the resulting UI work back onto the main thread.
 */
public final class NotificationPreferencesDialog {

    /**
     * How long a Save / Use server default button stays clickable after the dialog is shown. A dialog
     * left open past this makes its buttons inert; the player simply reopens it.
     */
    private static final Duration CALLBACK_LIFETIME = Duration.ofHours(1);

    private static final Component TITLE = Component.text("Notification Preferences");
    private static final Component INTRO = Component.text(
            "Choose how you want to receive notifications. Saving with nothing selected mutes them.");
    private static final Component SAVE_LABEL = Component.text("Save");
    private static final Component DEFAULT_LABEL = Component.text("Use server default");
    private static final Component CANCEL_LABEL = Component.text("Cancel");

    private final Plugin plugin;
    private final NotificationSinkRegistry sinkRegistry;
    private final DatabaseNotificationPreferences preferences;

    public NotificationPreferencesDialog(@NotNull Plugin plugin,
                                         @NotNull NotificationSinkRegistry sinkRegistry,
                                         @NotNull DatabaseNotificationPreferences preferences) {
        this.plugin = plugin;
        this.sinkRegistry = sinkRegistry;
        this.preferences = preferences;
    }

    /**
     * Reads the player's current preferences off the main thread, then shows the dialog on it. Safe to
     * call from a command executor.
     */
    public void open(@NotNull Player player) {
        List<String> selectable = selectableMedia();
        if (selectable.isEmpty()) {
            player.sendMessage(Component.text(
                    "No notification media are available on this server.", NamedTextColor.RED));
            return;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            Set<String> current = this.preferences.preferredMedia(uuid);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.showDialog(buildDialog(player, selectable, current));
            });
        });
    }

    /**
     * The media offered as checkboxes: every registered medium except {@link NullSink}, whose meaning is
     * already carried by checking nothing. Sorted so the dialog's row order is stable between openings.
     */
    @NotNull
    private List<String> selectableMedia() {
        Set<String> sorted = new TreeSet<>(this.sinkRegistry.registeredMedia());
        sorted.remove(NullSink.MEDIUM_KEY);
        return List.copyOf(sorted);
    }

    @NotNull
    private Dialog buildDialog(@NotNull Player player,
                               @NotNull List<String> selectable,
                               @NotNull Set<String> current) {
        // Input keys are derived from medium keys, which may contain '-'; keep the mapping so the
        // response view can be read back without re-deriving it.
        Map<String, String> inputKeyToMedium = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(selectable.size());
        for (int i = 0; i < selectable.size(); i++) {
            String medium = selectable.get(i);
            String inputKey = inputKey(i);
            inputKeyToMedium.put(inputKey, medium);
            inputs.add(DialogInput.bool(inputKey, label(medium))
                    .initial(current.contains(medium))
                    .build());
        }

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick(
                        (response, audience) -> save(player, inputKeyToMedium,
                                inputKey -> Boolean.TRUE.equals(response.getBoolean(inputKey))),
                        callbackOptions()))
                .build();
        ActionButton useDefault = ActionButton.builder(DEFAULT_LABEL)
                .action(DialogAction.customClick(
                        (response, audience) -> resetToDefault(player),
                        callbackOptions()))
                .build();
        // No action: closing the dialog without writing anything is exactly "cancel".
        ActionButton cancel = ActionButton.builder(CANCEL_LABEL).build();

        DialogBase base = DialogBase.builder(TITLE)
                .body(List.of(DialogBody.plainMessage(INTRO)))
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        return Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(List.of(save, useDefault))
                    .exitAction(cancel)
                    .columns(2)
                    .build());
        });
    }

    /**
     * Applies a Save click. The dialog is a snapshot, so the selection is re-validated against the
     * registry as it stands now: a medium unregistered while the dialog was open is dropped rather than
     * persisted as an unroutable preference.
     */
    private void save(@NotNull Player player,
                      @NotNull Map<String, String> inputKeyToMedium,
                      @NotNull Predicate<String> checked) {
        Set<String> stillRegistered = this.sinkRegistry.registeredMedia();
        Set<String> selected = new TreeSet<>();
        for (Map.Entry<String, String> entry : inputKeyToMedium.entrySet()) {
            if (checked.test(entry.getKey()) && stillRegistered.contains(entry.getValue())) {
                selected.add(entry.getValue());
            }
        }

        boolean muted = selected.isEmpty();
        Set<String> toStore = muted ? Set.of(NullSink.MEDIUM_KEY) : Set.copyOf(selected);
        Component feedback = muted
                ? Component.text("Notifications muted. You will not be notified anywhere.",
                        NamedTextColor.YELLOW)
                : Component.text("Notifications will be delivered via: ", NamedTextColor.GREEN)
                        .append(joinLabels(selected));
        writeAsync(player, toStore, feedback);
    }

    /**
     * Applies a Use server default click: clears the player's rows so they fall back to
     * {@code default-media}. Distinct from a mute, which stores {@value NullSink#MEDIUM_KEY}.
     */
    private void resetToDefault(@NotNull Player player) {
        writeAsync(player, Set.of(), Component.text(
                "Notification preferences reset to the server default.", NamedTextColor.GREEN));
    }

    private void writeAsync(@NotNull Player player,
                            @NotNull Set<String> media,
                            @NotNull Component feedback) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                this.preferences.setPreferredMedia(uuid, media);
            } catch (RuntimeException ex) {
                this.plugin.getLogger().warning(
                        "Failed to save notification preferences for " + uuid + ": " + ex.getMessage());
                message(player, Component.text(
                        "Could not save your notification preferences; please try again.",
                        NamedTextColor.RED));
                return;
            }
            message(player, feedback);
        });
    }

    private void message(@NotNull Player player, @NotNull Component component) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(component);
            }
        });
    }

    /**
     * The checkbox label for a medium: its sink's {@link NotificationSink#displayName()}, falling back
     * to the raw key if the sink vanished between listing and rendering.
     */
    @NotNull
    private Component label(@NotNull String medium) {
        return this.sinkRegistry.getSink(medium)
                .map(NotificationSink::displayName)
                .orElseGet(() -> Component.text(medium));
    }

    @NotNull
    private Component joinLabels(@NotNull Collection<String> media) {
        Component joined = Component.empty();
        boolean first = true;
        for (String medium : media) {
            if (!first) {
                joined = joined.append(Component.text(", "));
            }
            joined = joined.append(label(medium));
            first = false;
        }
        return joined;
    }

    /**
     * The dialog input key for the checkbox at the given row. Positional rather than derived from the
     * medium key: medium keys are arbitrary strings, so any sanitizing transform of them risks two
     * distinct media collapsing onto one input key and silently losing a checkbox. The
     * {@code inputKeyToMedium} map carries the association instead.
     */
    @NotNull
    private static String inputKey(int index) {
        return "medium_" + index;
    }

    @NotNull
    private static ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(CALLBACK_LIFETIME)
                .build();
    }
}
