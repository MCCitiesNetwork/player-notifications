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

/**
 * Editor for one medium: a checkbox per notification data type (grouped for readability by its primary
 * category), indicating whether it currently reaches the player through this medium. Save writes into
 * the session only; nothing is persisted until the root screen's Apply.
 */
final class MediumEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SAVE_LABEL = Component.text("Save");

    private final PreferenceDialogRouter router;

    MediumEditorDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String mediumKey) {
        List<String> dataTypes = PreferenceDialogs.sortedDataTypes(this.router.categories(), this.router.dataTypeRegistry());
        Map<String, String> inputKeyToDataType = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(dataTypes.size());
        for (int i = 0; i < dataTypes.size(); i++) {
            String dataType = dataTypes.get(i);
            String inputKey = PreferenceDialogs.inputKey("dataType", i);
            inputKeyToDataType.put(inputKey, dataType);
            boolean initial = session.mediaFor(dataType).contains(mediumKey);
            inputs.add(DialogInput.bool(inputKey, PreferenceDialogs.dataTypeLabel(this.router.categories(), dataType))
                    .initial(initial).build());
        }

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (Map.Entry<String, String> entry : inputKeyToDataType.entrySet()) {
                        boolean checked = Boolean.TRUE.equals(response.getBoolean(entry.getKey()));
                        session.toggleDataTypeMedium(entry.getValue(), mediumKey, checked, now);
                    }
                    this.router.showMediaPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showMediaPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), mediumKey))
                .body(List.of(DialogBody.plainMessage(Component.text(
                        "Choose which notifications reach you through this delivery method."))))
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(List.of(save)).exitAction(back).columns(2).build());
        });
        player.showDialog(dialog);
    }
}
