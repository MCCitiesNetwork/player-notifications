package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.InboxEntry;
import io.github.md5sha256.playernotifications.api.InboxPage;
import io.github.md5sha256.playernotifications.paper.ui.DialogSupport;
import io.github.md5sha256.playernotifications.paper.ui.PageBounds;
import io.github.md5sha256.playernotifications.paper.ui.PagedDialogs;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The inbox list screen: one row per notification, newest first, paged.
 *
 * <p>Unverified by automated tests — dialogs need a live server, the same exception the preference
 * screens sit under. The logic worth testing was pulled out first: rendering into
 * {@link InboxEntryRenderer}, paging into {@link PageBounds}, and query clamping into the service.
 */
final class InboxDialog {

    private static final Component EMPTY = Component.text("Your inbox is empty.", NamedTextColor.GRAY);
    private static final Component MARK_ALL_LABEL = Component.text("Mark all read");
    private static final Component DISMISS_SEEN_LABEL = Component.text("Dismiss all read");
    private static final Component PREFERENCES_LABEL = Component.text("Preferences");
    private static final Component CLOSE_LABEL = Component.text("Close");

    private final InboxRouter router;
    private final Component title;
    private final Consumer<Player> openPreferences;

    InboxDialog(@NotNull InboxRouter router, @NotNull Component title, @NotNull Consumer<Player> openPreferences) {
        this.router = router;
        this.title = title;
        this.openPreferences = openPreferences;
    }

    void show(@NotNull Player player, @NotNull InboxPage page) {
        PageBounds bounds = new PageBounds(page.page(), page.pageSize(), page.totalEntries());

        List<DialogBody> body = new ArrayList<>();
        if (page.entries().isEmpty()) {
            body.add(DialogBody.plainMessage(EMPTY));
        } else {
            body.add(DialogBody.plainMessage(Component.text(
                    page.unreadCount() + " unread of " + page.totalEntries(), NamedTextColor.GRAY)));
        }
        body.add(DialogBody.plainMessage(PagedDialogs.pageIndicator(bounds)));

        List<ActionButton> buttons = new ArrayList<>();
        for (InboxEntry entry : page.entries()) {
            String key = entry.notifKey();
            buttons.add(ActionButton.builder(rowLabel(player, entry))
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.openEntry(player, key), DialogSupport.callbackOptions()))
                    .build());
        }
        PagedDialogs.addNavigationButtons(buttons, bounds, DialogSupport.callbackOptions(),
                target -> this.router.openInbox(player, target));

        if (page.unreadCount() > 0) {
            buttons.add(ActionButton.builder(MARK_ALL_LABEL)
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.markAllSeen(player), DialogSupport.callbackOptions()))
                    .build());
        }
        if (page.totalEntries() > page.unreadCount()) {
            buttons.add(ActionButton.builder(DISMISS_SEEN_LABEL)
                    .action(DialogAction.customClick((response, audience) ->
                            this.router.dismissSeen(player), DialogSupport.callbackOptions()))
                    .build());
        }
        buttons.add(ActionButton.builder(PREFERENCES_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.openPreferences.accept(player), DialogSupport.callbackOptions()))
                .build());

        DialogBase base = DialogBase.builder(this.title)
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

    /** Unread rows are bold and white; already-seen rows are plain grey. */
    private Component rowLabel(@NotNull Player player, @NotNull InboxEntry entry) {
        Component title = this.router.renderer().render(entry, player.getUniqueId()).title();
        return entry.unread()
                ? Component.text("• ", NamedTextColor.WHITE)
                .append(title.colorIfAbsent(NamedTextColor.WHITE))
                .decoration(TextDecoration.BOLD, true)
                : Component.text("  ", NamedTextColor.GRAY)
                .append(title.colorIfAbsent(NamedTextColor.GRAY));
    }
}
