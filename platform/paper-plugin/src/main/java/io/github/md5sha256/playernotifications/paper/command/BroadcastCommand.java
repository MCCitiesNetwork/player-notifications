package io.github.md5sha256.playernotifications.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.md5sha256.playernotifications.paper.broadcast.BroadcastArguments;
import io.github.md5sha256.playernotifications.paper.broadcast.BroadcastAudience;
import io.github.md5sha256.playernotifications.paper.broadcast.Broadcaster;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * {@code /broadcast <content> --perm [perm] … [--bypass]}: parses and validates on the command thread,
 * then fans the message out asynchronously through {@link Broadcaster}. Any {@link CommandSender} may
 * use it, console included — unlike {@code /notifications}/{@code /mail}, it acts on the server's
 * online players, not on the sender's own inbox.
 *
 * <p><b>The audience is resolved on the command thread, not inside the async task.</b> Bukkit permission
 * state belongs to the main thread — {@link BroadcastAudience#resolve} (in practice
 * {@code OnlineBroadcastAudience}, which calls {@code Player#hasPermission}) must run there, and only the
 * resulting {@code List<UUID>} crosses into the async task. This is the same rule
 * {@code MailCommand.send} follows for its {@code TagResolver}: build anything Bukkit-permission-shaped
 * up front, hand the async task plain data.
 *
 * <p><b>The fan-out itself runs asynchronously</b> because {@link Broadcaster#broadcast} does blocking
 * JDBC (reading preferences and the mute flag) and can deliver through {@code DiscordDmSink}, which
 * refuses to run on the main thread outright.
 */
public final class BroadcastCommand {

    public static final String PERMISSION = "playernotifications.command.broadcast";
    public static final String DESCRIPTION = "Broadcast a message to online players";

    private static final String CONTENT_ARGUMENT = "content";

    private static final Component NO_RECIPIENTS =
            Component.text("No online player matched those permissions.", NamedTextColor.RED);
    private static final Component NOTHING_ENABLED = Component.text(
            "No recipient had broadcasts enabled. Use --bypass to deliver regardless.",
            NamedTextColor.RED);

    private BroadcastCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull Plugin plugin,
                                                                  @NotNull Broadcaster broadcaster,
                                                                  @NotNull BroadcastAudience audience) {
        return Commands.literal("broadcast")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument(CONTENT_ARGUMENT, StringArgumentType.greedyString())
                        .executes(context -> run(context, plugin, broadcaster, audience)))
                .build();
    }

    private static int run(@NotNull CommandContext<CommandSourceStack> context, @NotNull Plugin plugin,
                            @NotNull Broadcaster broadcaster, @NotNull BroadcastAudience audience) {
        CommandSender sender = context.getSource().getSender();
        String raw = StringArgumentType.getString(context, CONTENT_ARGUMENT);

        BroadcastArguments.Result parsed = BroadcastArguments.parse(raw);
        if (parsed instanceof BroadcastArguments.Result.Invalid invalid) {
            sender.sendMessage(Component.text(invalid.message(), NamedTextColor.RED));
            return 0;
        }
        BroadcastArguments arguments = ((BroadcastArguments.Result.Parsed) parsed).arguments();

        Component content;
        try {
            content = MiniMessage.miniMessage().deserialize(arguments.content());
        } catch (RuntimeException e) {
            sender.sendMessage(Component.text(
                    "Could not parse broadcast content: " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }

        List<UUID> recipients = audience.resolve(arguments.permissions());
        if (recipients.isEmpty()) {
            sender.sendMessage(NO_RECIPIENTS);
            return 0;
        }

        boolean bypass = arguments.bypass();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int attempted = broadcaster.broadcast(content, recipients, bypass);
            if (attempted == 0) {
                sender.sendMessage(NOTHING_ENABLED);
            } else {
                sender.sendMessage(Component.text(
                        "Broadcast sent to " + attempted + " player(s).", NamedTextColor.GREEN));
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
