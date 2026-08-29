package io.github.md5sha256.playernotifications.paper.broadcast;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Today's default audience: every online player matching the {@code --perm} list.
 *
 * <p>Thin by design — it is the one class in the online path that touches Bukkit, so it is the one that
 * cannot be unit tested. Everything worth testing lives in {@link BroadcastRecipients}.
 *
 * <h2>Threading</h2>
 *
 * <p>Bukkit permission state belongs to the main thread, but {@link BroadcastAudience#resolve} is now
 * called from the command's async task, because the offline audience blocks on a permission backend and
 * must <em>not</em> run on the main thread. Rather than make the command branch on which audience it
 * holds, this class marshals back onto the main thread itself, so each audience owns its own
 * requirement.
 *
 * <p>The {@code isPrimaryThread()} short-circuit is not tidiness: without it, a call already on the
 * main thread would block waiting for a task only the main thread can run, which is a deadlock.
 */
public final class OnlineBroadcastAudience implements BroadcastAudience {

    private final Plugin plugin;
    private final Server server;

    public OnlineBroadcastAudience(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }

    @Override
    @NotNull
    public List<UUID> resolve(@NotNull List<String> permissions,
                              @NotNull BroadcastArguments.Chain chain) {
        if (this.server.isPrimaryThread()) {
            return selectNow(permissions, chain);
        }
        try {
            return this.server.getScheduler()
                    .callSyncMethod(this.plugin, () -> selectNow(permissions, chain))
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resolving the broadcast audience", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not resolve the broadcast audience", e.getCause());
        }
    }

    /** Main thread only — {@link Player#hasPermission} is not safe to ask from anywhere else. */
    @NotNull
    private List<UUID> selectNow(@NotNull List<String> permissions,
                                 @NotNull BroadcastArguments.Chain chain) {
        List<BroadcastRecipients.Candidate> candidates = new ArrayList<>();
        for (Player player : this.server.getOnlinePlayers()) {
            candidates.add(new BroadcastRecipients.Candidate(player.getUniqueId(), player::hasPermission));
        }
        return BroadcastRecipients.select(candidates, permissions, chain);
    }
}
