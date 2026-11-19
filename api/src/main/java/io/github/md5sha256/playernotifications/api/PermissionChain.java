package io.github.md5sha256.playernotifications.api;

/** How several permission nodes combine when selecting a {@link NotificationAudience}. */
public enum PermissionChain {
    /** A player must hold every node. */
    AND,
    /** A player must hold at least one node. */
    OR
}
