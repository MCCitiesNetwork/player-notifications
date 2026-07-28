package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.core.DatabaseNotificationPreferences;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The root notification-preferences screen: choose a pivot ("by delivery method" or "by notification
 * type"), stage a global mute/reset, or apply/discard whatever is currently staged.
 */
final class PreferenceRootDialog {

    private static final Component TITLE = Component.text("Notification Preferences");
    private static final Component INTRO = Component.text(
            "Manage notifications by delivery method or by notification type. Changes are staged until"
                    + " you press Apply.");
    private static final Component BY_MEDIUM_LABEL = Component.text("By delivery method");
    private static final Component BY_CATEGORY_LABEL = Component.text("By notification type");
    private static final Component MUTE_ALL_LABEL = Component.text("Mute everything");
    private static final Component RESET_ALL_LABEL = Component.text("Reset all to server default");
    private static final Component DISCARD_LABEL = Component.text("Discard changes");
    private static final Component CLOSE_LABEL = Component.text("Close");

    private final PreferenceDialogRouter router;

    PreferenceRootDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSessionHandle handle) {
        var session = handle.session();
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
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String dataType : this.router.dataTypeRegistry().dataTypes()) {
                        session.setDataTypeMedia(dataType, Set.of(), now);
                    }
                    session.setDataTypeMedia(DatabaseNotificationPreferences.ALL_DATA_TYPES_KEY, Set.of(), now);
                    show(player, handle);
                }, PreferenceDialogs.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(RESET_ALL_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String dataType : this.router.dataTypeRegistry().dataTypes()) {
                        session.resetDataType(dataType, now);
                    }
                    show(player, handle);
                }, PreferenceDialogs.callbackOptions()))
                .build());
        if (session.isDirty()) {
            Component applyLabel = Component.text("Apply (" + session.dirtyCount() + " changed)");
            buttons.add(ActionButton.builder(applyLabel)
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.apply(player, session), PreferenceDialogs.callbackOptions()))
                    .build());
            buttons.add(ActionButton.builder(DISCARD_LABEL)
                    .action(DialogAction.customClick((response, audience) -> {
                        this.router.sessions().drop(player.getUniqueId());
                        PreferenceDialogs.message(this.router.plugin(), player,
                                Component.text("Changes discarded.", NamedTextColor.YELLOW));
                    }, PreferenceDialogs.callbackOptions()))
                    .build());
        }
        ActionButton close = ActionButton.builder(CLOSE_LABEL).build();

        DialogBase base = DialogBase.builder(TITLE)
                .body(List.of(DialogBody.plainMessage(INTRO)))
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(close).columns(1).build());
        });
        player.showDialog(dialog);
    }
}
