package io.github.md5sha256.playernotifications.paper;

import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Delivers a joining player's due notifications, gated by {@code deliver-on-join} in
 * {@code settings.yml} and delayed by {@code join-delivery-delay-seconds}.
 *
 * <p>This is a <em>trigger</em> for the existing delivery loop, the same kind of thing as the async
 * prune task, which is why it lives in the Paper bootstrap rather than behind one of the registries.
 *
 * <p>The listener is <strong>always registered</strong> and returns early when disabled, rather than
 * being registered conditionally: {@code /notifications reload} can then flip the toggle through
 * {@link #reloadSettings(boolean, long)} without unregistering anything through {@code HandlerList}.
 */
public final class JoinDeliveryListener implements Listener {

    private final Plugin plugin;
    private final Supplier<NotificationDelivery> delivery;

    // Mutable rather than final, and volatile, so a reload applies to the already-registered listener
    // — the same idiom as DatabaseNotificationPreferences.reloadDefaultMedia and
    // PreferenceDialogRouter.reloadCategories.
    private volatile boolean enabled;
    private volatile long delaySeconds;

    /**
     * @param delivery a supplier rather than an instance: {@code PlayerNotificationsPlugin.reload}
     *                 replaces the {@link NotificationDelivery} with a new object, so a captured
     *                 reference would silently go stale after a reload.
     */
    public JoinDeliveryListener(@NotNull Plugin plugin,
                                @NotNull Supplier<NotificationDelivery> delivery,
                                boolean enabled,
                                long delaySeconds) {
        this.plugin = plugin;
        this.delivery = delivery;
        this.enabled = enabled;
        this.delaySeconds = Math.max(0L, delaySeconds);
    }

    /**
     * Applies a reloaded {@code deliver-on-join} / {@code join-delivery-delay-seconds} pair. Takes
     * effect on the next join, and also cancels any delivery already waiting out its delay — see
     * {@link #deliver(UUID)}.
     */
    public void reloadSettings(boolean enabled, long delaySeconds) {
        this.enabled = enabled;
        this.delaySeconds = Math.max(0L, delaySeconds);
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!this.enabled) {
            return;
        }
        Player player = event.getPlayer();
        // Async: the mappers and preference lookups do blocking JDBC, and DiscordDmSink refuses to run
        // on the main thread outright.
        Runnable task = () -> {
            // The player may have left during the delay. Delivering anyway would consume the
            // notification into nothing: ChatSink reports DELIVERED against an offline Audience, and
            // the DELETE-wins fan-out would then drop it permanently.
            if (player.isOnline()) {
                deliver(player.getUniqueId());
            }
        };
        long delay = this.delaySeconds;
        if (delay == 0L) {
            this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, task);
        } else {
            this.plugin.getServer().getScheduler()
                    .runTaskLaterAsynchronously(this.plugin, task, delay * 20L);
        }
    }

    /**
     * Runs one delivery pass for the given player. Re-reads the toggle, so a reload that turns the
     * trigger off while a delivery is waiting out its delay cancels that delivery rather than letting
     * it land.
     */
    void deliver(@NotNull UUID target) {
        if (!this.enabled) {
            return;
        }
        try {
            this.delivery.get().deliver(target);
        } catch (RuntimeException ex) {
            // Swallowed after logging: an uncaught throw inside a scheduled task is reported by Bukkit
            // with no useful attribution, and one player's failed delivery must not affect the next
            // join. Nothing is sent to the player — unlike /notifications test, this is not a
            // diagnostic they asked for.
            this.plugin.getLogger().log(Level.WARNING,
                    "Join delivery failed for " + target, ex);
        }
    }

    /** Test seam: the currently effective {@code deliver-on-join} value. */
    boolean enabled() {
        return this.enabled;
    }

    /** Test seam: the currently effective {@code join-delivery-delay-seconds} value. */
    long delaySeconds() {
        return this.delaySeconds;
    }
}
