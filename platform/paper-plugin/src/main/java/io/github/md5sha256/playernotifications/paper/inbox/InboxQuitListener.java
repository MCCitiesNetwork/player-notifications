package io.github.md5sha256.playernotifications.paper.inbox;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Drops a player's inbox page cursor on quit, so the map does not grow unboundedly and a rejoining
 * player starts at page 1. The sibling of {@code PreferenceQuitListener}, kept separate because the
 * two clear unrelated state owned by different routers.
 *
 * <p>Takes a list rather than one router because the unfiltered {@code /notifications} inbox and the
 * {@code mail}-filtered {@code /mail} inbox are two independent {@link InboxRouter} instances, each with
 * its own cursor and last-listed map to drop.
 */
public final class InboxQuitListener implements Listener {

    private final List<InboxRouter> routers;

    public InboxQuitListener(@NotNull List<InboxRouter> routers) {
        this.routers = List.copyOf(routers);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        for (InboxRouter router : this.routers) {
            router.drop(event.getPlayer().getUniqueId());
        }
    }
}
