package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * The Discord {@code /mail} command: send, list, read, dismiss and clear, mirroring the in-game
 * command's shape. {@code send} takes the recipient as a slash option and the message in a modal.
 *
 * <p>A logic-free adapter, like {@link io.github.md5sha256.playernotifications.discord.LinkSlashCommandListener}
 * — every rule lives in {@link DiscordMailService} and {@link InboxView}, which are unit tested, and
 * nothing in this class can be. The command itself is declared by {@link SlashCommandRegistrar}.
 */
public final class MailCommandListener extends ListenerAdapter {

    /** The modal's body field. */
    static final String COMPOSE_BODY = "body";

    /** What a bare {@code /mail} does — the same thing the in-game command does. */
    static final String DEFAULT_SUBCOMMAND = "list";

    private final DiscordUserResolver users;
    private final DiscordMailService mail;
    private final InboxView inbox;
    private final InboxMessageFactory messages;
    private final Executor asyncExecutor;
    private final Logger logger;

    public MailCommandListener(@NotNull DiscordUserResolver users, @NotNull DiscordMailService mail,
                               @NotNull InboxView inbox, @NotNull InboxMessageFactory messages,
                               @NotNull Executor asyncExecutor, @NotNull Logger logger) {
        this.users = users;
        this.mail = mail;
        this.inbox = inbox;
        this.messages = messages;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!SlashCommandRegistrar.MAIL_COMMAND.equals(event.getName())) {
            return;
        }
        // A command declaring subcommands cannot be invoked bare in Discord today, but if that ever
        // changes a bare /mail should show the mailbox, as the in-game command does.
        String subcommand = event.getSubcommandName() == null
                ? DEFAULT_SUBCOMMAND
                : event.getSubcommandName();

        if (subcommand.equals("send")) {
            // A modal has to be the *initial* response to the interaction, so this branch cannot
            // defer — the link check therefore happens on submit, in onModalInteraction.
            event.replyModal(composeModal(stringOption(event, SlashCommandRegistrar.OPTION_PLAYER)))
                    .queue();
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

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        Optional<ComponentIds.Parsed> parsed = ComponentIds.parse(event.getModalId());
        if (parsed.isEmpty() || !parsed.get().surface().equals(SlashCommandRegistrar.MAIL_COMMAND)) {
            return;
        }
        String recipient = parsed.get().arg(0).orElse("");
        ModalMapping bodyField = event.getValue(COMPOSE_BODY);
        String body = bodyField == null ? "" : bodyField.getAsString();

        event.deferReply(true).queue();
        InteractionHook hook = event.getHook();
        InteractionSupport.async(this.asyncExecutor, hook, this.logger, () -> {
            Optional<UUID> player = this.users.resolve(event.getUser().getIdLong());
            if (player.isEmpty()) {
                InteractionSupport.reply(hook, DiscordUserResolver.NOT_LINKED_MESSAGE, this.logger);
                return;
            }
            InteractionSupport.reply(hook, replyFor(this.mail.send(player.get(), recipient, body)),
                    this.logger);
        });
    }

    private void handle(@NotNull String subcommand, @NotNull UUID player,
                        @NotNull SlashCommandInteractionEvent event, @NotNull InteractionHook hook) {
        switch (subcommand) {
            case "list" -> InteractionSupport.reply(hook,
                    this.messages.listing(this.inbox.page(player, intOption(event,
                            SlashCommandRegistrar.OPTION_PAGE, 1)), ComponentIds.SURFACE_INBOX_MAIL),
                    this.logger);
            case "read" -> {
                InboxView.ReadResult result = this.inbox.read(player,
                        intOption(event, SlashCommandRegistrar.OPTION_PAGE, 1),
                        intOption(event, SlashCommandRegistrar.OPTION_ENTRY, 1));
                if (result instanceof InboxView.ReadResult.Ok ok) {
                    InteractionSupport.reply(hook,
                            this.messages.detail(ok.row(), ComponentIds.SURFACE_INBOX_MAIL), this.logger);
                } else {
                    InboxView.ReadResult.OutOfRange range = (InboxView.ReadResult.OutOfRange) result;
                    InteractionSupport.reply(hook,
                            InboxReplies.outOfRange(range.entry(), range.rowCount()), this.logger);
                }
            }
            case "delete" -> {
                InboxView.ActionResult result = this.inbox.dismiss(player,
                        intOption(event, SlashCommandRegistrar.OPTION_PAGE, 1),
                        intOption(event, SlashCommandRegistrar.OPTION_ENTRY, 1));
                InteractionSupport.reply(hook, InboxReplies.of(result), this.logger);
            }
            case "clear" -> InteractionSupport.reply(hook, InboxReplies.of(this.inbox.clear(player)),
                    this.logger);
            default -> InteractionSupport.reply(hook, "Unknown subcommand.", this.logger);
        }
    }

    private static @NotNull Modal composeModal(@NotNull String recipient) {
        TextInput body = TextInput.create(COMPOSE_BODY, TextInputStyle.PARAGRAPH)
                .setPlaceholder("What would you like to say?")
                .setMaxLength(MailPayload.MAX_MESSAGE_LENGTH)
                .setRequired(true)
                .build();
        return Modal.create(ComponentIds.encode(SlashCommandRegistrar.MAIL_COMMAND, "send", recipient),
                        "Mail to " + recipient)
                .addComponents(Label.of("Message", body))
                .build();
    }

    private static @NotNull String replyFor(@NotNull DiscordMailService.SendResult result) {
        return switch (result) {
            case DiscordMailService.SendResult.Ok ok -> "Mail sent to " + ok.recipientName() + ".";
            case DiscordMailService.SendResult.Rejected rejected -> rejected.reason();
            case DiscordMailService.SendResult.Failed failed -> failed.reason();
        };
    }

    private static @NotNull String stringOption(@NotNull SlashCommandInteractionEvent event,
                                                @NotNull String name) {
        OptionMapping option = event.getOption(name);
        return option == null ? "" : option.getAsString();
    }

    private static int intOption(@NotNull SlashCommandInteractionEvent event, @NotNull String name,
                                 int fallback) {
        OptionMapping option = event.getOption(name);
        return option == null ? fallback : option.getAsInt();
    }
}
