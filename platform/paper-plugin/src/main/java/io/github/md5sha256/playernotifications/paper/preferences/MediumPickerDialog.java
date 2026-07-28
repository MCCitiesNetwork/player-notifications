package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * "By delivery method" picker: choose one registered medium to configure which notification categories
 * reach the player through it.
 */
final class MediumPickerDialog {

    private static final Component TITLE = Component.text("By Delivery Method");
    private static final Component INTRO = Component.text(
            "Choose a delivery method to configure which notifications reach you there.");
    private static final Component BACK_LABEL = Component.text("Back");

    private final PreferenceDialogRouter router;

    MediumPickerDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session) {
        List<String> media = PreferenceDialogs.selectableMedia(this.router.sinkRegistry());
        if (media.isEmpty()) {
            PreferenceDialogs.message(this.router.plugin(), player, Component.text(
                    "No notification media are available on this server.", NamedTextColor.RED));
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (String medium : media) {
            buttons.add(ActionButton.builder(PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), medium))
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.showMediaEditor(player, session, medium), PreferenceDialogs.callbackOptions()))
                    .build());
        }
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showRoot(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(TITLE)
                .body(List.of(DialogBody.plainMessage(INTRO)))
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(back).columns(1).build());
        });
        player.showDialog(dialog);
    }
}
