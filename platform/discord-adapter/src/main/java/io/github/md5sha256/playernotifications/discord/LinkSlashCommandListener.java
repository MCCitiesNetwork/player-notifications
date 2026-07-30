package io.github.md5sha256.playernotifications.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Discord half of the account-link flow: a {@code /link <code>} slash command that redeems a code
 * issued in game by {@code /discordlink}.
 *
 * <p>An interaction rather than the bot reading a DM's text, because message <em>content</em> needs the
 * privileged {@code MESSAGE_CONTENT} intent while interactions need no intent at all. That keeps
 * {@link DiscordBot} on {@code createLight} with an empty intent set, and spares the operator a toggle in
 * the Discord developer portal.
 *
 * <p>A thin adapter over {@link DiscordLinkFlow}, which holds every decision — this class cannot be unit
 * tested without a live bot, so it deliberately contains no logic beyond mapping a
 * {@link DiscordLinkFlow.RedeemResult} to a reply.
 */
public final class LinkSlashCommandListener extends ListenerAdapter {

    /** The slash command players type in Discord. */
    public static final String COMMAND_NAME = "link";

    /** The name of its single required option. */
    public static final String CODE_OPTION = "code";

    private final DiscordLinkFlow flow;
    private final Executor asyncExecutor;
    private final Logger logger;

    public LinkSlashCommandListener(@NotNull DiscordLinkFlow flow,
                                    @NotNull Executor asyncExecutor,
                                    @NotNull Logger logger) {
        this.flow = flow;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        // Registered from onReady because awaitReady() is never called, so this is the only point at which
        // the connection is known to be up. Globally, and with the bot-DM context included, since players
        // link from a DM with the bot rather than in a guild channel.
        event.getJDA().updateCommands()
                .addCommands(Commands.slash(COMMAND_NAME, "Link your Minecraft account to this Discord account")
                        .addOption(OptionType.STRING, CODE_OPTION,
                                "The code shown by /discordlink in game", true)
                        .setContexts(InteractionContextType.BOT_DM, InteractionContextType.GUILD))
                .queue(
                        success -> this.logger.info("Registered the Discord /" + COMMAND_NAME
                                + " slash command for account linking"),
                        // A failure here leaves DMs working for players who are already linked, so it is a
                        // warning rather than a module failure.
                        failure -> this.logger.log(Level.WARNING, "Could not register the Discord /"
                                + COMMAND_NAME + " slash command; in-game linking will not work", failure));
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!COMMAND_NAME.equals(event.getName())) {
            return;
        }

        OptionMapping option = event.getOption(CODE_OPTION);
        if (option == null) {
            // Discord enforces the required option, so this is unreachable in practice — but replying is
            // cheaper than leaving an interaction to time out visibly.
            event.reply("No code was supplied.").setEphemeral(true).queue();
            return;
        }
        String code = option.getAsString();
        long discordId = event.getUser().getIdLong();

        // Ephemeral: the code and the resulting link are the player's business, and the interaction may
        // happen in a guild channel.
        event.deferReply(true).queue();
        // Redemption does blocking JDBC, and this is a JDA event thread.
        this.asyncExecutor.execute(() -> {
            String reply;
            try {
                reply = replyFor(this.flow.redeem(code, discordId));
            } catch (RuntimeException exception) {
                // DiscordLinkFlow already swallows store failures; this is belt and braces, because an
                // exception escaping here would leave the interaction hanging with no reply at all.
                this.logger.log(Level.WARNING, "Unexpected failure redeeming a Discord link code", exception);
                reply = "Something went wrong. Please try again.";
            }
            event.getHook().sendMessage(reply).setEphemeral(true).queue(
                    success -> {
                    },
                    failure -> this.logger.log(Level.FINE, "Could not deliver a link reply", failure));
        });
    }

    private static @NotNull String replyFor(@NotNull DiscordLinkFlow.RedeemResult result) {
        return switch (result) {
            case LINKED -> "Linked. Notifications you have set to Discord will arrive here.";
            // Unknown and expired are one message: the code service removes an expired entry on redeem, so
            // there is nothing left to tell them apart by.
            case UNKNOWN_CODE -> "That code is not valid or has expired. Run /discordlink in game for a new one.";
            case ALREADY_LINKED_TO_THIS_ACCOUNT -> "This Discord account is already linked to that player.";
            case FAILED -> "Something went wrong. Run /discordlink in game for a new code and try again.";
        };
    }
}
