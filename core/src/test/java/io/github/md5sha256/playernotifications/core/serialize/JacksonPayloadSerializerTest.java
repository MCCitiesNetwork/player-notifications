package io.github.md5sha256.playernotifications.core.serialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializationException;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JacksonPayloadSerializerTest {

    private record Greeting(String message, int count) {
    }

    private static <T> PayloadSerializer<T> serializerFor(Class<T> type) {
        return new JacksonPayloadSerializer<>(new ObjectMapper(), type);
    }

    @Test
    @DisplayName("round-trips a record payload through JSON")
    void roundTripsRecord() {
        PayloadSerializer<Greeting> serializer = serializerFor(Greeting.class);

        String json = serializer.serialize(new Greeting("hi", 3));
        Greeting decoded = serializer.deserialize(json);

        Assertions.assertEquals(new Greeting("hi", 3), decoded);
    }

    @Test
    @DisplayName("JSON-quotes a String payload so the JSON column stays valid")
    void quotesStringPayload() {
        PayloadSerializer<String> serializer = serializerFor(String.class);

        String json = serializer.serialize("hello");

        Assertions.assertEquals("\"hello\"", json);
        Assertions.assertEquals("hello", serializer.deserialize(json));
    }

    @Test
    @DisplayName("wraps deserialization failures in PayloadSerializationException")
    void wrapsDeserializeFailure() {
        PayloadSerializer<Greeting> serializer = serializerFor(Greeting.class);

        Assertions.assertThrows(PayloadSerializationException.class,
                () -> serializer.deserialize("not json"));
    }
}