package io.github.md5sha256.playernotifications.discord.command;

import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import io.github.md5sha256.playernotifications.discord.DiscordLinkFlow;
import io.github.md5sha256.playernotifications.discord.LinkSlashCommandListener;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single owner of this bot's slash-command registration.
 *
 * <p>{@code JDA#updateCommands()} <em>replaces</em> the whole global command set, so registration
 * cannot be spread across the listeners that handle each command: whichever listener ran last would
 * silently delete the others' commands. Every command is therefore declared here and registered in one
 * call; the feature listeners handle interactions only.
 *
 * <p>Registered from {@code onReady} because {@code awaitReady()} is never called (see
 * {@link io.github.md5sha256.playernotifications.discord.DiscordBot}), so this is the only point at
 * which the connection is known to be up.
 */
public final class SlashCommandRegistrar extends ListenerAdapter {

    /** The {@code /mail} root, matching the in-game command name exactly. */
    public static final String MAIL_COMMAND = "mail";

    /** The {@code /notifications} root, matching the in-game command name exactly. */
    public static final String NOTIFICATIONS_COMMAND = "notifications";

    public static final String OPTION_PLAYER = "player";
    public static final String OPTION_MESSAGE = "message";
    public static final String OPTION_ENTRY = "entry";
    public static final String OPTION_PAGE = "page";

    private final boolean commandsEnabled;
    private final boolean linkingEnabled;
    private final Logger logger;

    public SlashCommandRegistrar(boolean commandsEnabled, boolean linkingEnabled, @NotNull Logger logger) {
        this.commandsEnabled = commandsEnabled;
        this.linkingEnabled = linkingEnabled;
        this.logger = logger;
    }

    /**
     * The command set that would be registered for these toggles.
     *
     * <p>Separated from {@link #onReady} so it can be asserted without a connection — the rest of this
     * class cannot be tested at all.
     */
    static @NotNull List<CommandData> commandData(boolean commandsEnabled, boolean linkingEnabled) {
        List<CommandData> commands = new ArrayList<>(3);
        if (linkingEnabled) {
            commands.add(link());
        }
        if (commandsEnabled) {
            commands.add(mail());
            commands.add(notifications());
        }
        return commands;
    }

    private static @NotNull SlashCommandData link() {
        return contexts(Commands.slash(LinkSlashCommandListener.COMMAND_NAME,
                        "Link your Minecraft account to this Discord account")
                .addOption(OptionType.STRING, LinkSlashCommandListener.CODE_OPTION,
                        "The code shown by " + DiscordLinkFlow.LINK_COMMAND + " in game", true));
    }

    private static @NotNull SlashCommandData mail() {
        return contexts(Commands.slash(MAIL_COMMAND, "Read and send in-game mail")
                .addSubcommands(
                        new SubcommandData("send", "Send mail to a player")
                                .addOption(OptionType.STRING, OPTION_PLAYER, "Who to send it to", true)
                                .addOptions(new OptionData(OptionType.STRING, OPTION_MESSAGE,
                                        "The message; sent as plain text", true)
                                        .setMaxLength(MailPayload.MAX_MESSAGE_LENGTH)),
                        new SubcommandData("compose", "Write mail to a player in a pop-up box")
                                .addOption(OptionType.STRING, OPTION_PLAYER, "Who to send it to", true),
                        listSubcommand("Show your mail"),
                        entrySubcommand("read", "Read one mail"),
                        entrySubcommand("dismiss", "Dismiss one mail"),
                        new SubcommandData("clear", "Dismiss all of your mail")));
    }

    private static @NotNull SlashCommandData notifications() {
        return contexts(Commands.slash(NOTIFICATIONS_COMMAND, "Read your notifications and set your preferences")
                .addSubcommands(
                        listSubcommand("Show your notifications"),
                        entrySubcommand("read", "Read one notification"),
                        entrySubcommand("dismiss", "Dismiss one notification"),
                        new SubcommandData("clear", "Dismiss all of your notifications"),
                        new SubcommandData("prefs", "Choose how your notifications reach you"),
                        new SubcommandData("mute",
                                "Temporarily stop all notifications from interrupting you"),
                        new SubcommandData("unmute", "Let notifications interrupt you again")));
    }

    private static @NotNull SubcommandData listSubcommand(@NotNull String description) {
        return new SubcommandData("list", description)
                .addOption(OptionType.INTEGER, OPTION_PAGE, "Which page to show", false);
    }

    /**
     * A subcommand acting on one row of a page. The page is an explicit option rather than a stored
     * cursor, which is what keeps this surface stateless — nothing to expire, and no way for a stale
     * listing to resolve an entry against a page the player is no longer looking at.
     */
    private static @NotNull SubcommandData entrySubcommand(@NotNull String name, @NotNull String description) {
        return new SubcommandData(name, description)
                .addOption(OptionType.INTEGER, OPTION_ENTRY, "Which row of the page", true)
                .addOption(OptionType.INTEGER, OPTION_PAGE, "Which page that row is on (default 1)", false);
    }

    /** Players use these from a DM with the bot; a guild channel is allowed since replies are ephemeral. */
    private static @NotNull SlashCommandData contexts(@NotNull SlashCommandData command) {
        return command.setContexts(InteractionContextType.BOT_DM, InteractionContextType.GUILD);
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        List<CommandData> commands = commandData(this.commandsEnabled, this.linkingEnabled);
        event.getJDA().updateCommands().addCommands(commands).queue(
                success -> this.logger.info("Registered " + commands.size()
                        + " Discord slash command(s) for PlayerNotifications"),
                // A failure here leaves delivery working for players who are already linked, so it is a
                // warning rather than a module failure.
                failure -> this.logger.log(Level.WARNING,
                        "Could not register the PlayerNotifications Discord slash commands;"
                                + " in-game linking and the Discord commands will not work", failure));
    }
}
