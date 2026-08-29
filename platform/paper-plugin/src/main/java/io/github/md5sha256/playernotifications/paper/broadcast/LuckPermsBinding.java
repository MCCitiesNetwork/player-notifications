package io.github.md5sha256.playernotifications.paper.broadcast;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.logging.Level;

/**
 * The isolation guard in front of {@link LuckPermsPermissionLookup}.
 *
 * <p><b>No signature or field here names a LuckPerms type, and that is load-bearing.</b> LuckPerms is
 * declared {@code required: false}, so on a server without it those classes do not exist; a method that
 * mentioned one would force it to load before the guard could run, and the resulting
 * {@code NoClassDefFoundError} would take the host plugin down at enable. This is exactly the shape of
 * {@code essentials.convert.EssentialsMailBinding}, arrived at the same way.
 *
 * <p>Verify the property holds after any change:
 * {@code javap -c -p .../LuckPermsBinding.class | grep -i luckperms} should match string literals and the
 * call to our own {@code LuckPermsPermissionLookup.create} only — never a {@code net/luckperms} type.
 *
 * <p>A {@link LinkageError} is caught rather than allowed to propagate, for the reason the converter
 * module catches one: it is not a {@code RuntimeException}, so nothing above would handle it, and an
 * optional capability failing to load must not disable everything else.
 */
public final class LuckPermsBinding {

    private static final String PLUGIN_NAME = "LuckPerms";

    private LuckPermsBinding() {
    }

    /**
     * @return a lookup when LuckPerms is installed and enabled, otherwise empty — the caller turns that
     *         into "offline broadcasts are not available on this server" rather than an error
     */
    @NotNull
    public static Optional<PermissionLookup> tryCreate(@NotNull Plugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            plugin.getLogger().info(PLUGIN_NAME + " is not enabled; /broadcast --offline will be "
                    + "unavailable. Everything else about /broadcast works as normal.");
            return Optional.empty();
        }
        try {
            // The factory lives on LuckPermsPermissionLookup, not here, so that no method in *this*
            // class names a LuckPerms type even in its bytecode. Lazy constant-pool resolution would
            // probably have been enough, but HotSpot's verifier is permitted to load types named in a
            // method body while verifying it — and this class is loaded on every server, LuckPerms or
            // not. Touching LuckPermsPermissionLookup at all happens only inside this try.
            return Optional.of(LuckPermsPermissionLookup.create(plugin));
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not bind to " + PLUGIN_NAME
                    + "; /broadcast --offline will be unavailable", e);
            return Optional.empty();
        }
    }
}
