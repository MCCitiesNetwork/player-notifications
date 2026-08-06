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
import java.util.Set;

/**
 * "Notification types" picker: choose one category to configure which media it reaches the player
 * through. A category label is suffixed "(server default)" when every data type it claims is currently
 * showing the server default.
 */
final class CategoryPickerDialog {

    private static final Component TITLE = Component.text("Notification Types");
    private static final Component INTRO = Component.text(
            "Pick a kind of notification to choose where it is sent.");
    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SERVER_DEFAULT_SUFFIX = Component.text(" (server default)",
            NamedTextColor.GRAY);

    private final PreferenceDialogRouter router;

    CategoryPickerDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session) {
        List<String> categoryKeys = PreferenceDialogs.sortedCategoryKeys(this.router.categories());
        Set<String> allDataTypes = this.router.dataTypeRegistry().dataTypes();
        List<ActionButton> buttons = new ArrayList<>();
        for (String category : categoryKeys) {
            Set<String> members = this.router.categories().dataTypesForCategory(category, allDataTypes);
            boolean usingDefault = members.isEmpty() || members.stream().allMatch(session::isUsingServerDefault);
            Component label = PreferenceDialogs.categoryLabel(this.router.categories(), category);
            if (usingDefault) {
                label = label.append(SERVER_DEFAULT_SUFFIX);
            }
            buttons.add(ActionButton.builder(label)
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.showCategoryEditor(player, session, category), PreferenceDialogs.callbackOptions()))
                    .build());
        }
        PreferenceDialogs.addStagedButtons(this.router, player, session, buttons,
                () -> this.router.openCategoryPicker(player), response -> {});
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showRoot(player, session), PreferenceDialogs.callbackOptions()))
                .build();

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
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(back).columns(1).build());
        });
        player.showDialog(dialog);
    }
}
