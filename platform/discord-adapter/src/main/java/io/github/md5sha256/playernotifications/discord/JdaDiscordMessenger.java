package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The real {@link DiscordMessenger}: opens a private channel and sends the message through JDA,
 * blocking up to the configured timeout.
 *
 * <p>Blocking is intentional and safe — the delivery loop already runs off the main thread, and
 * {@link DiscordDmSink} refuses to run on it. Blocking is what lets a send failure be reported as a
 * {@link DeliveryResult} rather than swallowed in a callback the sink cannot observe.
 *
 * <p>Only the two permanent Discord errors map to {@link DeliveryResult#UNSUPPORTED}: the user
 * blocking DMs and the user not existing. Everything else — not connected, rate limited, timed
 * out — is {@link DeliveryResult#UNREACHABLE}, so the notification survives to the next pass.
 */
public final class JdaDiscordMessenger implements DiscordMessenger {

    private final DiscordBot bot;
    private final long timeoutSeconds;
    private final Logger logger;

    public JdaDiscordMessenger(@NotNull DiscordBot bot, long timeoutSeconds, @NotNull Logger logger) {
        this.bot = bot;
        this.timeoutSeconds = timeoutSeconds;
        this.logger = logger;
    }

    @Override
    public @NotNull DeliveryResult send(long discordUserId, @NotNull MessageCreateData message) {
        Optional<JDA> jda = this.bot.jda();
        if (jda.isEmpty()) {
            this.logger.fine("Discord bot is not connected; deferring the DM to " + discordUserId);
            return DeliveryResult.UNREACHABLE;
        }

        try {
            jda.get().openPrivateChannelById(discordUserId)
                    .flatMap(channel -> channel.sendMessage(message))
                    .submit()
                    .get(this.timeoutSeconds, TimeUnit.SECONDS);
            return DeliveryResult.DELIVERED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeliveryResult.UNREACHABLE;
        } catch (TimeoutException exception) {
            this.logger.warning("Timed out sending a Discord DM to " + discordUserId);
            return DeliveryResult.UNREACHABLE;
        } catch (ExecutionException exception) {
            return classify(discordUserId, exception.getCause());
        } catch (RuntimeException exception) {
            return classify(discordUserId, exception);
        }
    }

    private @NotNull DeliveryResult classify(long discordUserId, Throwable cause) {
        if (cause instanceof ErrorResponseException errorResponse) {
            ErrorResponse response = errorResponse.getErrorResponse();
            if (response == ErrorResponse.CANNOT_SEND_TO_USER || response == ErrorResponse.UNKNOWN_USER) {
                this.logger.fine("Discord user " + discordUserId
                        + " cannot receive DMs from this bot (" + response + ")");
                return DeliveryResult.UNSUPPORTED;
            }
        }
        this.logger.log(Level.WARNING, "Failed to send a Discord DM to " + discordUserId, cause);
        return DeliveryResult.UNREACHABLE;
    }
}
