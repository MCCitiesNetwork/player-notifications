package io.github.md5sha256.playernotifications.paper.customtype;

import org.jetbrains.annotations.NotNull;

/**
 * The payload of every operator-declared notification type — one record for all of them.
 *
 * <p><b>It carries its own {@code typeKey}, and that is load-bearing rather than redundant.</b>
 * {@code NotificationDataTypeRegistry} keys renderers and serializers by <em>payload class</em> and
 * mappings by {@code dataType}; a file read at runtime cannot supply a class per key, so N declared
 * types map to this one class and therefore to one {@link CustomTypeRenderer}. The key stored here is
 * the only thing that renderer has to tell them apart with.
 *
 * <p>{@code message} is the raw MiniMessage the sender typed, not a rendered component, so how a
 * stored notification reads is decided in {@link CustomTypeRenderer} and only there.
 *
 * @param typeKey the declared {@code dataType} this notification was sent under
 * @param message the sender's message, as raw MiniMessage
 */
public record CustomNotificationPayload(@NotNull String typeKey, @NotNull String message) {
}
