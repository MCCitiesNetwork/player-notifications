package io.github.md5sha256.playernotifications.discord;

import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers notifications as a Discord direct message from this module's own bot.
 *
 * <p>Being a {@link NotificationSink} rather than a
 * {@link io.github.md5sha256.playernotifications.api.processor.NotificationProcessor} is deliberate:
 * a sink participates in the player's stored preferences and in the fan-out, which is what a medium
 * should do. (The Essentials adapter registers a processor and therefore bypasses preferences — a
 * known quirk this module does not repeat.)
 *
 * <p>Result mapping: no linked account is {@link DeliveryResult#UNSUPPORTED} — the exact case
 * {@code UNSUPPORTED}'s javadoc names — while anything transient is {@link DeliveryResult#UNREACHABLE}
 * so the notification survives to the next delivery pass.
 */
public final class DiscordDmSink implements NotificationSink {

    private final DiscordAccountProvider accounts;
    private final DiscordMessageFactory factory;
    private final DiscordMessenger messenger;
    private final Logger logger;
    private final BooleanSupplier mainThreadCheck;

    public DiscordDmSink(
            @NotNull DiscordAccountProvider accounts,
            @NotNull DiscordMessageFactory factory,
            @NotNull DiscordMessenger messenger,
            @NotNull Logger logger) {
        this(accounts, factory, messenger, logger, DiscordDmSink::isServerMainThread);
    }

    /** Visible for testing: lets a test drive the main-thread guard without a running server. */
    DiscordDmSink(
            @NotNull DiscordAccountProvider accounts,
            @NotNull DiscordMessageFactory factory,
            @NotNull DiscordMessenger messenger,
            @NotNull Logger logger,
            @NotNull BooleanSupplier mainThreadCheck) {
        this.accounts = accounts;
        this.factory = factory;
        this.messenger = messenger;
        this.logger = logger;
        this.mainThreadCheck = mainThreadCheck;
    }

    @Override
    public @NotNull String mediumKey() {
        return DiscordMedia.DM;
    }

    @Override
    public @NotNull Component displayName() {
        // The interface default would title-case "discord-dm" into "Discord Dm".
        return Component.text("Discord DM");
    }

    @Override
    public @NotNull Component description() {
        return Component.text("Direct message from the server's Discord bot");
    }

    @Override
    public @NotNull DeliveryResult deliver(
            @NotNull RenderableNotification notification, @NotNull UUID target) {
        if (this.mainThreadCheck.getAsBoolean()) {
            this.logger.warning("Refusing to deliver a Discord DM on the main thread; "
                    + "this sink blocks on a Discord round trip");
            return DeliveryResult.UNREACHABLE;
        }

        Optional<Long> discordId;
        try {
            discordId = this.accounts.discordIdFor(target);
        } catch (RuntimeException exception) {
            this.logger.log(Level.WARNING,
                    "Failed to resolve a Discord account for " + target, exception);
            return DeliveryResult.UNREACHABLE;
        }
        if (discordId.isEmpty()) {
            return DeliveryResult.UNSUPPORTED;
        }

        MessageCreateData message = this.factory.create(notification);
        try {
            return this.messenger.send(discordId.get(), message);
        } catch (RuntimeException exception) {
            // Never propagate: RenderingProcessor's fan-out must reach the other media.
            this.logger.log(Level.WARNING,
                    "Failed to send a Discord DM to " + target, exception);
            return DeliveryResult.UNREACHABLE;
        }
    }

    /**
     * Whether we are on the server's main thread. Returns {@code false} when no server is running,
     * so the sink is usable outside Bukkit.
     */
    private static boolean isServerMainThread() {
        return Bukkit.getServer() != null && Bukkit.isPrimaryThread();
    }
}
