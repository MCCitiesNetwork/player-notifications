package io.github.md5sha256.playernotifications.essentials;

import com.earth2me.essentials.Console;
import com.earth2me.essentials.IEssentials;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import net.ess3.api.IUser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Delivers a {@link RenderableNotification} as Essentials mail: one message per target, title and body
 * joined into the single line of text Essentials mail can hold.
 *
 * <p>A sink rather than a processor, so Essentials mail is a <em>medium</em> a player can choose for any
 * notification type, alongside chat, dialogs and Discord DMs — it no longer claims a data type of its
 * own. Mail reaches players who are offline, which is the whole point of offering it.
 *
 * <p>The Essentials mail API is not thread-safe and the delivery loop is async, so sending is marshalled
 * onto the server main thread. Like {@code ChatSink}, that means the result is reported before the send
 * actually runs: an unknown Essentials user is logged rather than surfaced as
 * {@link DeliveryResult#UNREACHABLE}. Resolving the user up front would mean touching Essentials state
 * off the main thread, which is exactly what the marshalling exists to avoid.
 */
final class EssentialsMailSink implements NotificationSink {

    /** The medium key this sink registers under. */
    static final String MEDIUM_KEY = "essentials-mail";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final IEssentials essentials;

    EssentialsMailSink(@NotNull Plugin plugin, @NotNull IEssentials essentials) {
        this.plugin = plugin;
        this.essentials = essentials;
    }

    @Override
    public @NotNull String mediumKey() {
        return MEDIUM_KEY;
    }

    @Override
    public @NotNull Component description() {
        return Component.text("Sent to your Essentials mailbox; read it with /mail read.");
    }

    @Override
    public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification, @NotNull UUID target) {
        String message = flatten(notification);
        if (Bukkit.isPrimaryThread()) {
            send(message, target);
        } else {
            Bukkit.getScheduler().runTask(this.plugin, () -> send(message, target));
        }
        return DeliveryResult.DELIVERED;
    }

    /**
     * Essentials mail is one string per message, so the title and body are joined rather than sent as
     * two pieces of mail. Legacy section codes keep the colours a {@link Component} carries; Essentials
     * stores and replays the string as-is.
     */
    @NotNull
    private static String flatten(@NotNull RenderableNotification notification) {
        String title = LEGACY.serialize(notification.title());
        String body = LEGACY.serialize(notification.body());
        if (title.isEmpty()) {
            return body;
        }
        if (body.isEmpty()) {
            return title;
        }
        return title + ": " + body;
    }

    private void send(@NotNull String message, @NotNull UUID target) {
        IUser user = this.essentials.getUser(target);
        if (user == null) {
            this.plugin.getLogger().warning("No Essentials user for UUID " + target + "; skipping mail");
            return;
        }
        this.essentials.getMail().sendMail(user, Console.getInstance(), message);
    }
}
