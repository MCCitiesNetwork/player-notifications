package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
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
import java.util.function.Consumer;

/**
 * The confirmation screen for "Mute everything" / "Unmute everything" — the same screen either way,
 * reading its title, intro and direction off {@link PreferenceEditSession#muted()} so it flips once the
 * flag does. Structurally an editor with no inputs: Apply stages the flag flip and persists everything
 * staged, Discard throws the session away, Back leaves without staging anything.
 *
 * <p>It exists because the root screen no longer carries Apply/Discard at all — an Apply sitting on a
 * screen whose only edit was a button press read as belonging to the whole menu rather than to the mute.
 * The change is staged by the Apply button's commit callback rather than on the way in, so Back really is
 * a way out that changes nothing.
 */
final class MuteConfirmDialog {

    private static final Component MUTE_TITLE = Component.text("Mute everything");
    private static final Component UNMUTE_TITLE = Component.text("Unmute everything");
    private static final Component MUTE_INTRO = Component.text(
            "Muting stops every notification from being sent to you. They still arrive in your inbox, "
                    + "so nothing is lost — you just won't be interrupted.");
    private static final Component UNMUTE_INTRO = Component.text(
            "Unmuting lets notifications reach you again, according to your per-type delivery "
                    + "preferences.");
    private static final Component BACK_LABEL = Component.text("Back");

    private final PreferenceDialogRouter router;

    MuteConfirmDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session) {
        boolean currentlyMuted = session.muted();
        Consumer<DialogResponseView> commit = response ->
                session.setMuted(!currentlyMuted, Instant.now());

        List<ActionButton> buttons = new ArrayList<>();
        PreferenceDialogs.addEditorCommitButtons(this.router, player, session, buttons,
                () -> this.router.openRoot(player),
                () -> this.router.openRoot(player),
                commit);

        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showRoot(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(currentlyMuted ? UNMUTE_INTRO : MUTE_INTRO));
        body.add(DialogBody.plainMessage(Component.text(
                currentlyMuted
                        ? "Press Apply to unmute, or Back to leave your preferences as they are."
                        : "Press Apply to mute everything, or Back to leave your preferences as they are.",
                NamedTextColor.YELLOW)));
        PreferenceDialogs.stagedSummary(session).ifPresent(summary ->
                body.add(DialogBody.plainMessage(summary)));

        DialogBase base = DialogBase.builder(currentlyMuted ? UNMUTE_TITLE : MUTE_TITLE)
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
