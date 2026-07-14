package io.github.md5sha256.playernotifications.api.serialize;

import org.jetbrains.annotations.NotNull;

/**
 * Converts a notification payload to and from the JSON string persisted in the {@code notifPayload}
 * column. This is the sole serialization type crossing the public API boundary: the underlying JSON
 * library is an implementation detail of whoever supplies the serializer, so callers register payload
 * types without depending on it.
 *
 * @param <T> the payload type this serializer handles
 */
public interface PayloadSerializer<T> {

    /**
     * Serializes a payload to the JSON string stored in {@code notifPayload}.
     *
     * @throws PayloadSerializationException if the payload cannot be serialized
     */
    @NotNull String serialize(@NotNull T payload);

    /**
     * Deserializes a payload from its stored JSON string form.
     *
     * @throws PayloadSerializationException if the JSON cannot be deserialized into {@code T}
     */
    @NotNull T deserialize(@NotNull String json);

}