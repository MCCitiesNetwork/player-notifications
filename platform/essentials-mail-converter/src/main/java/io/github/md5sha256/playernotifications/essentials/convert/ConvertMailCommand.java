package io.github.md5sha256.playernotifications.essentials.convert;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /essmailconvert} — the module's only command, and the only reason it exists at runtime.
 *
 * <p>The bare form imports nothing and {@code confirm} is a literal rather than a flag, because the
 * conversion is deliberately <strong>not idempotent</strong>: it leaves the EssentialsX copy untouched
 * (so a bad import destroys nothing and can be re-run) and does not de-duplicate, so running it twice
 * imports every mail twice. {@code preview} exists so an operator can see the numbers first.
 *
 * <p>Console is a valid sender: this operates on server data, not on the player running it.
 *
 * <p>{@link #PERMISSION} is declared nowhere. {@code paper-plugin.yml} belongs to the host, and
 * registering a permission programmatically is the pattern the Discord adapter was cleaned of. An
 * undeclared permission resolves through Bukkit's default — op only — which is the intended gate; an
 * operator who wants to grant it to a rank defines it in their permissions plugin.
 */
final class ConvertMailCommand {

    static final String PERMISSION = "essentialsmailconverter.command.convert";

    static final String DESCRIPTION = "Import EssentialsX mail into PlayerNotifications mail";

    private ConvertMailCommand() {
    }

    static @NotNull LiteralCommandNode<CommandSourceStack> create(@NotNull EssentialsMailReader reader,
                                                                  @NotNull EssentialsMailConverter converter,
                                                                  @NotNull Executor asyncExecutor,
                                                                  @NotNull Logger logger) {
        // One conversion at a time. A second invocation mid-sweep is refused rather than queued: two
        // concurrent runs over the same mailboxes would import everything twice, which is precisely what
        // the confirm literal exists to make deliberate.
        AtomicBoolean running = new AtomicBoolean();
        return Commands.literal("essmailconvert")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    usage(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("preview")
                        .executes(context -> run(context.getSource().getSender(), reader, converter,
                                asyncExecutor, logger, running, false)))
                .then(Commands.literal("confirm")
                        .executes(context -> run(context.getSource().getSender(), reader, converter,
                                asyncExecutor, logger, running, true)))
                .build();
    }

    private static void usage(@NotNull CommandSender sender) {
        sender.sendMessage(Component.text("EssentialsX mail converter", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/essmailconvert preview", NamedTextColor.YELLOW)
                .append(Component.text(" — count what would be imported, and change nothing.",
                        NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/essmailconvert confirm", NamedTextColor.YELLOW)
                .append(Component.text(" — import it.", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(
                "Importing does not remove the EssentialsX copy and does not check for mail already "
                        + "imported, so running confirm twice gives every player two copies of everything.",
                NamedTextColor.RED));
    }

    private static int run(@NotNull CommandSender sender, @NotNull EssentialsMailReader reader,
                           @NotNull EssentialsMailConverter converter, @NotNull Executor asyncExecutor,
                           @NotNull Logger logger, @NotNull AtomicBoolean running, boolean write) {
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(Component.text("A mail conversion is already running.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(Component.text(
                write ? "Reading EssentialsX mailboxes and importing…" : "Reading EssentialsX mailboxes…",
                NamedTextColor.GRAY));
        // Read on the main thread (EssentialsX user loading is not thread-safe), write off it (the
        // enqueue path is blocking JDBC).
        reader.readAsync(mail -> asyncExecutor.execute(() -> {
            try {
                ConversionReport report = write ? converter.convert(mail) : converter.preview(mail);
                report(sender, logger, report, write);
            } catch (RuntimeException ex) {
                logger.log(Level.SEVERE, "EssentialsX mail conversion failed", ex);
                sender.sendMessage(Component.text(
                        "The conversion failed; see the server log.", NamedTextColor.RED));
            } finally {
                running.set(false);
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Reports to the sender and to the log alike, so a console run and an in-game run leave the same
     * record of what a non-repeatable operation did.
     */
    private static void report(@NotNull CommandSender sender, @NotNull Logger logger,
                               @NotNull ConversionReport report, boolean write) {
        List<Component> lines = List.of(
                Component.text(write
                        ? "EssentialsX mail conversion complete."
                        : "EssentialsX mail preview complete. Nothing was imported.", NamedTextColor.GOLD),
                line(write ? "Imported" : "Would import", report.imported(), NamedTextColor.GREEN),
                line("Skipped (expired)", report.skippedExpired(), NamedTextColor.GRAY),
                line("Skipped (empty)", report.skippedBlank(), NamedTextColor.GRAY),
                line("Failed", report.failed(),
                        report.failed() == 0 ? NamedTextColor.GRAY : NamedTextColor.RED),
                line("Mails read", report.total(), NamedTextColor.GRAY));
        lines.forEach(sender::sendMessage);
        logger.info("EssentialsX mail " + (write ? "conversion" : "preview") + ": "
                + report.imported() + " imported, " + report.skippedExpired() + " expired, "
                + report.skippedBlank() + " empty, " + report.failed() + " failed, "
                + report.total() + " read");
    }

    private static @NotNull Component line(@NotNull String label, int count, @NotNull NamedTextColor colour) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(count, colour));
    }
}
