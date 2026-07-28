package io.github.md5sha256.playernotifications.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class NotificationDataTypeRegistryTest {

    @Test
    void dataTypesReturnsEveryRegisteredMapping() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping("mail", String.class);
        registry.registerPayloadMapping("warning", String.class);

        Assertions.assertEquals(Set.of("mail", "warning"), registry.dataTypes());
    }

    @Test
    void dataTypesIsEmptyForAFreshRegistry() {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();

        Assertions.assertEquals(Set.of(), registry.dataTypes());
    }
}
