package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ephemeral preference screen: a data-type select, a media multi-select, and the buttons
 * that commit or throw away the staged edit.
 *
 * <p>Components rather than a modal. A modal opens only in response to an interaction and submits
 * once, so it cannot re-render as the player toggles — it would mean either no staging at all, or
 * asking players to type medium keys as free text, where a typo is silent. Modals are used here only
 * for prose, which components cannot carry.
 */
public final class PreferenceMessageFactory {

    /** Discord rejects a select carrying more options than this. */
    static final int MAX_OPTIONS = 25;

    private static final int OPTION_LABEL_LIMIT = 100;

    private final int embedColor;

    public PreferenceMessageFactory(int embedColor) {
        this.embedColor = embedColor;
    }

    public @NotNull MessageCreateData preferences(@NotNull PreferenceView.State state) {
        if (state.expired() || state.dataTypes().isEmpty()) {
            // Nothing to press: a select with no options is rejected by Discord outright, and a screen
            // whose session is gone must not offer buttons that would stage against a matrix nobody
            // can see.
            return new MessageCreateBuilder()
                    .addEmbeds(new EmbedBuilder().setColor(this.embedColor)
                            .setTitle("Notification preferences")
                            .setDescription(state.expired()
                                    ? InboxMessageFactory.EXPIRED_MESSAGE
                                    : "This server has no configurable notification types.")
                            .build())
                    .build();
        }

        String pending = state.pendingChanges() == 0
                ? "Choose how each kind of notification reaches you."
                : state.pendingChanges() + " pending change"
                        + (state.pendingChanges() == 1 ? "" : "s") + " — press Apply to save.";

        MessageEmbed embed = new EmbedBuilder()
                .setColor(this.embedColor)
                .setTitle("Notification preferences")
                .setDescription(truncate(pending, MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .build();

        List<SelectOption> typeOptions = optionsOf(state.dataTypes());
        List<SelectOption> mediaOptions = optionsOf(state.media());

        return new MessageCreateBuilder()
                .addEmbeds(embed)
                .addComponents(
                        ActionRow.of(StringSelectMenu.create(
                                        ComponentIds.encode(ComponentIds.SURFACE_PREFS, "type"))
                                .setPlaceholder("Notification type")
                                .addOptions(typeOptions)
                                .build()),
                        ActionRow.of(StringSelectMenu.create(
                                        ComponentIds.encode(ComponentIds.SURFACE_PREFS, "media",
                                                state.selectedDataType()))
                                .setPlaceholder("Where it reaches you")
                                .addOptions(mediaOptions)
                                // Zero is a real answer: ticking nothing is how a player silences one type.
                                .setRequiredRange(0, mediaOptions.size())
                                .build()),
                        ActionRow.of(
                                Button.success(
                                        ComponentIds.encode(ComponentIds.SURFACE_PREFS, "apply"), "Apply"),
                                Button.secondary(
                                        ComponentIds.encode(ComponentIds.SURFACE_PREFS, "discard"), "Discard"),
                                muteButton(state)))
                .build();
    }

    /** Labelled by what pressing it would do, not by the state it reports. */
    private static @NotNull Button muteButton(@NotNull PreferenceView.State state) {
        String id = ComponentIds.encode(ComponentIds.SURFACE_PREFS,
                state.muted() ? "unmute" : "mute", state.selectedDataType());
        return state.muted()
                ? Button.primary(id, "Unmute everything")
                : Button.danger(id, "Mute everything");
    }

    private static @NotNull List<SelectOption> optionsOf(@NotNull List<PreferenceView.Choice> choices) {
        List<SelectOption> options = new ArrayList<>(Math.min(choices.size(), MAX_OPTIONS));
        for (PreferenceView.Choice choice : choices) {
            if (options.size() == MAX_OPTIONS) {
                break;
            }
            options.add(SelectOption.of(truncate(choice.label(), OPTION_LABEL_LIMIT), choice.key())
                    .withDefault(choice.selected()));
        }
        return options;
    }

    private static @NotNull String truncate(@NotNull String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
