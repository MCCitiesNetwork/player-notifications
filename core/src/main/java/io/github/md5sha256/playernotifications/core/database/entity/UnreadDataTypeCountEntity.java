package io.github.md5sha256.playernotifications.core.database.entity;

import org.jetbrains.annotations.NotNull;

/**
 * Internal entity record for one row of the grouped unread count: a data type and how many due,
 * unexpired notifications of it the viewing player has not seen.
 *
 * <p>Exists so the inbox filter screen can mark every category it lists from a single read. Counting
 * per category instead would mean one query per row on every open, which is why an earlier version of
 * that screen carried no counts at all.
 */
public record UnreadDataTypeCountEntity(@NotNull String notifPayloadType, int unreadCount) {
}
