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
 * "By notification type" picker: choose one category to configure which media it reaches the player
 * through. A category label is suffixed "(server default)" when the player has not explicitly
 * configured it.
 */
final class CategoryPickerDialog {

    private static final Component TITLE = Component.text("By Notification Type");
    private static final Component INTRO = Component.text(
            "Choose a notification type to configure where it reaches you.");
    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SERVER_DEFAULT_SUFFIX = Component.text(" (server default)",
            NamedTextColor.GRAY);

    private final PreferenceDialogRouter router;

    CategoryPickerDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session) {
        List<String> categoryKeys = PreferenceDialogs.sortedCategoryKeys(this.router.categories());
        List<ActionButton> buttons = new ArrayList<>();
        for (String category : categoryKeys) {
            Component label = PreferenceDialogs.categoryLabel(this.router.categories(), category);
            if (session.isUsingServerDefault(category)) {
                label = label.append(SERVER_DEFAULT_SUFFIX);
            }
            buttons.add(ActionButton.builder(label)
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.showCategoryEditor(player, session, category), PreferenceDialogs.callbackOptions()))
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
