package io.github.md5sha256.playernotifications.paper.diagnostic;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.core.NotificationDelivery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Backs {@code /notifications test}: enqueues a {@link TestNotificationPayload} targeting one player and
 * immediately delivers it.
 *
 * <p>It persists through {@link NotificationService} and then calls
 * {@link NotificationDelivery#deliver(UUID)} rather than invoking a sink directly, so a test exercises
 * serialization, the due-notification query, dispatch precedence, the renderer, preference resolution,
 * sink fan-out and target pruning — the whole path a real notification takes. Calling a sink directly
 * would prove only that the sink itself works.
 */
public final class TestNotificationSender {

    /** How long an undelivered test notification lingers before the prune task reaps it. */
    private static final Duration EXPIRY = Duration.ofMinutes(10);

    private final Plugin plugin;
    private final NotificationService service;
    private final NotificationPreferences preferences;
    private final Supplier<NotificationDelivery> delivery;

    /**
     * @param delivery a supplier rather than an instance: {@code PlayerNotificationsPlugin.reload}
     *                 replaces the {@link NotificationDelivery} with a new object, so a captured
     *                 reference would silently go stale after a reload.
     */
    public TestNotificationSender(@NotNull Plugin plugin,
                                  @NotNull NotificationService service,
                                  @NotNull NotificationPreferences preferences,
                                  @NotNull Supplier<NotificationDelivery> delivery) {
        this.plugin = plugin;
        this.service = service;
        this.preferences = preferences;
        this.delivery = delivery;
    }

    /**
     * Enqueues and delivers a test notification to the given player, off the main thread — the mappers
     * and preference lookups do blocking JDBC, and {@code DiscordDmSink} refuses to run on the main
     * thread outright.
     */
    public void send(@NotNull Player player, @NotNull String message) {
        UUID target = player.getUniqueId();
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                this.service.enqueueNotification(new TypedNotification<>(
                        "test:" + UUID.randomUUID(),
                        Instant.now(),
                        Instant.now().plus(EXPIRY),
                        new NotificationTarget(List.of(target)),
                        TestNotificationPayload.TEST_DATA_TYPE,
                        new TestNotificationPayload(message),
                        0), false);

                this.delivery.get().deliver(target);

                player.sendMessage(report(target));
            } catch (RuntimeException ex) {
                this.plugin.getLogger().log(Level.WARNING, "Test notification failed for " + target, ex);
                player.sendMessage(Component.text(
                        "Test notification failed: " + ex.getMessage(), NamedTextColor.RED));
            }
        });
    }

    /**
     * Reports the media the notification was <em>attempted</em> on, not the ones that succeeded:
     * {@code RenderingProcessor} folds every sink's result into a single disposition and reports nothing
     * per medium. Naming them still makes the common confusion visible — an operator expecting a Discord
     * DM who sees only {@code chat} here knows their preference was never set.
     */
    private @NotNull Component report(@NotNull UUID target) {
        Set<String> media = this.preferences.preferredMedia(
                target, TestNotificationPayload.TEST_DATA_TYPE);
        if (media.isEmpty()) {
            return Component.text(
                    "Test notification sent, but you have no delivery media selected for 'test'.",
                    NamedTextColor.YELLOW);
        }
        return Component.text("Test notification attempted on: ", NamedTextColor.GREEN)
                .append(Component.text(String.join(", ", media), NamedTextColor.WHITE));
    }
}
