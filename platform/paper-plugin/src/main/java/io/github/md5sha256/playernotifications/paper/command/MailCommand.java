package io.github.md5sha256.playernotifications.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.md5sha256.playernotifications.paper.inbox.InboxRouter;
import io.github.md5sha256.playernotifications.paper.mail.MailFormatting;
import io.github.md5sha256.playernotifications.paper.mail.MailNotifier;
import io.github.md5sha256.playernotifications.paper.mail.MailRecipients;
import io.github.md5sha256.playernotifications.paper.mail.MailSender;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The player-facing {@code /mail} command: a second, {@code mail}-filtered view over the same inbox
 * machinery {@code /notifications} uses, plus {@code send}.
 *
 * <p>The bare root opens the mail-filtered inbox dialog; {@code list}/{@code read}/{@code dismiss}/
 * {@code clear} are its chat fallback, mirroring {@code NotificationsCommand}'s shape exactly. Those
 * branches are player-only — they act on <i>your</i> inbox, and the console has none. {@code send} is
 * not: it acts on someone else's inbox, so it runs for any {@code CommandSender} and attributes a
 * non-player one to {@code MailSender.SERVER_SENDER}.
 *
 * <p>Every branch dispatches off the main thread — all of it does blocking JDBC, and {@code send}
 * additionally resolves an offline recipient through {@code Bukkit.getOfflinePlayer}, which can touch
 * disk.
 */
public final class MailCommand {

    public static final String PERMISSION = "playernotifications.command.mail";

    /**
     * Gates {@code send} on top of the root {@link #PERMISSION}, the same additive-gate shape
     * {@code NotificationsCommand.LINK_PERMISSION} uses: a server can make mail read-only for a rank
     * without hiding the inbox. Brigadier {@code requires} nest, so revoking the root hides {@code send}
     * too.
     */
    public static final String SEND_PERMISSION = "playernotifications.command.mail.send";

    public static final String DESCRIPTION = "Read and send mail";

    private static final Component PLAYERS_ONLY =
            Component.text("Only players can use mail.", NamedTextColor.RED);

    private static final String PAGE_ARGUMENT = "page";
    private static final String INDEX_ARGUMENT = "entry";
    private static final String PLAYER_ARGUMENT = "player";
    private static final String MESSAGE_ARGUMENT = "message";

    private MailCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull Plugin plugin,
                                                                 @NotNull InboxRouter mailRouter,
                                                                 @NotNull MailSender mailSender,
                                                                 @NotNull MailNotifier mailNotifier) {
        return Commands.literal("mail")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> run(context, player -> mailRouter.openInbox(player, 1)))
                .then(Commands.literal("list")
                        .executes(context -> run(context, player -> mailRouter.listInChat(player, 1)))
                        .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                                .executes(context -> run(context, player -> mailRouter.listInChat(
                                        player, IntegerArgumentType.getInteger(context, PAGE_ARGUMENT))))))
                .then(Commands.literal("read")
                        .then(Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(1))
                                .executes(context -> run(context, player -> mailRouter.readInChat(
                                        player, IntegerArgumentType.getInteger(context, INDEX_ARGUMENT))))))
                .then(Commands.literal("dismiss")
                        .then(Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(1))
                                .executes(context -> run(context, player -> mailRouter.dismissInChat(
                                        player, IntegerArgumentType.getInteger(context, INDEX_ARGUMENT))))))
                .then(Commands.literal("clear")
                        .executes(context -> run(context, mailRouter::clearInChat)))
                .then(sendNode(plugin, mailSender, mailNotifier))
                .build();
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> sendNode(
            @NotNull Plugin plugin, @NotNull MailSender mailSender, @NotNull MailNotifier mailNotifier) {
        return Commands.literal("send")
                .requires(source -> source.getSender().hasPermission(SEND_PERMISSION))
                .then(Commands.argument(PLAYER_ARGUMENT, StringArgumentType.word())
                        .suggests((context, builder) -> {
                            Bukkit.getOnlinePlayers().forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                                .executes(context -> send(context, plugin, mailSender, mailNotifier))));
    }

    /**
     * Unlike every other branch, this one accepts a non-player sender: mail is written <i>to</i> someone
     * else, so the console has a use for it even though it has no inbox of its own. A non-player sender
     * is attributed to {@link MailSender#SERVER_SENDER}.
     *
     * <p>The tag resolver is built <b>here</b>, on the command thread, rather than inside the async task:
     * it reads the sender's permissions, and Bukkit's permission state belongs to the main thread. The
     * resolved {@code TagResolver} is then just data the task closes over.
     */
    private static int send(@NotNull CommandContext<CommandSourceStack> context, @NotNull Plugin plugin,
                            @NotNull MailSender mailSender, @NotNull MailNotifier mailNotifier) {
        String name = StringArgumentType.getString(context, PLAYER_ARGUMENT);
        String message = StringArgumentType.getString(context, MESSAGE_ARGUMENT);
        CommandSender sender = context.getSource().getSender();
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : MailSender.SERVER_SENDER;
        String senderName = sender instanceof Player player ? player.getName() : MailSender.SERVER_NAME;
        TagResolver allowedTags = MailFormatting.resolverFor(sender::hasPermission);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MailRecipients.Result result = MailRecipients.resolve(name, message,
                    MailCommand::resolveRecipient, raw -> MailFormatting.sanitize(raw, allowedTags));
            switch (result) {
                case MailRecipients.Result.Ok ok -> {
                    mailSender.send(senderId, senderName, ok.recipient(), ok.message());
                    sender.sendMessage(Component.text("Mail sent to " + name + ".", NamedTextColor.GREEN));
                    // Unconditional: Discord DM reaches the recipient whether or not they're online, and
                    // MailNotifier already resolves what can reach them.
                    mailNotifier.notifyArrival(ok.recipient());
                }
                case MailRecipients.Result.UnknownPlayer unknown -> sender.sendMessage(Component.text(
                        "Unknown player: " + unknown.name(), NamedTextColor.RED));
                case MailRecipients.Result.InvalidMessage invalid -> sender.sendMessage(Component.text(
                        invalid.reason(), NamedTextColor.RED));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * An online player by name first; otherwise {@code Bukkit.getOfflinePlayer(name)}, accepted only
     * when {@code hasPlayedBefore()} is true; otherwise unknown. The {@code hasPlayedBefore} check is
     * essential — {@code getOfflinePlayer(String)} fabricates a UUID for any string, so without it a
     * typo would silently mail a player who cannot exist.
     */
    private static UUID resolveRecipient(@NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    private static int run(@NotNull CommandContext<CommandSourceStack> context, @NotNull Consumer<Player> action) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return 0;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
