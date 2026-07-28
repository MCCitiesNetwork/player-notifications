package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Editor for one notification category: a checkbox per registered medium, plus "use server default" to
 * stage clearing this category's explicit configuration. Save writes into the session only; nothing is
 * persisted until the root screen's Apply.
 */
final class CategoryEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SAVE_LABEL = Component.text("Save");
    private static final Component USE_DEFAULT_LABEL = Component.text("Use server default");

    private final PreferenceDialogRouter router;

    CategoryEditorDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String categoryKey) {
        List<String> media = PreferenceDialogs.selectableMedia(this.router.sinkRegistry());
        Map<String, String> inputKeyToMedium = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(media.size());
        for (int i = 0; i < media.size(); i++) {
            String medium = media.get(i);
            String inputKey = PreferenceDialogs.inputKey("medium", i);
            inputKeyToMedium.put(inputKey, medium);
            boolean initial = session.mediaFor(categoryKey).contains(medium);
            inputs.add(DialogInput.bool(inputKey, PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), medium))
                    .initial(initial).build());
        }

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Set<String> selected = new TreeSet<>();
                    for (Map.Entry<String, String> entry : inputKeyToMedium.entrySet()) {
                        if (Boolean.TRUE.equals(response.getBoolean(entry.getKey()))) {
                            selected.add(entry.getValue());
                        }
                    }
                    session.setCategoryMedia(categoryKey, selected, Instant.now());
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton useDefault = ActionButton.builder(USE_DEFAULT_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    session.resetCategory(categoryKey, Instant.now());
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showCategoryPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(PreferenceDialogs.categoryLabel(this.router.categories(), categoryKey))
                .body(List.of(DialogBody.plainMessage(Component.text(
                        "Choose where this kind of notification reaches you."))))
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(List.of(save, useDefault)).exitAction(back)
                    .columns(2).build());
        });
        player.showDialog(dialog);
    }
}
