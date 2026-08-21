package io.github.md5sha256.playernotifications.paper.broadcast;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Today's only {@link BroadcastAudience}: every currently online player, filtered by
 * {@link BroadcastRecipients}' OR rule.
 *
 * <p><b>Must be called on the main/command thread, not the async delivery task.</b> Bukkit permission
 * state belongs to the main thread, so permissions are evaluated eagerly here and only the resulting
 * {@code List<UUID>} crosses into the async task that actually delivers — the same rule
 * {@code MailCommand.send} follows for its {@code TagResolver}. This is a property of *this*
 * implementation, not of {@link BroadcastAudience}: an offline-capable implementation will almost
 * certainly need a permission lookup that blocks, and so cannot keep this constraint.
 *
 * <p>Deliberately a pure map-and-delegate onto {@link BroadcastRecipients#select}: it is the one class
 * in the broadcast feature that touches Bukkit, so it is the one class that cannot be unit tested, and
 * it is kept free of any decision worth testing so that everything worth testing lives in
 * {@link BroadcastRecipients} instead.
 */
public final class OnlineBroadcastAudience implements BroadcastAudience {

    private final Server server;

    public OnlineBroadcastAudience(@NotNull Server server) {
        this.server = server;
    }

    @Override
    @NotNull
    public List<UUID> resolve(@NotNull List<String> permissions) {
        List<BroadcastRecipients.Candidate> candidates = new ArrayList<>();
        for (Player player : this.server.getOnlinePlayers()) {
            candidates.add(new BroadcastRecipients.Candidate(player.getUniqueId(), player::hasPermission));
        }
        return BroadcastRecipients.select(candidates, permissions);
    }
}
