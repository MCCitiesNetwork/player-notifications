package io.github.md5sha256.playernotifications.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.md5sha256.playernotifications.paper.diagnostic.TestNotificationSender;
import io.github.md5sha256.playernotifications.paper.preferences.PreferenceDialogRouter;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The player-facing {@code /notifications} command. The bare root and {@code preferences} open the
 * staged root dialog; {@code media}/{@code types} jump straight to the corresponding picker on the same
 * session; {@code mute}/{@code reset} write immediately and discard any open session, unlike their
 * staged root-screen equivalents. {@code reload} is admin-only (a separate permission) and works from
 * any sender, console included, since it operates on plugin configuration rather than a specific player.
 *
 * <p>Registered through Paper's Brigadier API rather than a {@code commands:} block, because this plugin
 * ships a {@code paper-plugin.yml}, which has no such block.
 */
public final class NotificationsCommand {

    public static final String PERMISSION = "playernotifications.command.preferences";

    public static final String RELOAD_PERMISSION = "playernotifications.command.reload";

    public static final String TEST_PERMISSION = "playernotifications.command.test";

    private static final String DEFAULT_TEST_MESSAGE = "This is a test notification.";

    public static final String DESCRIPTION = "Choose how you receive notifications";

    private static final Component PLAYERS_ONLY =
            Component.text("Only players have notification preferences.", NamedTextColor.RED);

    private NotificationsCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull PreferenceDialogRouter router,
                                                                @NotNull Consumer<CommandSender> reloadAction,
                                                                @NotNull TestNotificationSender testSender) {
        return Commands.literal("notifications")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> run(context, router::openRoot))
                .then(Commands.literal("preferences").executes(context -> run(context, router::openRoot)))
                .then(Commands.literal("media").executes(context -> run(context, router::openMediaPicker)))
                .then(Commands.literal("types").executes(context -> run(context, router::openCategoryPicker)))
                .then(Commands.literal("mute").executes(context -> run(context, router::muteImmediately)))
                .then(Commands.literal("reset").executes(context -> run(context, router::resetImmediately)))
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
