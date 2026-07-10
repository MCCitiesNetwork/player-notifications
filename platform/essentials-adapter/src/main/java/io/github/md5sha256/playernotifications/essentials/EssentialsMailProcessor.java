package io.github.md5sha256.playernotifications.essentials;

import com.earth2me.essentials.Console;
import com.earth2me.essentials.IEssentials;
import io.github.md5sha256.playernotifications.api.processor.NotificationProcessor;
import net.ess3.api.IUser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Renders a plain-text notification payload as Essentials mail, one message per target player.
 *
 * <p>The Essentials mail API is not thread-safe, so delivery is marshalled onto the server main
 * thread when this processor is invoked from elsewhere.
 */
final class EssentialsMailProcessor implements NotificationProcessor<String> {

    private final Plugin plugin;
    private final IEssentials essentials;

    EssentialsMailProcessor(@NotNull Plugin plugin, @NotNull IEssentials essentials) {
        this.plugin = plugin;
        this.essentials = essentials;
    }

    @Override
    public void receiveNotification(@NotNull String payload, @NotNull List<UUID> targets) {
        if (Bukkit.isPrimaryThread()) {
            deliver(payload, targets);
        } else {
            Bukkit.getScheduler().runTask(this.plugin, () -> deliver(payload, targets));
        }
    }

    private void deliver(@NotNull String message, @NotNull List<UUID> targets) {
        for (UUID target : targets) {
            IUser user = this.essentials.getUser(target);
            if (user == null) {
                this.plugin.getLogger().warning("No Essentials user for UUID " + target + "; skipping mail");
                continue;
            }
            this.essentials.getMail().sendMail(user, Console.getInstance(), message);
        }
    }
}
