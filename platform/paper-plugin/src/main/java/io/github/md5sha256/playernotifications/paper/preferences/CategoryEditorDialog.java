package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Editor for one notification category: a checkbox per registered medium, plus "use server default" to
 * stage clearing every data type this category claims. Checking or unchecking a medium away from what
 * was rendered fans out to a per-data-type write on Apply; a checkbox left exactly as rendered is a
 * no-op, except a "(partly on)" medium always resolves on Apply (it has no single "current" value to
 * compare against, so it always fans out uniformly).
 *
 * <p>Apply folds the checkboxes into the session and persists everything staged, Discard throws the
 * session away. There is no Save — staging without persisting was a third option indistinguishable
 * from Apply to the player pressing it. "Use server default" remains, being a different action rather
 * than a second way to save.
 */
final class CategoryEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component USE_DEFAULT_LABEL = Component.text("Use server default");
    private static final Component MIXED_SUFFIX = Component.text(" (partly on)", NamedTextColor.GRAY);

    private final PreferenceDialogRouter router;

    CategoryEditorDialog(@NotNull PreferenceDialogRouter router) {
        this.router = router;
    }

    void show(@NotNull Player player, @NotNull PreferenceEditSession session, @NotNull String categoryKey) {
        Set<String> memberDataTypes = this.router.categories()
                .dataTypesForCategory(categoryKey, this.router.dataTypeRegistry().dataTypes());
        List<String> media = PreferenceDialogs.selectableMedia(this.router.sinkRegistry());
        Map<String, String> inputKeyToMedium = new LinkedHashMap<>();
        Map<String, MixedState> inputKeyToState = new LinkedHashMap<>();
        List<DialogInput> inputs = new ArrayList<>(media.size());
        for (int i = 0; i < media.size(); i++) {
            String medium = media.get(i);
            String inputKey = PreferenceDialogs.inputKey("medium", i);
            inputKeyToMedium.put(inputKey, medium);
            MixedState state = mixedStateFor(session, memberDataTypes, medium);
            inputKeyToState.put(inputKey, state);
            Component label = PreferenceDialogs.mediumLabel(this.router.sinkRegistry(), medium);
            if (state == MixedState.MIXED) {
                label = label.append(MIXED_SUFFIX);
            }
            inputs.add(DialogInput.bool(inputKey, label).initial(state == MixedState.ALL_CHECKED).build());
        }

        // Folds this screen's checkbox state into the session on Apply. Each click callback carries its
        // own response, so it is passed in rather than captured.
        Consumer<DialogResponseView> commit = response -> {
            Instant now = Instant.now();
            for (Map.Entry<String, String> entry : inputKeyToMedium.entrySet()) {
                boolean checked = Boolean.TRUE.equals(response.getBoolean(entry.getKey()));
                MixedState state = inputKeyToState.get(entry.getKey());
                if (state != MixedState.MIXED && checked == (state == MixedState.ALL_CHECKED)) {
                    continue;
                }
                for (String dataType : memberDataTypes) {
                    session.toggleDataTypeMedium(dataType, entry.getValue(), checked, now);
                }
            }
        };

        ActionButton useDefault = ActionButton.builder(USE_DEFAULT_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String dataType : memberDataTypes) {
                        session.resetDataType(dataType, now);
                    }
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        // Back abandons this screen's checkboxes rather than staging them — see MediumEditorDialog.
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showCategoryPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        List<ActionButton> buttons = new ArrayList<>(List.of(useDefault));
        PreferenceDialogs.addEditorCommitButtons(this.router, player, session, buttons,
                () -> this.router.openCategoryPicker(player),
                () -> this.router.openCategoryEditor(player, categoryKey),
                commit);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                "Choose where this kind of notification is sent.")));
        PreferenceDialogs.stagedSummary(session).ifPresent(summary ->
                body.add(DialogBody.plainMessage(summary)));

        DialogBase base = DialogBase.builder(PreferenceDialogs.categoryLabel(this.router.categories(), categoryKey))
                .body(body)
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(buttons).exitAction(back)
                    .columns(2).build());
        });
        player.showDialog(dialog);
    }

    private enum MixedState { ALL_CHECKED, ALL_UNCHECKED, MIXED }

    @NotNull
    private static MixedState mixedStateFor(@NotNull PreferenceEditSession session,
                                            @NotNull Set<String> memberDataTypes, @NotNull String medium) {
        boolean anyChecked = false;
        boolean anyUnchecked = false;
        for (String dataType : memberDataTypes) {
            if (session.mediaFor(dataType).contains(medium)) {
                anyChecked = true;
            } else {
                anyUnchecked = true;
            }
        }
        if (anyChecked && anyUnchecked) {
            return MixedState.MIXED;
        }
        return anyChecked ? MixedState.ALL_CHECKED : MixedState.ALL_UNCHECKED;
    }
}
