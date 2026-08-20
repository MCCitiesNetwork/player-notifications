package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * The Discord {@code /notifications} command, minus {@code prefs} — that subcommand and every
 * component on its screen belong to {@link PreferenceInteractionListener}, which owns the staged
 * session.
 *
 * <p>A logic-free adapter over the unfiltered {@link InboxView}; the mail-filtered instance of the
 * same class serves {@code /mail}.
 */
public final class NotificationsCommandListener extends ListenerAdapter {

    private final DiscordUserResolver users;
    private final InboxView inbox;
    private final InboxMessageFactory messages;
    private final PreferenceView preferences;
    private final Executor asyncExecutor;
    private final Logger logger;

    public NotificationsCommandListener(@NotNull DiscordUserResolver users, @NotNull InboxView inbox,
                                        @NotNull InboxMessageFactory messages,
                                        @NotNull PreferenceView preferences,
                                        @NotNull Executor asyncExecutor, @NotNull Logger logger) {
        this.users = users;
        this.inbox = inbox;
        this.messages = messages;
        this.preferences = preferences;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!SlashCommandRegistrar.NOTIFICATIONS_COMMAND.equals(event.getName())) {
            return;
        }
        String subcommand = String.valueOf(event.getSubcommandName());
        if (subcommand.equals("prefs")) {
            return;
        }

        event.deferReply(true).queue();
        InteractionHook hook = event.getHook();
        InteractionSupport.async(this.asyncExecutor, hook, this.logger, () -> {
            Optional<UUID> player = this.users.resolve(event.getUser().getIdLong());
            if (player.isEmpty()) {
                InteractionSupport.reply(hook, DiscordUserResolver.NOT_LINKED_MESSAGE, this.logger);
                return;
            }
            handle(subcommand, player.get(), event, hook);
        });
    }

    private void handle(@NotNull String subcommand, @NotNull UUID player,
                        @NotNull SlashCommandInteractionEvent event, @NotNull InteractionHook hook) {
        switch (subcommand) {
            case "list" -> InteractionSupport.reply(hook,
                    this.messages.listing(this.inbox.page(player,
                            intOption(event, SlashCommandRegistrar.OPTION_PAGE, 1)),
                            ComponentIds.SURFACE_INBOX),
                    this.logger);
            case "read" -> {
                InboxView.ReadResult result = this.inbox.read(player,
                        intOption(event, SlashCommandRegistrar.OPTION_PAGE, 1),
                        intOption(event, SlashCommandRegistrar.OPTION_ENTRY, 1));
                if (result instanceof InboxView.ReadResult.Ok ok) {
                    InteractionSupport.reply(hook,
                            this.messages.detail(ok.row(), ComponentIds.SURFACE_INBOX), this.logger);
                } else {
                    InboxView.ReadResult.OutOfRange range = (InboxView.ReadResult.OutOfRange) result;
                    InteractionSupport.reply(hook,
                            InboxReplies.outOfRange(range.entry(), range.rowCount()), this.logger);
                }
            }
            case "dismiss" -> {
                InboxView.ActionResult result = this.inbox.dismiss(player,
                        intOption(event, SlashCommandRegistrar.OPTION_PAGE, 1),
                        intOption(event, SlashCommandRegistrar.OPTION_ENTRY, 1));
                InteractionSupport.reply(hook, InboxReplies.of(result), this.logger);
            }
            case "clear" -> InteractionSupport.reply(hook, InboxReplies.of(this.inbox.clear(player)),
                    this.logger);
            // Immediate, as /notifications mute is in game — the staged form is the button on the
            // preference screen.
            case "mute" -> {
                this.preferences.muteImmediately(player);
                InteractionSupport.reply(hook,
                        "Muted until you unmute. Nothing will interrupt you; your inbox still fills up.",
                        this.logger);
            }
            case "unmute" -> {
                this.preferences.unmuteImmediately(player);
                InteractionSupport.reply(hook,
                        "Unmuted. Your notification preferences are exactly as you left them; "
                                + "types you silenced stay silenced.", this.logger);
            }
            default -> InteractionSupport.reply(hook, "Unknown subcommand.", this.logger);
        }
    }

    private static int intOption(@NotNull SlashCommandInteractionEvent event, @NotNull String name,
                                 int fallback) {
        OptionMapping option = event.getOption(name);
        return option == null ? fallback : option.getAsInt();
    }
}
