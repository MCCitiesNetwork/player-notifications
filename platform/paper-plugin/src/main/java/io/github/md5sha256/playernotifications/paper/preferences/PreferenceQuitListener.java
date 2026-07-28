package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceSessionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Drops a player's staged {@code PreferenceEditSession} on quit, so a player who leaves mid-edit does
 * not resume stale staged changes on rejoin, and so the session map does not grow unboundedly.
 */
public final class PreferenceQuitListener implements Listener {

    private final PreferenceSessionManager sessions;

    public PreferenceQuitListener(@NotNull PreferenceSessionManager sessions) {
        this.sessions = sessions;
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        this.sessions.drop(event.getPlayer().getUniqueId());
    }
}
