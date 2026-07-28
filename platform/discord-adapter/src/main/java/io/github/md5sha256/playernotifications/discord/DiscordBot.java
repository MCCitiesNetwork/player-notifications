package io.github.md5sha256.playernotifications.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Optional;

/**
 * Owns this module's JDA connection.
 *
 * <p>The module runs its own bot rather than borrowing DiscordSRV's: legacy DiscordSRV relocates its
 * bundled JDA to {@code github.scarsz.discordsrv.dependencies.jda.*}, so its instance is not
 * type-compatible with upstream {@code net.dv8tion}. DiscordSRV is a link source only.
 *
 * <p>Built with {@code createLight} and no gateway intents — the bot only ever sends DMs, and never
 * receives events. {@code awaitReady()} is deliberately not called, so a slow or unreachable Discord
 * cannot stall server startup; {@link #jda()} is empty until the connection is up, and a send
 * attempted before then is reported as unreachable rather than queued.
 */
public final class DiscordBot {

    private final JDA jda;

    private DiscordBot(@NotNull JDA jda) {
        this.jda = jda;
    }

    /**
     * Starts a bot with the given token. Returns as soon as the connection has been initiated.
     */
    public static @NotNull DiscordBot start(@NotNull String botToken) {
        JDA jda = JDABuilder.createLight(botToken)
                .setEnabledIntents(Collections.emptyList())
                .build();
        return new DiscordBot(jda);
    }

    /** The JDA instance, or empty while it is not connected. */
    public @NotNull Optional<JDA> jda() {
        return this.jda.getStatus() == JDA.Status.CONNECTED ? Optional.of(this.jda) : Optional.empty();
    }

    /** Closes the gateway connection. Safe to call more than once. */
    public void shutdown() {
        this.jda.shutdown();
    }
}
