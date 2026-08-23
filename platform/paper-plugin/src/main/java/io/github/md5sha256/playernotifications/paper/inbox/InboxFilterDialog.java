package io.github.md5sha256.playernotifications.paper.inbox;

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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The filter picker: one row per category, plus an unconditional "All notifications" row.
 *
 * <p>Unverified by automated tests — dialogs need a live server, the same exception every other screen
 * in this tree sits under. What could be pulled out already has been: the key-to-data-type resolution
 * into {@link InboxFilters}, and the counts into the router's async read.
 */
final class InboxFilterDialog {

    private static final Component TITLE = Component.text("Filter inbox");
    private static final Component BACK_LABEL = Component.text("Back");

    /**
     * One row's worth of already-read state. {@code categoryKey} is {@code null} for the unfiltered row,
     * matching {@link InboxFilters#resolve} — so the row a player picks is the argument the router takes,
     * with nothing to translate in between.
     */
    record Row(@Nullable String categoryKey, @NotNull Component label, int unread, int total) {
    }

    private final InboxRouter router;

    InboxFilterDialog(@NotNull InboxRouter router) {
        this.router = router;
    }

    /**
     * Shows the picker. The row list always carries the "All notifications" row, so this can never hand
     * {@code multiAction} an empty {@code actions} list — the codec rejects that outright, which is the
     * bug an empty inbox hits in {@link InboxRouter#openInbox}.
     */
    void show(@NotNull Player player, @NotNull List<Row> rows, @Nullable String activeCategory) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                "Choose which notifications this screen shows.", NamedTextColor.GRAY)));

        List<ActionButton> buttons = new ArrayList<>();
        for (Row row : rows) {
            buttons.add(ActionButton.builder(rowLabel(row, activeCategory))
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.applyDialogFilter(player, row.categoryKey()),
                            DialogSupport.callbackOptions()))
                    .build());
        }
        buttons.add(ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.openInbox(player, 1), DialogSupport.callbackOptions()))
                .build());

        DialogBase base = DialogBase.builder(TITLE)
                .body(body)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons)
                    .exitAction(ActionButton.builder(BACK_LABEL).build())
                    .columns(2)
                    .build());
        });
        player.showDialog(dialog);
    }

    /**
     * {@code <label> — <unread> unread of <total>}, greyed when the category holds nothing.
     *
     * <p>Empty categories are shown rather than hidden: a picker whose rows come and go as mail arrives
     * moves the row a player is reaching for, and "nothing here yet" is itself the answer to "where is
     * my mail". The active row is marked so the screen says what it is currently showing.
     */
    private static Component rowLabel(@NotNull InboxFilterDialog.Row row, @Nullable String activeCategory) {
        boolean active = java.util.Objects.equals(row.categoryKey(), activeCategory);
        NamedTextColor colour = row.total() == 0 ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE;
        Component label = row.label().colorIfAbsent(colour)
                .append(Component.text(" — " + row.unread() + " unread of " + row.total(),
                        NamedTextColor.GRAY));
        return active ? Component.text("▶ ", NamedTextColor.YELLOW).append(label) : label;
    }
}
