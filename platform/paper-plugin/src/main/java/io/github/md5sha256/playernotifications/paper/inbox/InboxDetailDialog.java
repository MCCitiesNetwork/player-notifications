package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.paper.ui.DialogSupport;
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
 * One notification, read in full. Two buttons: Delete (removes it and returns to the list) and Back
 * (returns without deleting) — Back-doesn't-commit mirrors the rule players already learned in the
 * preference editors. Delete is red, because it is the one button here that destroys something.
 *
 * <p>Opening this screen has already marked the entry seen; that is not undone by Back.
 *
 * <p>Unverified by automated tests: dialogs need a live server.
 */
final class InboxDetailDialog {

    private static final Component DELETE_LABEL = Component.text("Delete", NamedTextColor.RED);
    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component CLOSE_LABEL = Component.text("Close");

    private final InboxRouter router;

    InboxDetailDialog(@NotNull InboxRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull InboxEntry entry, @NotNull RenderableNotification rendered) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(rendered.body()));

        String key = entry.notifKey();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(DELETE_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.dismissEntry(player, key), DialogSupport.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.openInbox(player, 1), DialogSupport.callbackOptions()))
                .build());

        DialogBase base = DialogBase.builder(rendered.title())
                .body(body)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons)
                    .exitAction(ActionButton.builder(CLOSE_LABEL).build())
                    .columns(1)
                    .build());
        });
        player.showDialog(dialog);
    }
}
