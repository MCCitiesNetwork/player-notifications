package io.github.md5sha256.playernotifications.paper.preferences;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The root notification-preferences screen: choose a pivot ("delivery methods" or "notification types"),
 * or go to the mute confirmation screen.
 *
 * <p>It is purely navigational — it carries <em>no</em> Apply and <em>no</em> Discard. Every edit is made
 * on a screen of its own ({@link MediumEditorDialog}, {@link CategoryEditorDialog},
 * {@link MuteConfirmDialog}) and committed there, so a commit button here would belong to no particular
 * edit. It still shows {@link PreferenceDialogs#stagedSummary} so a player passing through knows edits
 * made elsewhere are still waiting.
 */
final class PreferenceRootDialog {

    private static final Component TITLE = Component.text("Notification Preferences");
    private static final Component INTRO = Component.text(
            "Choose how notifications reach you. Nothing is saved until you press Apply.");
    private static final Component BY_MEDIUM_LABEL = Component.text("Delivery methods");
    private static final Component BY_CATEGORY_LABEL = Component.text("Notification types");
    private static final Component MUTE_ALL_LABEL = Component.text("Mute everything");
    private static final Component CLOSE_LABEL = Component.text("Close");

    private final PreferenceDialogRouter router;

    PreferenceRootDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(BY_MEDIUM_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showMediaPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(BY_CATEGORY_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showCategoryPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(MUTE_ALL_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showMuteConfirm(player, session), PreferenceDialogs.callbackOptions()))
                .build());
        ActionButton close = ActionButton.builder(CLOSE_LABEL).build();

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(INTRO));
        PreferenceDialogs.stagedSummary(session).ifPresent(summary ->
                body.add(DialogBody.plainMessage(summary)));

        DialogBase base = DialogBase.builder(TITLE)
                .body(body)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(close).columns(1).build());
        });
        player.showDialog(dialog);
    }
}
