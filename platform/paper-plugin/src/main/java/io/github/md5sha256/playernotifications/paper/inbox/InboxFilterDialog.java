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
 * <p><b>This is the inbox's landing screen</b> — {@code /notifications} opens it, and the list is
 * reached from its "View notifications" button. Picking a category re-opens this screen with the new
 * choice marked rather than jumping straight to the list, so choosing a scope and looking at it stay
 * two separate acts and a mis-click costs one more click rather than a screen transition.
 *
 * <p>Unverified by automated tests — dialogs need a live server, the same exception every other screen
 * in this tree sits under. What could be pulled out already has been: the key-to-data-type resolution,
 * into {@link InboxFilters}.
 */
final class InboxFilterDialog {

    private static final Component TITLE = Component.text("Filter inbox");
    private static final Component VIEW_LABEL = Component.text("View notifications");
    private static final Component CLOSE_LABEL = Component.text("Close");

    /**
     * One row. {@code categoryKey} is {@code null} for the unfiltered row, matching
     * {@link InboxFilters#resolve} — so the row a player picks is the argument the router takes, with
     * nothing to translate in between.
     *
     * <p>Carries no counts. A filter is a filter: this screen chooses what the inbox shows, and unread
     * totals are the inbox's own business — the list screen states them for the scope you are actually
     * in. Putting them here also meant a count pair per category on every open, for numbers nobody had
     * asked this screen for.
     */
    record Row(@Nullable String categoryKey, @NotNull Component label) {
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
                "Choose which notifications to show, then view them.", NamedTextColor.GRAY)));

        List<ActionButton> buttons = new ArrayList<>();
        for (Row row : rows) {
            buttons.add(ActionButton.builder(rowLabel(row, activeCategory))
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.applyDialogFilter(player, row.categoryKey()),
                            DialogSupport.callbackOptions()))
                    .build());
        }
        // View and Close are both grid buttons so they sit side by side. exitAction is a separate field
        // rendered on its own row beneath the grid, so a Close there could never share this line.
        // Dropping it costs nothing: canCloseWithEscape defaults to true, so Escape still closes.
        //
        // They pair only when an even number of rows precedes them — with two columns, button i sits at
        // (i / 2, i % 2). An odd count would put View beside the last category instead, which is what
        // the spacer prevents: an empty trailing slot in a 2-column grid reads as alignment, whereas a
        // split pair reads as two unrelated buttons.
        if (buttons.size() % 2 != 0) {
            buttons.add(ActionButton.builder(Component.empty()).build());
        }
        buttons.add(ActionButton.builder(VIEW_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.openInbox(player, 1), DialogSupport.callbackOptions()))
                .build());
        buttons.add(ActionButton.builder(CLOSE_LABEL).build());

        DialogBase base = DialogBase.builder(TITLE)
                .body(body)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            // No exitAction: Close is in the grid above, and setting both would render the word twice.
            builder.base(base).type(DialogType.multiAction(buttons)
                    .columns(2)
                    .build());
        });
        player.showDialog(dialog);
    }

    /**
     * The category's name, with the active one marked so the screen says what it is currently showing.
     *
     * <p>Every category is listed, including ones holding nothing: a picker whose rows come and go as
     * mail arrives moves the row a player is reaching for, and the row set staying put is what makes the
     * screen learnable.
     */
    private static Component rowLabel(@NotNull InboxFilterDialog.Row row, @Nullable String activeCategory) {
        Component label = row.label().colorIfAbsent(NamedTextColor.WHITE);
        return java.util.Objects.equals(row.categoryKey(), activeCategory)
                ? Component.text("▶ ", NamedTextColor.YELLOW).append(label)
                : label;
    }
}
