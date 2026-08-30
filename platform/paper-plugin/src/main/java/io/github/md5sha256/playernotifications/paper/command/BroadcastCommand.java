package io.github.md5sha256.playernotifications.paper.command;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.md5sha256.playernotifications.paper.broadcast.BroadcastArguments;
import io.github.md5sha256.playernotifications.paper.broadcast.BroadcastAudience;
import io.github.md5sha256.playernotifications.paper.broadcast.Broadcaster;
import io.github.md5sha256.playernotifications.paper.broadcast.PersistentBroadcaster;
import io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationPayload;
import io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationTypes;
import io.github.md5sha256.playernotifications.paper.customtype.DeclaredNotificationType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * {@code /broadcast <content> [--perm <node>]… [--chain and|or] [--persistent] [--offline]
 * [--limit <n>] [--type <key>] [--bypass]}: parses and validates on the command thread, then resolves and fans out
 * asynchronously. Any {@link CommandSender} may use it, console included — unlike
 * {@code /notifications}/{@code /mail}, it acts on other players, not on the sender's own inbox.
 *
 * <p><b>Resolution now happens inside the async task, not on the command thread.</b> That inverts the
 * original rule, because {@code OfflineBroadcastAudience} blocks on a permission backend and must not
 * run on the main thread. {@code OnlineBroadcastAudience} still needs the main thread for
 * {@code Player#hasPermission} and marshals back onto it internally, so each audience owns its own
 * threading requirement and this class has one code path rather than a branch.
 *
 * <p>The fan-out runs asynchronously for the reason it always did: it does blocking JDBC and can reach
 * {@code DiscordDmSink}, which refuses the main thread outright.
 *
 * <p><b>The async order is fixed:</b> resolve, then the {@code --limit} check, then the empty-audience
 * check, then the fan-out — so a refused command is indistinguishable from one never run, apart from
 * the queries it took to count. See {@link #send}.
 */
public final class BroadcastCommand {

    public static final String PERMISSION = "playernotifications.command.broadcast";
    public static final String DESCRIPTION = "Broadcast a message to online players";

    private static final String CONTENT_ARGUMENT = "content";

    private BroadcastCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull MessageContainer messages,
                                                                  @NotNull Plugin plugin,
                                                                  @NotNull Broadcaster broadcaster,
                                                                  @NotNull PersistentBroadcaster persistentBroadcaster,
                                                                  @NotNull BroadcastAudience onlineAudience,
                                                                  @Nullable BroadcastAudience offlineAudience,
                                                                  @NotNull CustomNotificationTypes customTypes) {
        return Commands.literal("broadcast")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument(CONTENT_ARGUMENT, StringArgumentType.greedyString())
                        .executes(context -> run(messages, context, plugin, broadcaster,
                                persistentBroadcaster, onlineAudience, offlineAudience,
                                customTypes)))
                .build();
    }

    private static int run(@NotNull MessageContainer messages,
                            @NotNull CommandContext<CommandSourceStack> context, @NotNull Plugin plugin,
                            @NotNull Broadcaster broadcaster,
                            @NotNull PersistentBroadcaster persistentBroadcaster,
                            @NotNull BroadcastAudience onlineAudience,
                            @Nullable BroadcastAudience offlineAudience,
                            @NotNull CustomNotificationTypes customTypes) {
        CommandSender sender = context.getSource().getSender();
        String raw = StringArgumentType.getString(context, CONTENT_ARGUMENT);

        BroadcastArguments.Result parsed = BroadcastArguments.parse(raw);
        switch (parsed) {
            case BroadcastArguments.Result.BlankContent ignored -> {
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_BLANK_CONTENT));
                return 0;
            }
            case BroadcastArguments.Result.FlagMissingValue missing -> {
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_FLAG_MISSING_VALUE,
                        MessageContainer.value("flag", missing.flag())));
                return 0;
            }
            case BroadcastArguments.Result.UnrecognisedToken unrecognised -> {
                // value(): the token is whatever the sender typed, so it must stay literal.
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_UNRECOGNISED_TOKEN,
                        MessageContainer.value("token", unrecognised.token())));
                return 0;
            }
            case BroadcastArguments.Result.UnknownChainValue unknown -> {
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_UNKNOWN_CHAIN,
                        MessageContainer.value("value", unknown.value())));
                return 0;
            }
            case BroadcastArguments.Result.InvalidLimitValue invalid -> {
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_INVALID_LIMIT,
                        MessageContainer.value("value", invalid.value())));
                return 0;
            }
            case BroadcastArguments.Result.OfflineRequiresPersistent ignored -> {
                sender.sendMessage(
                        messages.messageFor(MessageKeys.BROADCAST_OFFLINE_REQUIRES_PERSISTENT));
                return 0;
            }
            case BroadcastArguments.Result.OfflineRequiresPermission ignored -> {
                sender.sendMessage(
                        messages.messageFor(MessageKeys.BROADCAST_OFFLINE_REQUIRES_PERMISSION));
                return 0;
            }
            case BroadcastArguments.Result.Parsed ignored -> {
                // Fall through to the send below.
            }
        }
        BroadcastArguments arguments = ((BroadcastArguments.Result.Parsed) parsed).arguments();

        // Resolved on the command thread, before anything is queried: an unknown type costs the
        // sender a reply and the server nothing.
        DeclaredNotificationType declaredType = null;
        if (arguments.type() != null) {
            declaredType = customTypes.get(arguments.type()).orElse(null);
            if (declaredType == null) {
                // value(): the key is whatever the sender typed, so it must stay literal.
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_UNKNOWN_TYPE,
                        MessageContainer.value("type", arguments.type())));
                return 0;
            }
        }

        Component content;
        try {
            content = MiniMessage.miniMessage().deserialize(arguments.content());
        } catch (RuntimeException e) {
            sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_PARSE_FAILED,
                    MessageContainer.value("error", String.valueOf(e.getMessage()))));
            return 0;
        }

        if (arguments.offline() && offlineAudience == null) {
            sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_OFFLINE_UNAVAILABLE));
            return 0;
        }
        BroadcastAudience audience = arguments.offline() ? offlineAudience : onlineAudience;

        // Everything from here blocks: resolve queries a permission backend (or marshals to the main
        // thread), and the fan-out does JDBC and can reach DiscordDmSink, which refuses the main thread.
        DeclaredNotificationType type = declaredType;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> send(messages, sender, arguments, content, audience, broadcaster,
                        persistentBroadcaster, type));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The async half. Order is fixed and matters: resolve, then the {@code --limit} check, then the
     * empty-audience check, then the fan-out — so a refused command is indistinguishable from one never
     * run, apart from the queries it took to count.
     */
    private static void send(@NotNull MessageContainer messages, @NotNull CommandSender sender,
                             @NotNull BroadcastArguments arguments, @NotNull Component content,
                             @NotNull BroadcastAudience audience, @NotNull Broadcaster broadcaster,
                             @NotNull PersistentBroadcaster persistentBroadcaster,
                             @Nullable DeclaredNotificationType declaredType) {
        List<UUID> recipients;
        try {
            recipients = audience.resolve(arguments.permissions(), arguments.chain());
        } catch (RuntimeException e) {
            // A partial audience is worse than a failed command here: the recipients it would miss are
            // not present to notice they were missed. Its own key, not parse-failed — that one blames
            // the sender's MiniMessage, and a permission backend being down is the server's problem.
            sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_LOOKUP_FAILED,
                    MessageContainer.value("error", String.valueOf(e.getMessage()))));
            return;
        }

        Integer limit = arguments.limit();
        if (limit != null && recipients.size() > limit) {
            // Naming the real count is what makes this a confirmation rather than a wall — the operator
            // types that number back as --limit to send anyway.
            sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_LIMIT_EXCEEDED,
                    MessageContainer.value("count", String.valueOf(recipients.size())),
                    MessageContainer.value("limit", String.valueOf(limit))));
            return;
        }

        if (recipients.isEmpty()) {
            sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_NO_AUDIENCE));
            return;
        }

        if (arguments.persistent()) {
            PersistentBroadcaster.Result result = declaredType == null
                    ? persistentBroadcaster.broadcast(content, arguments.content(), recipients,
                            arguments.bypass())
                    : persistentBroadcaster.broadcast(title(declaredType), content,
                            declaredType.key(),
                            new CustomNotificationPayload(declaredType.key(), arguments.content()),
                            recipients, arguments.bypass());
            sender.sendMessage(messages.messageFor(
                    result.stored() == 1 ? MessageKeys.BROADCAST_STORED_ONE
                            : MessageKeys.BROADCAST_STORED_MANY,
                    MessageContainer.value("stored", String.valueOf(result.stored())),
                    MessageContainer.value("attempted", String.valueOf(result.attempted()))));
            if (result.failed() > 0) {
                // Reported separately because "attempted 0" would otherwise read the same whether
                // nobody was reachable or every delivery blew up.
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_PUSH_FAILED,
                        MessageContainer.value("count", String.valueOf(result.failed()))));
            }
            if (result.bypassed() > 0) {
                sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_BYPASSED,
                        MessageContainer.value("count", String.valueOf(result.bypassed()))));
            }
            return;
        }

        int attempted = declaredType == null
                ? broadcaster.broadcast(content, recipients, arguments.bypass())
                : broadcaster.broadcast(title(declaredType), content, declaredType.key(), recipients,
                        arguments.bypass());
        if (attempted == 0) {
            sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_NOTHING_ENABLED));
        } else {
            sender.sendMessage(messages.messageFor(MessageKeys.BROADCAST_SENT,
                    MessageContainer.value("count", String.valueOf(attempted))));
        }
    }

    /**
     * The declared title, parsed fresh per send so a reloaded {@code notification-types.yml} takes
     * effect. {@code CustomNotificationTypes.load} already proved it parses.
     */
    @NotNull
    private static Component title(@NotNull DeclaredNotificationType declaredType) {
        return MiniMessage.miniMessage().deserialize(declaredType.title());
    }
}
