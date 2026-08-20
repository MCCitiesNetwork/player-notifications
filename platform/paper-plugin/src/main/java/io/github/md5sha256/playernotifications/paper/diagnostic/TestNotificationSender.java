package io.github.md5sha256.playernotifications.paper.diagnostic;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
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

    private static final Component SEPARATOR = Component.text(", ", NamedTextColor.GRAY);

    /** The one thing a player can do about either not-delivered case, so both replies end with it. */
    private static final Component USE_PREFERENCES = Component.text()
            .append(Component.text("Use ", NamedTextColor.GRAY))
            .append(Component.text("/notifications preferences", NamedTextColor.WHITE))
            .append(Component.text(" to choose how test notifications reach you.", NamedTextColor.GRAY))
            .build();

    /** What a globally muted player is told, naming the one way out. */
    private static final Component USE_UNMUTE = Component.text()
            .append(Component.text("Use ", NamedTextColor.GRAY))
            .append(Component.text("/notifications unmute", NamedTextColor.WHITE))
            .append(Component.text(" to receive notifications again.", NamedTextColor.GRAY))
            .build();

    private final Plugin plugin;
    private final NotificationService service;
    private final NotificationPreferences preferences;
    private final NotificationSinkRegistry sinkRegistry;
    private final Supplier<NotificationDelivery> delivery;

    /**
     * @param delivery a supplier rather than an instance: {@code PlayerNotificationsPlugin.reload}
     *                 replaces the {@link NotificationDelivery} with a new object, so a captured
     *                 reference would silently go stale after a reload.
     */
    public TestNotificationSender(@NotNull Plugin plugin,
                                  @NotNull NotificationService service,
                                  @NotNull NotificationPreferences preferences,
                                  @NotNull NotificationSinkRegistry sinkRegistry,
                                  @NotNull Supplier<NotificationDelivery> delivery) {
        this.plugin = plugin;
        this.service = service;
        this.preferences = preferences;
        this.sinkRegistry = sinkRegistry;
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
                        "The test notification could not be sent. The details are in the server "
                                + "console: " + ex.getMessage(), NamedTextColor.RED));
            }
        });
    }

    /**
     * Reports the media the notification was <em>attempted</em> on, not the ones that succeeded:
     * {@code RenderingProcessor} folds every sink's result into a single disposition and reports nothing
     * per medium. Naming them still makes the common confusion visible — someone expecting a Discord DM
     * who sees only Chat here knows their preference was never set.
     *
     * <p>Media are named by {@link NotificationSinkRegistry#displayName(String)} rather than by raw key,
     * matching what the preference dialogs show, and the three states a preference can be in are
     * distinguished. A silenced type in particular used to report as "attempted on: none", which reads
     * as a failure rather than as the setting the player chose.
     */
    private @NotNull Component report(@NotNull UUID target) {
        if (this.preferences.isMuted(target)) {
            return Component.text("Your notifications are muted, so it was suppressed. ",
                            NamedTextColor.YELLOW)
                    .append(USE_UNMUTE);
        }

        Set<String> media = this.preferences.preferredMedia(
                target, TestNotificationPayload.TEST_DATA_TYPE);

        if (media.contains(NotificationPreferences.SILENCED_MEDIUM)) {
            return Component.text("Test notifications are silenced for you, so it was suppressed. ",
                            NamedTextColor.YELLOW)
                    .append(USE_PREFERENCES);
        }
        if (media.isEmpty()) {
            return Component.text("Nothing was sent: you have no delivery methods chosen for test "
                            + "notifications. ", NamedTextColor.YELLOW)
                    .append(USE_PREFERENCES);
        }

        List<String> sorted = media.stream().sorted().toList();
        Component reply = Component.text("Test notification sent. Delivery attempted via: ",
                        NamedTextColor.GREEN)
                .append(Component.join(SEPARATOR, sorted.stream()
                        .map(medium -> this.sinkRegistry.displayName(medium)
                                .colorIfAbsent(NamedTextColor.WHITE))
                        .toList()));

        // A medium with no registered sink is skipped silently by RenderingProcessor, so without this the
        // reply would overstate what actually happened — the usual cause is a module that is not installed.
        List<String> unavailable = sorted.stream()
                .filter(medium -> this.sinkRegistry.getSink(medium).isEmpty())
                .toList();
        if (!unavailable.isEmpty()) {
            reply = reply.append(Component.newline())
                    .append(Component.text("Not set up on this server, so it was skipped: "
                            + String.join(", ", unavailable), NamedTextColor.YELLOW));
        }
        return reply;
    }
}
