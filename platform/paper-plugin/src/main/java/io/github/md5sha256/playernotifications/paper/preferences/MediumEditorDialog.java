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
 * category), indicating whether it currently reaches the player through this medium.
 *
 * <p>Apply and Discard are the only buttons besides Back: Apply folds the checkboxes into the session
 * and persists everything staged, Discard throws the session away. There is no Save — staging without
 * persisting was a third option indistinguishable from Apply to the player pressing it. Back is the
 * way out without saving, and resets what was ticked here.
 */
final class MediumEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");

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

        // Folds this screen's checkbox state into the session on Apply. Each click callback carries its
        // own response, so it is passed in rather than captured.
        Consumer<DialogResponseView> commit = response -> {
            Instant now = Instant.now();
            for (Map.Entry<String, String> entry : inputKeyToDataType.entrySet()) {
                boolean checked = Boolean.TRUE.equals(response.getBoolean(entry.getKey()));
                session.toggleDataTypeMedium(entry.getValue(), mediumKey, checked, now);
            }
        };

        List<ActionButton> buttons = new ArrayList<>();
        PreferenceDialogs.addEditorCommitButtons(this.router, player, session, buttons,
                () -> this.router.openMediaPicker(player),
                () -> this.router.openMediaEditor(player, mediumKey),
                commit);
        // Back abandons this screen's checkboxes: it deliberately does not commit, so leaving without
        // pressing Apply resets what was ticked here. Anything applied or staged on another screen is
        // untouched — Back backs out of this editor, it is not a session-wide undo.
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
