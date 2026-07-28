package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import io.papermc.paper.dialog.Dialog;
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

/**
 * Editor for one notification category: a checkbox per registered medium, plus "use server default" to
 * stage clearing every data type this category claims. Checking or unchecking a medium away from what
 * was rendered fans out to a per-data-type write on Save; a checkbox left exactly as rendered is a
 * no-op, except a "(mixed)" medium always resolves on Save (it has no single "current" value to compare
 * against, so it always fans out uniformly). Save writes into the session only; nothing is persisted
 * until the root screen's Apply.
 */
final class CategoryEditorDialog {

    private static final Component BACK_LABEL = Component.text("Back");
    private static final Component SAVE_LABEL = Component.text("Save");
    private static final Component USE_DEFAULT_LABEL = Component.text("Use server default");
    private static final Component MIXED_SUFFIX = Component.text(" (mixed)", NamedTextColor.GRAY);

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

        ActionButton save = ActionButton.builder(SAVE_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
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
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton useDefault = ActionButton.builder(USE_DEFAULT_LABEL)
                .action(DialogAction.customClick((response, audience) -> {
                    Instant now = Instant.now();
                    for (String dataType : memberDataTypes) {
                        session.resetDataType(dataType, now);
                    }
                    this.router.showCategoryPicker(player, session);
                }, PreferenceDialogs.callbackOptions()))
                .build();
        ActionButton back = ActionButton.builder(BACK_LABEL)
                .action(DialogAction.customClick((response, audience) ->
                        this.router.showCategoryPicker(player, session), PreferenceDialogs.callbackOptions()))
                .build();

        DialogBase base = DialogBase.builder(PreferenceDialogs.categoryLabel(this.router.categories(), categoryKey))
                .body(List.of(DialogBody.plainMessage(Component.text(
                        "Choose where this kind of notification reaches you."))))
                .inputs(inputs)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.multiAction(List.of(save, useDefault)).exitAction(back)
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
