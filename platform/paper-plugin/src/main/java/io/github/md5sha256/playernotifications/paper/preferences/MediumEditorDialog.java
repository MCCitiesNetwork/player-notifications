package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
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
import java.util.function.Consumer;

/**
 * Editor for one medium: a checkbox per notification data type (grouped for readability by its primary
 * category), indicating whether it currently reaches the player through this medium. Save writes into
 * the session only; Apply — shown here as on every screen once anything is staged — persists it.
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

        // Shared by Save and Apply: both must fold this screen's checkbox state into the session, and
        // each click callback carries its own response, so it is passed in rather than captured.
        Consumer<DialogResponseView> commit = response -> {
            Instant now = Instant.now();
            for (Map.Entry<String, String> entry : inputKeyToDataType.entrySet()) {
                boolean checked = Boolean.TRUE.equals(response.getBoolean(entry.getKey()));
                session.toggleDataTypeMedium(entry.getValue(), mediumKey, checked, now);
            }
        };

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    commit.accept(response);
                    this.router.showMediaPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build());
        PreferenceDialogs.addStagedButtons(this.router, player, session, buttons,
                () -> this.router.openMediaPicker(player), commit);
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showMediaPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Choose which notifications are sent here.")));
        PreferenceDialogs.stagedSummary(session).ifPresent(summary ->
                body.add(DialogBody.plainMessage(summary)));

        DialogBase base = DialogBase.builder(PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), mediumKey))
                .body(body)
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(back).columns(2).build());
        });
        player.showDialog(dialog);
    }
}
