package io.github.md5sha256.playernotifications.core.serialize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializationException;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link PayloadSerializer} that maps a payload type to and from JSON with a shared Jackson
 * {@link ObjectMapper}. Reflection over records/POJOs means most payload types need no hand-written
 * serializer. Jackson stays entirely inside this class — the public contract is the neutral
 * {@link PayloadSerializer} interface.
 *
 * @param <T> the payload type
 */
public final class JacksonPayloadSerializer<T> implements PayloadSerializer<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;

    public JacksonPayloadSerializer(@NotNull ObjectMapper mapper, @NotNull Class<T> type) {
        this.mapper = mapper;
        this.type = type;
    }

    @Override
    public @NotNull String serialize(@NotNull T payload) {
        try {
            return this.mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new PayloadSerializationException("Failed to serialize payload of type " + this.type.getName(), e);
        }
    }

    @Override
    public @NotNull T deserialize(@NotNull String json) {
        try {
            return this.mapper.readValue(json, this.type);
        } catch (JsonProcessingException e) {
            throw new PayloadSerializationException("Failed to deserialize payload of type " + this.type.getName(), e);
        }
    }
}
