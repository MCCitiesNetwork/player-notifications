package io.github.md5sha256.playernotifications.paper.broadcast;

import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code --offline} audience: everyone a {@link PermissionLookup} says holds the permissions,
 * minus whoever is online right now.
 *
 * <p>Online players are dropped so the two audiences stay disjoint — they are
 * {@link OnlineBroadcastAudience}'s business, and a persistent broadcast reaches them through the push
 * step regardless.
 *
 * <p>That is the whole class. With the lookup answering set-valued queries there is no decision left
 * here, which is why it carries no tests of its own: everything worth asserting lives in
 * {@link PermissionLookup}'s implementation, which needs a live backend, or in
 * {@code BroadcastArguments}, which does not.
 *
 * <p><b>Blocks.</b> Every call is a query against a permission backend, so this runs on the async task
 * only — the opposite of {@link OnlineBroadcastAudience}'s requirement, which is why each class owns
 * its own threading rather than the command choosing.
 */
public final class OfflineBroadcastAudience implements BroadcastAudience {

    private final Server server;
    private final PermissionLookup lookup;

    public OfflineBroadcastAudience(@NotNull Server server, @NotNull PermissionLookup lookup) {
        this.server = server;
        this.lookup = lookup;
    }

    /** For the startup log line and any diagnostic naming where the answers come from. */
    @NotNull
    public PermissionLookup lookup() {
        return this.lookup;
    }

    @Override
    public @NotNull List<UUID> resolve(@NotNull List<String> permissions,
                                       @NotNull BroadcastArguments.Chain chain) {
        Set<UUID> matching = this.lookup.matching(permissions, chain);
        List<UUID> offline = new ArrayList<>(matching.size());
        for (UUID candidate : matching) {
            if (this.server.getPlayer(candidate) == null) {
                offline.add(candidate);
            }
        }
        return List.copyOf(offline);
    }
}
