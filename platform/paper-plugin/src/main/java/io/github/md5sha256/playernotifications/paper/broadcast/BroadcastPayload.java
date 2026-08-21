package io.github.md5sha256.playernotifications.paper.broadcast;

import org.jetbrains.annotations.NotNull;

/**
 * A mapping-only marker payload for {@link Broadcaster#BROADCAST_DATA_TYPE} — it is never enqueued,
 * never serialized and never rendered.
 *
 * <p>A broadcast is never stored (see {@link Broadcaster}'s javadoc), so nothing ever constructs one of
 * these. It exists solely so {@code "broadcast"} is a registered {@code dataType} mapping — see
 * {@code PlayerNotificationsPlugin#onEnable}, which calls
 * {@code dataTypeRegistry().registerPayloadMapping(Broadcaster.BROADCAST_DATA_TYPE, BroadcastPayload.class)}
 * with no serializer, renderer or processor registered alongside it. That single registration is what
 * makes {@code broadcast} enumerate in {@code dataTypeRegistry().dataTypes()}, which is what the
 * preference dialogs walk to list configurable types — without it, a player would have no way to silence
 * broadcasts at all.
 *
 * <p>Should a future broadcast variant need to be storable (see {@code BroadcastAudience}'s "Room for
 * offline delivery" notes), this record's field is the upgrade path: give it a
 * {@code PayloadSerializer<BroadcastPayload>} and a {@code NotificationRenderer<BroadcastPayload>} and it
 * becomes a real, storable payload without renaming the {@code dataType} everything already agrees on.
 */
public record BroadcastPayload(@NotNull String message) {
}
