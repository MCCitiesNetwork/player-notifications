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
 * Builds the ephemeral preference screen: a data-type select, a media multi-select, and four buttons —
 * Apply, Discard, and the two silences (this type, and every type).
 *
 * <p>The screen edits <em>silences</em> only: lasting, per-{@code dataType} choices. The player-level
 * mute is temporary and covers every type at once, so it has no button here — {@code /notifications
 * mute} sets it, and this screen only says so when it is on.
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

        MessageEmbed embed = new EmbedBuilder()
                .setColor(this.embedColor)
                .setTitle("Notification preferences")
                .setDescription(truncate(description(state), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .build();

        List<SelectOption> typeOptions = optionsOf(state.dataTypes());
        List<SelectOption> mediaOptions = optionsOf(state.media());

        MessageCreateBuilder message = new MessageCreateBuilder()
                .addEmbeds(embed)
                .addComponents(ActionRow.of(StringSelectMenu.create(
                                ComponentIds.encode(ComponentIds.SURFACE_PREFS, "type"))
                        .setPlaceholder("Notification type")
                        .addOptions(typeOptions)
                        .build()));

        // Discord rejects a select with no options outright, the same shape of bug as vanilla's empty
        // multiAction dialog. With no sink registered there is no medium to offer, but Silence this
        // type is still a real answer, so the buttons stay.
        if (!mediaOptions.isEmpty()) {
            message.addComponents(ActionRow.of(StringSelectMenu.create(
                            ComponentIds.encode(ComponentIds.SURFACE_PREFS, "media",
                                    state.selectedDataType()))
                    .setPlaceholder("Where it reaches you")
                    .addOptions(mediaOptions)
                    // Zero is a real answer: unticking everything silences the type, exactly as the
                    // Silence this type button does.
                    .setRequiredRange(0, mediaOptions.size())
                    .build()));
        }

        return message
                .addComponents(ActionRow.of(
                        Button.success(
                                ComponentIds.encode(ComponentIds.SURFACE_PREFS, "apply"), "Apply"),
                        Button.secondary(
                                ComponentIds.encode(ComponentIds.SURFACE_PREFS, "discard"), "Discard"),
                        Button.danger(ComponentIds.encode(ComponentIds.SURFACE_PREFS, "silence-all",
                                state.selectedDataType()), "Silence everything"),
                        Button.danger(ComponentIds.encode(ComponentIds.SURFACE_PREFS, "silence-type",
                                state.selectedDataType()), "Silence this type")))
                .build();
    }

    /**
     * The pending-change line, plus whatever the player cannot otherwise see. A silenced type shows as
     * an empty media select, which is indistinguishable from a type they have simply not configured;
     * and the player-level mute has no button on this screen at all, so a muted player would otherwise
     * read a screen full of correct-looking media and still receive nothing.
     */
    private static @NotNull String description(@NotNull PreferenceView.State state) {
        StringBuilder text = new StringBuilder(state.pendingChanges() == 0
                ? "Choose how each kind of notification reaches you."
                : state.pendingChanges() + " pending change"
                        + (state.pendingChanges() == 1 ? "" : "s") + " — press Apply to save.");
        if (state.silenced()) {
            text.append("\n\nThis type is silenced: pick a delivery method above to hear about it again.");
        }
        if (state.muted()) {
            text.append("\n\nYou are muted, so nothing is being sent to you at all — whatever you"
                    + " choose here. Use `/notifications unmute` to lift it.");
        }
        return text.toString();
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
