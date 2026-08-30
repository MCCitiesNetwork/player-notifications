package io.github.md5sha256.playernotifications.paper.broadcast;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The only {@link PermissionLookup}: resolves permission holders through LuckPerms' storage.
 *
 * <h2>Why not simply load each user</h2>
 *
 * <p>{@code UserManager#loadUser(uuid)} answers per player and would mean one storage round trip per
 * candidate. And the naive one-query alternative — {@code searchAll(NodeMatcher.key(permission))} — is
 * <b>wrong</b>: it searches nodes as <em>stored</em>, and an inherited permission is not stored on the
 * user. Only their group membership is.
 *
 * <p>That is the way in. Per permission node, three stages:
 *
 * <ol>
 *     <li><b>Which groups grant it.</b> Walk the loaded groups and test each one's <em>resolved</em>
 *     permission data. This costs no I/O — groups are held in memory — and it resolves group-to-group
 *     inheritance, so a group inheriting from a granting group is caught.</li>
 *     <li><b>Who is in those groups.</b> One {@code searchAll} per granting group, matching the
 *     inheritance node. Group membership <em>is</em> stored, which is why this works where stage 3
 *     alone does not.</li>
 *     <li><b>Who holds it directly.</b> One {@code searchAll} for the permission itself, catching a
 *     node assigned to a user rather than through a group. Negated nodes subtract, expired nodes are
 *     dropped.</li>
 * </ol>
 *
 * <p>Two to four queries per node, against N for the per-user form.
 *
 * <h2>The default group</h2>
 *
 * <p>If the {@code default} group grants the node, membership is implicit and stored on nobody, so
 * stage 2 finds no one. That case falls back to {@code getUniqueUsers()} — everyone the backend knows.
 * It is reachable only by an operator naming a permission their default group grants, which is a
 * deliberate act rather than an accident of omission.
 *
 * <h2>Known approximations</h2>
 *
 * <ul>
 *     <li><b>Context-conditional permissions are answered in the default context.</b> There is no
 *     per-player context to evaluate against for an absent player, so this is inherent rather than a
 *     shortcut — a node granted only in one world reads as granted.</li>
 *     <li><b>A player with no stored LuckPerms data is never matched</b>, except through the
 *     default-group fallback. They cannot hold a non-default permission, so this is consistent.</li>
 * </ul>
 *
 * <p>Every method here blocks on LuckPerms' storage. Never call it from the main thread.
 */
public final class LuckPermsPermissionLookup implements PermissionLookup {

    /** LuckPerms' implicit group: every player is a member without a stored inheritance node. */
    private static final String DEFAULT_GROUP = "default";

    private final LuckPerms luckPerms;
    private final Logger logger;

    /**
     * Resolves LuckPerms' API and builds a lookup over it. Called only from
     * {@link LuckPermsBinding#tryCreate}, after its {@code isPluginEnabled} guard has passed — this is
     * the first thing that loads this class, and therefore the first thing that can fail with a
     * {@link LinkageError} on a server without LuckPerms. That failure is the binding's to catch.
     */
    @NotNull
    static PermissionLookup create(@NotNull org.bukkit.plugin.Plugin plugin) {
        return new LuckPermsPermissionLookup(LuckPermsProvider.get(), plugin.getLogger());
    }

    public LuckPermsPermissionLookup(@NotNull LuckPerms luckPerms, @NotNull Logger logger) {
        this.luckPerms = luckPerms;
        this.logger = logger;
    }

    @Override
    public @NotNull String providerName() {
        return "LuckPerms";
    }

    @Override
    public @NotNull Set<UUID> matching(@NotNull List<String> permissions,
                                       @NotNull BroadcastArguments.Chain chain) {
        if (permissions.isEmpty()) {
            return Set.of();
        }
        // Groups are loaded once up front so stage 1 sees a complete set rather than whatever happens
        // to be cached.
        this.luckPerms.getGroupManager().loadAllGroups().join();

        Set<UUID> combined = null;
        for (String permission : permissions) {
            Set<UUID> holders = holdersOf(permission);
            if (combined == null) {
                combined = new LinkedHashSet<>(holders);
            } else if (chain == BroadcastArguments.Chain.AND) {
                combined.retainAll(holders);
            } else {
                combined.addAll(holders);
            }
            // An AND that has already emptied cannot recover, and each further node is a query.
            if (combined.isEmpty() && chain == BroadcastArguments.Chain.AND) {
                return Set.of();
            }
        }
        return combined == null ? Set.of() : combined;
    }

    /** Stages 1 to 3 for one permission node. */
    @NotNull
    private Set<UUID> holdersOf(@NotNull String permission) {
        Set<String> grantingGroups = new LinkedHashSet<>();
        for (Group group : this.luckPerms.getGroupManager().getLoadedGroups()) {
            if (grants(group, permission)) {
                grantingGroups.add(group.getName());
            }
        }

        if (grantingGroups.contains(DEFAULT_GROUP)) {
            // Implicit membership: nobody has a stored inheritance node for it, so the honest answer
            // is everyone the backend knows about.
            this.logger.fine(() -> "'" + permission + "' is granted by the default group; "
                    + "resolving to every known user");
            return this.luckPerms.getUserManager().getUniqueUsers().join();
        }

        Set<UUID> holders = new LinkedHashSet<>();
        for (String groupName : grantingGroups) {
            NodeMatcher<InheritanceNode> matcher =
                    NodeMatcher.key(InheritanceNode.builder(groupName).build());
            holders.addAll(this.luckPerms.getUserManager().searchAll(matcher).join().keySet());
        }

        // Stage 3: assigned directly rather than through a group. Also the only place a negation can
        // appear, so it both adds and subtracts.
        Map<UUID, Collection<Node>> direct =
                this.luckPerms.getUserManager().searchAll(NodeMatcher.key(permission)).join();
        Set<UUID> negated = new HashSet<>();
        for (Map.Entry<UUID, Collection<Node>> entry : direct.entrySet()) {
            for (Node node : entry.getValue()) {
                if (node.hasExpired()) {
                    continue;
                }
                if (node.getValue()) {
                    holders.add(entry.getKey());
                } else {
                    negated.add(entry.getKey());
                }
            }
        }
        holders.removeAll(negated);
        return holders;
    }

    private boolean grants(@NotNull Group group, @NotNull String permission) {
        return group.getCachedData()
                .getPermissionData(QueryOptions.defaultContextualOptions())
                .checkPermission(permission)
                .asBoolean();
    }
}
