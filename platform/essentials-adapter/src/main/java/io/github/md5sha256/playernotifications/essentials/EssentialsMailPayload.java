package io.github.md5sha256.playernotifications.essentials;

import org.jetbrains.annotations.NotNull;

/**
 * The payload type for the {@code essentials-mail} data type: a single line of mail text.
 *
 * <p>This module owns its own payload class rather than reusing {@link String}, because
 * {@link io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry} keys processors,
 * serializers, and renderers by <em>payload class</em>. Two data types mapped to the same class share
 * one processor — so mapping this module to {@code String.class} would silently claim every other
 * "just a message" payload any other author registers.
 *
 * <p>Owning the class also scopes module unload correctly: {@code unregisterPayloadMapping} cascades
 * into {@code unregisterSerializer(payloadClass)}, which under {@code String.class} would have torn out
 * the host's shared {@code String} serializer for everyone.
 *
 * @param message the mail body delivered to the player
 */
public record EssentialsMailPayload(@NotNull String message) {
}
