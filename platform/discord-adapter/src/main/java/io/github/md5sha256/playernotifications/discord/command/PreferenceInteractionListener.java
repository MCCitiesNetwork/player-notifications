package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * {@code /notifications prefs} and every component on the screen it posts.
 *
 * <p>Component interactions {@code deferEdit()} and replace the message they were on, so the staged
 * state a player is looking at stays in one place instead of accumulating down the channel. Every
 * decision — what is staged, what Apply writes, when a session is gone — is in {@link PreferenceView}.
 */
public final class PreferenceInteractionListener extends ListenerAdapter {

    private final DiscordUserResolver users;
    private final PreferenceView preferences;
    private final PreferenceMessageFactory messages;
    private final Executor asyncExecutor;
    private final Logger logger;

    public PreferenceInteractionListener(@NotNull DiscordUserResolver users,
                                         @NotNull PreferenceView preferences,
                                         @NotNull PreferenceMessageFactory messages,
                                         @NotNull Executor asyncExecutor, @NotNull Logger logger) {
        this.users = users;
        this.preferences = preferences;
        this.messages = messages;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!SlashCommandRegistrar.NOTIFICATIONS_COMMAND.equals(event.getName())
                || !"prefs".equals(event.getSubcommandName())) {
            return;
        }
        event.deferReply(true).queue();
        InteractionHook hook = event.getHook();
        withPlayer(hook, event.getUser().getIdLong(), player -> InteractionSupport.reply(hook,
                this.messages.preferences(this.preferences.open(player, null)), this.logger));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        Optional<ComponentIds.Parsed> parsed = ours(String.valueOf(event.getComponentId()));
        if (parsed.isEmpty()) {
            return;
        }
        event.deferEdit().queue();
        InteractionHook hook = event.getHook();
        String dataType = parsed.get().arg(0).orElse("");
        withPlayer(hook, event.getUser().getIdLong(), player -> {
            switch (parsed.get().action()) {
                // Apply and Discard both end the session, so the screen has to be rebuilt from storage
                // rather than from the state the player was looking at.
                case "apply" -> {
                    String reply = this.preferences.apply(player);
                    InteractionSupport.edit(hook,
                            this.messages.preferences(this.preferences.open(player, dataType)), this.logger);
                    InteractionSupport.reply(hook, reply, this.logger);
                }
                case "discard" -> {
                    String reply = this.preferences.discard(player);
                    InteractionSupport.edit(hook,
                            this.messages.preferences(this.preferences.open(player, dataType)), this.logger);
                    InteractionSupport.reply(hook, reply, this.logger);
                }
                // Staged, not immediate: the immediate form is /notifications mute.
                case "mute" -> InteractionSupport.edit(hook,
                        this.messages.preferences(this.preferences.setMuted(player, true, dataType)),
                        this.logger);
                case "unmute" -> InteractionSupport.edit(hook,
                        this.messages.preferences(this.preferences.setMuted(player, false, dataType)),
                        this.logger);
                default -> this.logger.fine(() -> "Unknown preference action: " + parsed.get().action());
            }
        });
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        Optional<ComponentIds.Parsed> parsed = ours(String.valueOf(event.getComponentId()));
        if (parsed.isEmpty()) {
            return;
        }
        event.deferEdit().queue();
        InteractionHook hook = event.getHook();
        List<String> values = List.copyOf(event.getValues());
        withPlayer(hook, event.getUser().getIdLong(), player -> {
            switch (parsed.get().action()) {
                case "type" -> {
                    if (values.isEmpty()) {
                        return;
                    }
                    InteractionSupport.edit(hook,
                            this.messages.preferences(this.preferences.selectDataType(player, values.get(0))),
                            this.logger);
                }
                // The data type is in the select's own id, so an empty selection — which is how a player
                // silences one type — still knows which type it applies to.
                case "media" -> parsed.get().arg(0).ifPresent(dataType ->
                        InteractionSupport.edit(hook, this.messages.preferences(
                                        this.preferences.setMedia(player, dataType, Set.copyOf(values))),
                                this.logger));
                default -> this.logger.fine(() -> "Unknown preference action: " + parsed.get().action());
            }
        });
    }

    private @NotNull Optional<ComponentIds.Parsed> ours(@NotNull String componentId) {
        return ComponentIds.parse(componentId)
                .filter(parsed -> parsed.surface().equals(ComponentIds.SURFACE_PREFS));
    }

    private void withPlayer(@NotNull InteractionHook hook, long discordId,
                            @NotNull java.util.function.Consumer<UUID> action) {
        InteractionSupport.async(this.asyncExecutor, hook, this.logger, () -> {
            Optional<UUID> player = this.users.resolve(discordId);
            if (player.isEmpty()) {
                InteractionSupport.reply(hook, DiscordUserResolver.NOT_LINKED_MESSAGE, this.logger);
                return;
            }
            action.accept(player.get());
        });
    }
}
