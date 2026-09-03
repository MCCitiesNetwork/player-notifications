package io.github.md5sha256.playernotifications.paper.command;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.md5sha256.playernotifications.paper.broadcast.Broadcaster;
import io.github.md5sha256.playernotifications.paper.broadcast.PersistentBroadcaster;
import io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationPayload;
import io.github.md5sha256.playernotifications.paper.customtype.CustomNotificationTypes;
import io.github.md5sha256.playernotifications.paper.customtype.DeclaredNotificationType;
import io.github.md5sha256.playernotifications.paper.localisation.MessageKeys;
import io.github.md5sha256.playernotifications.paper.send.SendArguments;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /notifications send <player> <type> <content> [--transient]}: one operator-declared
 * notification to one named player.
 *
 * <p>It is a second <em>client</em> onto the machinery {@code /broadcast --type} already drives —
 * {@link PersistentBroadcaster}, {@link Broadcaster} and {@link CustomNotificationPayload} — rather than
 * a registry extension, which is why it adds no payload class, renderer, sink, category or schema. Its
 * whole contribution is the argument shape and the replies.
 *
 * <p><b>Persistent by default</b>, unlike {@code /broadcast}. The audiences differ: a broadcast's
 * "whoever is online and holds the node right now" does not survive being written down, whereas a named
 * player does — so the useful default here is the one that survives being missed. {@code --transient}
 * opts out for a nudge to a player who is standing right there.
 *
 * <p><b>The type is resolved on the command thread</b>, before anything is queried, so an unknown key
 * costs the sender a reply and the server nothing — the order {@code BroadcastCommand} uses. Everything
 * after that runs async: {@code hasPlayedBefore()} reads userdata, the enqueue is blocking JDBC, and
 * {@code DiscordDmSink} refuses the main thread outright.
 *
 * <p>Any {@link CommandSender} may use it, console included: like {@code /mail send}, it acts on another
 * player's inbox rather than the sender's own.
 */
public final class SendCommand {

    /**
     * Its own permission, {@code default: op}, because this writes into someone else's inbox — the
     * company it keeps is {@code /broadcast}, not the {@code default: true} player-facing subcommands.
     * An <em>additional</em> gate: the {@code /notifications} root still requires
     * {@link NotificationsCommand#PERMISSION}, so revoking that hides this too.
     */
    public static final String PERMISSION = "playernotifications.command.send";

    private static final String PLAYER_ARGUMENT = "player";
    private static final String TYPE_ARGUMENT = "type";
    private static final String CONTENT_ARGUMENT = "content";

