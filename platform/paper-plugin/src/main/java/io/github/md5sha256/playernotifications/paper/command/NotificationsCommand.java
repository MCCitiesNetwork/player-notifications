package io.github.md5sha256.playernotifications.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.md5sha256.playernotifications.paper.preferences.NotificationPreferencesDialog;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The player-facing {@code /notifications} command. Opens the notification preferences dialog; the bare
 * root and the explicit {@code preferences} literal do the same thing, the literal existing so future
 * subcommands can be added without changing what players already type.
 *
 * <p>Registered through Paper's Brigadier API rather than a {@code commands:} block, because this plugin
 * ships a {@code paper-plugin.yml}, which has no such block.
 */
public final class NotificationsCommand {

    public static final String PERMISSION = "playernotifications.command.preferences";

    public static final String DESCRIPTION = "Choose how you receive notifications";

    private static final Component PLAYERS_ONLY =
            Component.text("Only players have notification preferences.", NamedTextColor.RED);

    private NotificationsCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(
            @NotNull NotificationPreferencesDialog dialog) {
        return Commands.literal("notifications")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> openPreferences(context, dialog))
                .then(Commands.literal("preferences")
                        .executes(context -> openPreferences(context, dialog)))
                .build();
    }

    private static int openPreferences(@NotNull CommandContext<CommandSourceStack> context,
                                       @NotNull NotificationPreferencesDialog dialog) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return 0;
        }
        dialog.open(player);
        return Command.SINGLE_SUCCESS;
    }
}
