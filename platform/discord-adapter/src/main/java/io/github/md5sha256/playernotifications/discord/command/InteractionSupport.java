package io.github.md5sha256.playernotifications.discord.command;

import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The mechanics every command listener repeats: hop off the JDA event thread, and put a reply on an
 * interaction that has already been deferred.
 *
 * <p>No decisions live here — they are all in the view classes, which is what makes them testable.
 */
final class InteractionSupport {

    /** What a player is told when something failed on our side; the detail goes to the log. */
    static final String FAILURE_MESSAGE = "Something went wrong. Please try again.";

    private InteractionSupport() {
    }

    /**
     * Runs {@code work} off the JDA event thread — every command blocks on JDBC — and replies with
     * {@link #FAILURE_MESSAGE} if it throws, because an exception escaping here would leave the
     * interaction hanging with no reply at all.
     */
    static void async(@NotNull Executor executor, @NotNull InteractionHook hook, @NotNull Logger logger,
                      @NotNull Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "A Discord command failed", exception);
                reply(hook, FAILURE_MESSAGE, logger);
            }
        });
    }

    static void reply(@NotNull InteractionHook hook, @NotNull String message, @NotNull Logger logger) {
        hook.sendMessage(message).setEphemeral(true).queue(
                success -> {
                },
                failure -> logger.log(Level.FINE, "Could not deliver a Discord reply", failure));
    }

    static void reply(@NotNull InteractionHook hook, @NotNull MessageCreateData message,
                      @NotNull Logger logger) {
        hook.sendMessage(message).setEphemeral(true).queue(
                success -> {
                },
                failure -> logger.log(Level.FINE, "Could not deliver a Discord reply", failure));
    }

    /** Replaces the message the component was on, so a screen's state stays in one place. */
    static void edit(@NotNull InteractionHook hook, @NotNull MessageCreateData message,
                     @NotNull Logger logger) {
        hook.editOriginal(MessageEditData.fromCreateData(message)).queue(
                success -> {
                },
                failure -> logger.log(Level.FINE, "Could not update a Discord message", failure));
    }
}
