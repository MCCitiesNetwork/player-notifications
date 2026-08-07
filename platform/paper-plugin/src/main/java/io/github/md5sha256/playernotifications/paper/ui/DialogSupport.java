package io.github.md5sha256.playernotifications.paper.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Dialog plumbing shared by every screen in this plugin: the callback options every button uses, and
 * marshalling back onto the main thread after a blocking read.
 *
 * <p>Extracted from the preference dialogs, which had all three of these and are no longer the only
 * caller. Like the rest of {@code paper.ui}, this references nothing from this plugin.
 */
public final class DialogSupport {

    /**
     * How long a dialog button stays clickable after the dialog is shown. A dialog left open past this
     * makes its buttons inert; the player simply reopens it.
     */
    public static final Duration CALLBACK_LIFETIME = Duration.ofHours(1);

    private DialogSupport() {
    }

    @NotNull
    public static ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(CALLBACK_LIFETIME)
                .build();
    }

    public static void message(@NotNull Plugin plugin, @NotNull Player player, @NotNull Component component) {
        onMainThread(plugin, player, () -> player.sendMessage(component));
    }

    /**
     * Runs {@code action} on the server main thread, skipping it if the player has since logged out.
     * Showing a dialog is main-thread-only, and the read that precedes it is not.
     */
    public static void onMainThread(@NotNull Plugin plugin, @NotNull Player player, @NotNull Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        });
    }
}
