package io.github.md5sha256.playernotifications.core.serialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.serialize.PayloadSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrySerializerTest {

    private record Greeting(String message, int count) {
    }

    private static NotificationDataTypeRegistry registryWith(String dataType, Class<?> type) {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping(dataType, type);
        registry.registerSerializer(type, new JacksonPayloadSerializer(new ObjectMapper(), type));
        return registry;
    }

    @Test
    @DisplayName("getSerializer resolves a serializer by data-type string")
    void resolvesByDataType() {
        NotificationDataTypeRegistry registry = registryWith("greeting", Greeting.class);

        PayloadSerializer<?> serializer = registry.getSerializer("greeting").orElseThrow();
        Object decoded = serializer.deserialize("{\"message\":\"hi\",\"count\":3}");

        Assertions.assertEquals(new Greeting("hi", 3), decoded);
    }

    @Test
    @DisplayName("unregisterPayloadMapping cascades to drop the serializer")
    void unregisterCascades() {
        NotificationDataTypeRegistry registry = registryWith("greeting", Greeting.class);

        registry.unregisterPayloadMapping("greeting");

        Assertions.assertTrue(registry.getSerializer("greeting").isEmpty());
        Assertions.assertTrue(registry.getSerializer(Greeting.class).isEmpty());
    }
}
