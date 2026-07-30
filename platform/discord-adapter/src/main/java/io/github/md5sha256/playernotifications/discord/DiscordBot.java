package io.github.md5sha256.playernotifications.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

/**
 * Owns this module's JDA connection.
 *
 * <p>The module runs its own bot rather than borrowing DiscordSRV's: legacy DiscordSRV relocates its
 * bundled JDA to {@code github.scarsz.discordsrv.dependencies.jda.*}, so its instance is not
 * type-compatible with upstream {@code net.dv8tion}. DiscordSRV is a link source only.
 *
 * <p>Built with {@code createLight} and no gateway intents. The bot sends DMs and receives
 * <em>interactions</em> — which require no gateway intent, privileged or otherwise, which is why the link
 * flow is a slash command rather than the bot reading DM message content. {@code awaitReady()} is
 * deliberately not called, so a slow or unreachable Discord cannot stall server startup; {@link #jda()} is
 * empty until the connection is up, and a send attempted before then is reported as unreachable rather
 * than queued.
 */
public final class DiscordBot {

    /** How long {@link #shutdown()} waits for JDA's threads, per attempt. */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final JDA jda;

    private DiscordBot(@NotNull JDA jda) {
        this.jda = jda;
    }

    /**
     * Starts a bot with the given token. Returns as soon as the connection has been initiated.
     *
     * <p>Listeners are attached at build time rather than added afterwards, so a listener cannot miss the
     * ready event — {@link LinkSlashCommandListener} registers its slash command from exactly that event,
     * because {@code awaitReady()} is never called and there is no other point at which JDA is known to be
     * up.
     *
     * @param eventListeners JDA event listeners, typically {@link net.dv8tion.jda.api.hooks.ListenerAdapter}
     *                       instances; pass none for a send-only bot
     */
    public static @NotNull DiscordBot start(@NotNull String botToken, @NotNull Object... eventListeners) {
        JDA jda = JDABuilder.createLight(botToken)
                .setEnabledIntents(Collections.emptyList())
                .addEventListeners(eventListeners)
                .build();
        return new DiscordBot(jda);
    }

    /** The JDA instance, or empty while it is not connected. */
    public @NotNull Optional<JDA> jda() {
        return this.jda.getStatus() == JDA.Status.CONNECTED ? Optional.of(this.jda) : Optional.empty();
    }

    /**
     * Closes the gateway connection and <em>waits</em> for JDA's threads to finish. Safe to call more
     * than once.
     *
     * <p>The wait is load-bearing, not politeness. {@link JDA#shutdown()} only requests shutdown; the
     * websocket reading thread then runs its own teardown, which lazily loads further classes
     * ({@code WebSocketClient.onShutdown}). If the module's {@code URLClassLoader} — and the host jar
     * behind it — has already been closed by then, that load fails with
     * {@code IllegalStateException: zip file closed} on disable. Blocking here keeps the class loaders
     * alive until JDA is done with them.
     */
    public void shutdown() {
        this.jda.shutdown();
        try {
            if (!this.jda.awaitShutdown(SHUTDOWN_TIMEOUT)) {
                // Escalate: cancel queued requests instead of holding the disable thread indefinitely.
                this.jda.shutdownNow();
                this.jda.awaitShutdown(SHUTDOWN_TIMEOUT);
            }
        } catch (InterruptedException exception) {
            this.jda.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