    private SendCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull MessageContainer messages,
                                                                @NotNull Plugin plugin,
                                                                @NotNull Broadcaster broadcaster,
                                                                @NotNull PersistentBroadcaster persistentBroadcaster,
                                                                @NotNull CustomNotificationTypes customTypes) {
        return Commands.literal("send")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument(PLAYER_ARGUMENT, StringArgumentType.word())
                        .suggests((context, builder) -> suggestPlayers(builder))
                        .then(Commands.argument(TYPE_ARGUMENT, StringArgumentType.word())
                                .suggests((context, builder) -> suggestTypes(customTypes, builder))
                                .then(Commands.argument(CONTENT_ARGUMENT, StringArgumentType.greedyString())
                                        .executes(context -> run(messages, context, plugin, broadcaster,
                                                persistentBroadcaster, customTypes)))))
                .build();
    }

    private static CompletableFuture<Suggestions> suggestPlayers(
            @NotNull SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(player.getName());
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestTypes(
            @NotNull CustomNotificationTypes customTypes, @NotNull SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        // Read live rather than captured, so a reloaded notification-types.yml suggests its new keys.
        for (String key : customTypes.keys()) {
            if (key.startsWith(remaining)) {
                builder.suggest(key);
            }
        }
        return builder.buildFuture();
    }

    private static int run(@NotNull MessageContainer messages,
                           @NotNull CommandContext<CommandSourceStack> context, @NotNull Plugin plugin,
                           @NotNull Broadcaster broadcaster,
                           @NotNull PersistentBroadcaster persistentBroadcaster,
                           @NotNull CustomNotificationTypes customTypes) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, PLAYER_ARGUMENT);
        String typeKey = StringArgumentType.getString(context, TYPE_ARGUMENT);
        String raw = StringArgumentType.getString(context, CONTENT_ARGUMENT);

        SendArguments arguments;
        switch (SendArguments.parse(raw)) {
            case SendArguments.Result.BlankContent ignored -> {
                sender.sendMessage(messages.messageFor(MessageKeys.SEND_BLANK_CONTENT));
                return 0;
            }
            case SendArguments.Result.UnrecognisedToken unrecognised -> {
                // value(): the token is whatever the sender typed, so it must stay literal.
                sender.sendMessage(messages.messageFor(MessageKeys.SEND_UNRECOGNISED_TOKEN,
                        MessageContainer.value("token", unrecognised.token())));
                return 0;
            }
            case SendArguments.Result.Parsed parsed -> arguments = parsed.arguments();
        }

        Optional<DeclaredNotificationType> declared = customTypes.get(typeKey);
        if (declared.isEmpty()) {
            sender.sendMessage(messages.messageFor(MessageKeys.SEND_UNKNOWN_TYPE,
                    MessageContainer.value("type", typeKey)));
            return 0;
        }
        DeclaredNotificationType declaredType = declared.get();

        Component content;
        try {
            content = MiniMessage.miniMessage().deserialize(arguments.content());
        } catch (RuntimeException e) {
            sender.sendMessage(messages.messageFor(MessageKeys.SEND_PARSE_FAILED,
                    MessageContainer.value("error", String.valueOf(e.getMessage()))));
            return 0;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> send(messages, sender, name, declaredType, arguments, content, broadcaster,
                        persistentBroadcaster));
        return Command.SINGLE_SUCCESS;
    }

    /** The async half: resolve the recipient, then fan out or store. */
    private static void send(@NotNull MessageContainer messages, @NotNull CommandSender sender,
                             @NotNull String name, @NotNull DeclaredNotificationType declaredType,
                             @NotNull SendArguments arguments, @NotNull Component content,
                             @NotNull Broadcaster broadcaster,
                             @NotNull PersistentBroadcaster persistentBroadcaster) {
        UUID recipient = resolveRecipient(name);
        if (recipient == null) {
            sender.sendMessage(messages.messageFor(MessageKeys.SEND_UNKNOWN_PLAYER,
                    MessageContainer.value("name", name)));
            return;
        }

        // Parsed per send rather than captured, so a reloaded notification-types.yml takes effect.
        // CustomNotificationTypes.load already proved it parses.
        Component title = MiniMessage.miniMessage().deserialize(declaredType.title());
        try {
            if (arguments.transientSend()) {
                int attempted = broadcaster.broadcast(title, content, declaredType.key(),
                        List.of(recipient), false);
                sender.sendMessage(messages.messageFor(
                        attempted == 0 ? MessageKeys.SEND_NOTHING_ENABLED : MessageKeys.SEND_SENT,
                        MessageContainer.value("player", name)));
                return;
            }
            PersistentBroadcaster.Result result = persistentBroadcaster.broadcast(title, content,
                    declaredType.key(),
                    new CustomNotificationPayload(declaredType.key(), arguments.content()),
                    List.of(recipient), false);
            sender.sendMessage(messages.messageFor(MessageKeys.SEND_STORED,
                    MessageContainer.value("player", name)));
            if (result.failed() > 0) {
                // A warning, not a failure: it is stored, and will be pushed on their next join.
                sender.sendMessage(messages.messageFor(MessageKeys.SEND_PUSH_FAILED,
                        MessageContainer.value("player", name)));
            }
        } catch (RuntimeException e) {
            // The enqueue propagates out of PersistentBroadcaster deliberately. A sender told nothing
            // would assume it worked, which is the one outcome worse than the command failing.
            sender.sendMessage(messages.messageFor(MessageKeys.SEND_PUSH_FAILED,
                    MessageContainer.value("player", name)));
        }
    }

    /**
     * An online player by name first; otherwise {@code Bukkit.getOfflinePlayer(name)}, accepted only
     * when {@code hasPlayedBefore()} is true; otherwise unknown. The {@code hasPlayedBefore} check is
     * essential — {@code getOfflinePlayer(String)} fabricates a UUID for any string, so without it a
     * typo would silently store a notification for a player who cannot exist. The same rule
     * {@code MailCommand} applies, for the same reason.
     */
    @Nullable
    private static UUID resolveRecipient(@NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }
}
