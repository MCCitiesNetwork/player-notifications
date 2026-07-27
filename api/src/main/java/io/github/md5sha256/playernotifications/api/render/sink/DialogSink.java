package io.github.md5sha256.playernotifications.api.render.sink;

import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Delivers a {@link RenderableNotification} as a Paper {@link Dialog}: a {@link DialogBase} (title)
 * with a single {@link DialogBody#plainMessage(Component) plain-message body} and a single dismiss
 * {@link ActionButton}, closing after the player acknowledges it. Only reaches players who are
 * currently online.
 *
 * <p>Showing the dialog is marshalled onto the server main thread when this sink is invoked off it, as
 * {@code EssentialsMailProcessor} already does — delivery runs on the async prune/join path.
 */
public final class DialogSink implements NotificationSink {

    public static final String MEDIUM_KEY = "dialog";

    private static final Component DISMISS_LABEL = Component.text("Dismiss");

    private final Plugin plugin;

    public DialogSink(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String mediumKey() {
        return MEDIUM_KEY;
    }

    @Override
    public @NotNull DeliveryResult deliver(@NotNull RenderableNotification notification, @NotNull UUID target) {
        Player player = Bukkit.getPlayer(target);
        if (player == null) {
            return DeliveryResult.UNREACHABLE;
        }
        Dialog dialog = buildDialog(notification);
        if (Bukkit.isPrimaryThread()) {
            player.showDialog(dialog);
        } else {
            Bukkit.getScheduler().runTask(this.plugin, () -> player.showDialog(dialog));
        }
        return DeliveryResult.DELIVERED;
    }

    @NotNull
    private Dialog buildDialog(@NotNull RenderableNotification notification) {
        ActionButton dismissButton = ActionButton.builder(DISMISS_LABEL).build();
        DialogBase base = DialogBase.builder(notification.title())
                .body(List.of(DialogBody.plainMessage(notification.body())))
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        return Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(base).type(DialogType.notice(dismissButton));
        });
    }
}
