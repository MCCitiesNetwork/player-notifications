package io.github.md5sha256.playernotifications.discord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The player-facing {@code /discordlink} command: issue a link code, check the current link, or remove it.
 *
 * <p>Owned by this module rather than added to the host's {@code /notifications} tree, so the host needs no
 * knowledge of account linking and no new extension point. The trade-off, accepted in the design: a player
 * who has learned {@code /notifications} does not discover this from it.
 *
 * <p>Registered only when {@code link-providers} lists {@code embedded} — a DiscordSRV-only server gets no
 * command it cannot use.
 */
public final class DiscordLinkCommand {

    /** Declared programmatically: the module jar has no plugin descriptor to declare it in. */
    public static final String PERMISSION = "playernotifications.discord.link";

    public static final String DESCRIPTION = "Link your Minecraft account to Discord";

    /** The command literal and its alias, used for both registration and unregistration. */
    public static final String LITERAL = "discordlink";
    public static final String ALIAS = "dlink";

    private static final Component PLAYERS_ONLY =
            Component.text("Only players can link a Discord account.", NamedTextColor.RED);

    private DiscordLinkCommand() {
    }

    @NotNull
    public static LiteralCommandNode<CommandSourceStack> create(@NotNull DiscordLinkFlow flow,
                                                               @NotNull Executor asyncExecutor) {
        return Commands.literal(LITERAL)
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> run(context, asyncExecutor, flow::begin))
                .then(Commands.literal("status")
                        .executes(context -> run(context, asyncExecutor, flow::status)))
                .then(Commands.literal("unlink")
                        .executes(context -> run(context, asyncExecutor, flow::unlink)))
                .build();
    }

    /**
     * Removes the command from the server, so a module stop/start cycle cannot leave a {@code /discordlink}
     * behind that dispatches into a dead flow and a shut-down bot.
     *
     * <p>Paper's Brigadier registrar exposes no unregister, so this goes through the command map — which
     * does expose {@link CommandMap#getKnownCommands()} as API. Both the bare literal and its
     * plugin-namespaced form ({@code playernotifications:discordlink}) have to go, since Paper registers
     * both.
     */
    public static void unregister(@NotNull Logger logger) {
        try {
            CommandMap map = Bukkit.getCommandMap();
            org.bukkit.command.Command command = map.getCommand(LITERAL);
            if (command != null) {
                command.unregister(map);
            }
            map.getKnownCommands().keySet().removeIf(DiscordLinkCommand::isOurs);
            // Online clients cache the command tree, so without this they keep suggesting a command the
            // server no longer knows.
            Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        } catch (RuntimeException | LinkageError failure) {
            // Never let cleanup abort the rest of module shutdown.
            logger.log(Level.WARNING, "Could not unregister /" + LITERAL
                    + "; it may linger until the server restarts", failure);
        }
    }

    /**
     * Whether a known-commands key is ours, with or without a plugin namespace.
     *
     * <p>Matched on the whole name after any namespace, never as a substring: a key wrongly matched would
     * remove another plugin's command from a running server during our shutdown.
     */
    static boolean isOurs(@NotNull String key) {
        String name = key.toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return LITERAL.equals(name) || ALIAS.equals(name);
    }

    /** The aliases to register alongside the literal. */
    public static @NotNull List<String> aliases() {
        return List.of(ALIAS);
    }

    /**
     * Player-only, and dispatched off the main thread: every {@link DiscordLinkFlow} method blocks on JDBC.
     */
    private static int run(@NotNull CommandContext<CommandSourceStack> context,
                           @NotNull Executor asyncExecutor,
                           @NotNull Function<UUID, Component> action) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PLAYERS_ONLY);
            return 0;
        }
        UUID playerUuid = player.getUniqueId();
        asyncExecutor.execute(() -> player.sendMessage(action.apply(playerUuid)));
        return Command.SINGLE_SUCCESS;
    }
}
