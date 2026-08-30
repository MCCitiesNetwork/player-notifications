package io.github.md5sha256.playernotifications.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Nested
    class DisplayNames {

        @Test
        void anUnregisteredTypeHasNoDisplayName() {
            assertTrue(new NotificationDataTypeRegistry().displayName("mail").isEmpty());
        }

        @Test
        void aRegisteredDisplayNameComesBack() {
            NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
            registry.registerDisplayName("mail", "<gold>Personal Mail</gold>");
            assertEquals(Optional.of("<gold>Personal Mail</gold>"), registry.displayName("mail"));
        }

        @Test
        void reRegisteringOverwrites() {
            NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
            registry.registerDisplayName("mail", "First");
            registry.registerDisplayName("mail", "Second");
            assertEquals(Optional.of("Second"), registry.displayName("mail"));
        }

        @Test
        void unregisterRemovesIt() {
            NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
            registry.registerDisplayName("mail", "Personal Mail");
            registry.unregisterDisplayName("mail");
            assertTrue(registry.displayName("mail").isEmpty());
        }

        @Test
        void aDisplayNameIsIndependentOfThePayloadMapping() {
            // A module may name a type it does not own the payload for, and dataTypes() must not
            // start reporting a type that has no mapping just because someone named it.
            NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
            registry.registerDisplayName("mail", "Personal Mail");
            assertTrue(registry.dataTypes().isEmpty());
        }
    }

    @Nested
    class UnmapDataType {

        /**
         * Two data types sharing one payload class is the shape operator-defined types take: the
         * renderer map is class-keyed, so removing one key must not tear the shared renderer down.
         */
        @Test
        void removesOnlyThatMappingAndLeavesTheSharedRendererRegistered() {
            NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
            registry.registerPayloadMapping("a", String.class);
            registry.registerPayloadMapping("b", String.class);
            registry.registerRenderer(String.class,
                    (payload, target) -> { throw new UnsupportedOperationException(); });

            registry.unmapDataType("a");

            assertEquals(Set.of("b"), registry.dataTypes());
            assertTrue(registry.getRenderer(String.class).isPresent());
        }

        @Test
        void isANoOpForAnUnmappedType() {
            NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
            registry.registerPayloadMapping("a", String.class);

            registry.unmapDataType("nonesuch");

            assertEquals(Set.of("a"), registry.dataTypes());
        }
    }
}
