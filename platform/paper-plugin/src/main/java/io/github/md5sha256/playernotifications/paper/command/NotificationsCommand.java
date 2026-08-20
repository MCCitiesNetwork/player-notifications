package io.github.md5sha256.playernotifications.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationSender;
import io.github.md5sha256.playernotifications.paper.inbox.InboxRouter;
import io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogRouter;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The player-facing {@code /notifications} command.
 *
 * <p>The <strong>bare root opens the player's inbox</strong>; {@code list}/{@code read}/{@code dismiss}
 * are its chat fallback, for clients where the dialog does not render, and {@code clear} is the
 * shorthand for emptying it outright. Everything preference-related
 * hangs off {@code preferences}: that literal opens the
 * staged root dialog, its {@code media}/{@code types} children jump straight to the corresponding picker
 * on the same session, and its {@code mute} child writes immediately and discards any open session,
 * unlike its staged root-screen equivalent. Nesting them keeps the top level clear for the management
 * UI's own verbs, which would otherwise collide with a name like {@code mute}.
 *
 * <p>{@code /notifications mute} and its mirror {@code /notifications unmute} survive at the top level as
 * proxies onto {@link PreferenceDialogRouter#muteImmediately}/{@link PreferenceDialogRouter#unmuteImmediately}
 * — the same actions the nested forms invoke, not second implementations — because muting and unmuting
 * everything are the preference operations most often wanted in a hurry.
 *
 * <p>{@code link}/{@code unlink} address account-link providers registered by feature modules.
 * {@code reload} is admin-only (a separate permission) and works from any sender, console included, since
 * it operates on plugin configuration rather than a specific player.
 *
 * <p>Registered through Paper's Brigadier API rather than a {@code commands:} block, because this plugin
 * ships a {@code paper-plugin.yml}, which has no such block.
 */
public final class NotificationsCommand {

    public static final String PERMISSION = "playernotifications.command.preferences";

    /**
     * Gates the {@code link}/{@code unlink} subtrees. Linking a Minecraft account to an external one is a
     * different grant from choosing delivery media, so it is separable — a server can leave preferences open
     * to everyone while restricting linking to a rank. It is an <em>additional</em> gate, not a replacement:
     * the tree root already requires {@link #PERMISSION}, so revoking that hides linking too. Both default to
     * {@code true}, so the split changes nothing until an operator negates one.
     */
    public static final String LINK_PERMISSION = "playernotifications.command.link";

    public static final String RELOAD_PERMISSION = "playernotifications.command.reload";

    public static final String TEST_PERMISSION = "playernotifications.command.test";

    private static final String DEFAULT_TEST_MESSAGE = "This is a test notification.";

    private static final String PROVIDER_ARGUMENT = "provider";

    /**
     * Help text registered for the command tree as a whole, so it no longer names preferences specifically —
     * those moved under the {@code preferences} subcommand.
     */
    public static final String DESCRIPTION = "Manage your notifications";

    private static final Component PLAYERS_ONLY =
            Component.text("Only players have notification preferences.", NamedTextColor.RED);

    private static final String PAGE_ARGUMENT = "page";
    private static final String INDEX_ARGUMENT = "n";

    private NotificationsCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull PreferenceDialogRouter router,
                                                                @NotNull InboxRouter inboxRouter,
                                                                @NotNull Consumer<CommandSender> reloadAction,
                                                                @NotNull TestNotificationSender testSender,
                                                                @NotNull AccountLinkDispatcher linkDispatcher,
                                                                @NotNull Executor asyncExecutor) {
        return Commands.literal("notifications")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> run(context, player -> inboxRouter.openInbox(player, 1)))
                .then(Commands.literal("list")
                        .executes(context -> run(context, player -> inboxRouter.listInChat(player, 1)))
                        .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                                .executes(context -> run(context, player -> inboxRouter.listInChat(
                                        player, IntegerArgumentType.getInteger(context, PAGE_ARGUMENT))))))
                .then(Commands.literal("read")
                        .then(Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(1))
                                .executes(context -> run(context, player -> inboxRouter.readInChat(
                                        player, IntegerArgumentType.getInteger(context, INDEX_ARGUMENT))))))
                .then(Commands.literal("dismiss")
                        .then(Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(1))
                                .executes(context -> run(context, player -> inboxRouter.dismissInChat(
                                        player, IntegerArgumentType.getInteger(context, INDEX_ARGUMENT))))))
                .then(Commands.literal("clear")
                        .executes(context -> run(context, inboxRouter::clearInChat)))
                .then(Commands.literal("preferences")
                        .executes(context -> run(context, router::openRoot))
                        .then(Commands.literal("media").executes(context -> run(context, router::openMediaPicker)))
                        .then(Commands.literal("types").executes(context -> run(context, router::openCategoryPicker)))
                        .then(Commands.literal("mute").executes(context -> run(context, router::muteImmediately)))
                        .then(Commands.literal("unmute").executes(context -> run(context, router::unmuteImmediately))))
                .then(Commands.literal("mute").executes(context -> run(context, router::muteImmediately)))
                .then(Commands.literal("unmute").executes(context -> run(context, router::unmuteImmediately)))
                .then(linkNode(linkDispatcher, asyncExecutor))
                .then(unlinkNode(linkDispatcher, asyncExecutor))
                .then(Commands.literal("test")
                        .requires(source -> source.getSender().hasPermission(TEST_PERMISSION))
                        .executes(context -> run(context,
                                player -> testSender.send(player, DEFAULT_TEST_MESSAGE)))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> run(context, player -> testSender.send(
                                        player, StringArgumentType.getString(context, "message"))))))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission(RELOAD_PERMISSION))
                        .executes(context -> {
                            reloadAction.accept(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    /**
     * The {@code link} subtree: {@code /notifications link} lists what can be linked,
     * {@code /notifications link <provider>} starts a link, and {@code … <provider> status} describes
     * the current one. Removal is the sibling {@code /notifications unlink <provider>}, not a child
     * here — link and unlink are opposite operations and read as peers.
     *
     * <p>The provider is an <em>argument</em>, not one literal per registered provider, because this node
     * is built once — when Paper fires {@link io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents#COMMANDS}
     * — and static literals would freeze the provider set at that instant. The argument consults the
     * registry on every dispatch, and its suggestions are computed live.
     *
     * <p>The node exists whether or not anything is registered; a provider that is absent (never
     * installed, or its module stopped) produces an explanatory reply from the dispatcher rather than a
     * missing command. Removing the node instead would need mutation of an already-registered Brigadier
     * node, for which Paper exposes no API.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> linkNode(
            @NotNull AccountLinkDispatcher dispatcher, @NotNull Executor asyncExecutor) {
        return Commands.literal("link")
                .requires(source -> source.getSender().hasPermission(LINK_PERMISSION))
                .executes(context -> run(context,
                        player -> reply(player, asyncExecutor, dispatcher::listProviders)))
                .then(providerArgument(dispatcher)
                        .executes(context -> linkAction(context, dispatcher, asyncExecutor,
                                AccountLinkDispatcher.Action.BEGIN))
                        .then(Commands.literal("status")
                                .executes(context -> linkAction(context, dispatcher, asyncExecutor,
                                        AccountLinkDispatcher.Action.STATUS))));
    }

    /** {@code /notifications unlink <provider>} — a sibling of {@code link}, not a child of it. */
    private static LiteralArgumentBuilder<CommandSourceStack> unlinkNode(
            @NotNull AccountLinkDispatcher dispatcher, @NotNull Executor asyncExecutor) {
        return Commands.literal("unlink")
                .requires(source -> source.getSender().hasPermission(LINK_PERMISSION))
                .executes(context -> run(context,
                        player -> reply(player, asyncExecutor, dispatcher::listProviders)))
                .then(providerArgument(dispatcher)
                        .executes(context -> linkAction(context, dispatcher, asyncExecutor,
                                AccountLinkDispatcher.Action.UNLINK)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> providerArgument(
            @NotNull AccountLinkDispatcher dispatcher) {
        return Commands.argument(PROVIDER_ARGUMENT, StringArgumentType.word())
                .suggests((context, builder) -> {
                    dispatcher.suggestions().forEach(builder::suggest);
                    return builder.buildFuture();
                });
    }

    private static int linkAction(@NotNull CommandContext<CommandSourceStack> context,
                                  @NotNull AccountLinkDispatcher dispatcher,
                                  @NotNull Executor asyncExecutor,
                                  @NotNull AccountLinkDispatcher.Action action) {
        String provider = StringArgumentType.getString(context, PROVIDER_ARGUMENT);
        return run(context, player -> reply(player, asyncExecutor,
                () -> dispatcher.dispatch(provider, player.getUniqueId(), action)));
    }

    /**
     * Every {@link AccountLinkDispatcher} call blocks on JDBC, so it runs off the main thread and the
     * reply is sent from there — {@code Player#sendMessage} is safe from any thread.
     */
    private static void reply(@NotNull Player player,
                              @NotNull Executor asyncExecutor,
                              @NotNull Supplier<Component> action) {
        asyncExecutor.execute(() -> player.sendMessage(action.get()));
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
