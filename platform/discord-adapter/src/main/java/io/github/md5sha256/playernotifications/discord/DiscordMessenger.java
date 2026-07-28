package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;

/**
 * Sends an already-built Discord message to a Discord user.
 *
 * <p>The seam that keeps JDA — and a live gateway connection — out of {@link DiscordDmSink}'s tests.
 * {@link JdaDiscordMessenger} is the real implementation.
 */
@FunctionalInterface
public interface DiscordMessenger {

    /**
     * Attempts to deliver the message, blocking until it succeeds, fails, or times out. Never
     * returns {@code null}; implementations map their own failures onto {@link DeliveryResult}.
     */
    @NotNull DeliveryResult send(long discordUserId, @NotNull MessageCreateData message);
}
