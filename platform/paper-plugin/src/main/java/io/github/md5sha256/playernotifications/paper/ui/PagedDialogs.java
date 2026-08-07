package io.github.md5sha256.playernotifications.paper.ui;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The dialog-side half of paging: the indicator line and the Previous/Next buttons.
 *
 * <p>Like the rest of {@code paper.ui}, this references nothing from this plugin.
 */
public final class PagedDialogs {

    private PagedDialogs() {
    }

    @NotNull
    public static Component pageIndicator(@NotNull PageBounds bounds) {
        return Component.text("Page " + bounds.page() + " of " + bounds.totalPages(),
                NamedTextColor.GRAY);
    }

    /**
     * Appends Previous and Next to {@code buttons}, each calling {@code openPage} with the page it
     * moves to.
     *
     * <p>A button that cannot move is <em>omitted</em> rather than shown disabled: a dialog button that
     * does nothing is indistinguishable, from the player's side, from a broken callback.
     */
    public static void addNavigationButtons(@NotNull List<ActionButton> buttons,
                                            @NotNull PageBounds bounds,
                                            @NotNull ClickCallback.Options options,
                                            @NotNull IntConsumer openPage) {
        if (bounds.hasPrevious()) {
            int previous = bounds.previous().page();
            buttons.add(ActionButton.builder(Component.text("Previous"))
                    .action(DialogAction.customClick((response, audience) -> openPage.accept(previous),
                            options))
                    .build());
        }
        if (bounds.hasNext()) {
            int next = bounds.next().page();
            buttons.add(ActionButton.builder(Component.text("Next"))
                    .action(DialogAction.customClick((response, audience) -> openPage.accept(next),
                            options))
                    .build());
        }
    }
}
