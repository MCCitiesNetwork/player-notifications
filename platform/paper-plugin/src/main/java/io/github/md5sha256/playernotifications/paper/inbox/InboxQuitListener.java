package io.github.md5sha256.playernotifications.paper.inbox;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Drops a player's inbox page cursor on quit, so the map does not grow unboundedly and a rejoining
 * player starts at page 1. The sibling of {@code PreferenceQuitListener}, kept separate because the
 * two clear unrelated state owned by different routers.
 */
public final class InboxQuitListener implements Listener {

    private final InboxRouter router;

    public InboxQuitListener(@NotNull InboxRouter router) {
        this.router = router;
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        this.router.drop(event.getPlayer().getUniqueId());
    }
}
