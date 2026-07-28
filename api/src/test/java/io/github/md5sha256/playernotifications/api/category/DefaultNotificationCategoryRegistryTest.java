package io.github.md5sha256.playernotifications.api.category;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class DefaultNotificationCategoryRegistryTest {

    @Test
    void registerCategoryStoresLabelAndDescription() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.registerCategory("economy", "Economy", "Shop and payments");

        Assertions.assertEquals(Set.of("economy"), registry.categoryKeys());
        Assertions.assertEquals("Economy", registry.label("economy"));
        Assertions.assertEquals("Shop and payments", registry.description("economy"));
        Assertions.assertEquals(Set.of(), registry.dataTypesFor("economy"));
    }

    @Test
    void claimDataTypeRegistersAnUnknownCategoryWithEmptyLabelAndDescription() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.claimDataType("economy", "mail");

        Assertions.assertEquals(Set.of("economy"), registry.categoryKeys());
        Assertions.assertEquals("", registry.label("economy"));
        Assertions.assertEquals("", registry.description("economy"));
        Assertions.assertEquals(Set.of("mail"), registry.dataTypesFor("economy"));
    }

    @Test
    void claimDataTypeDoesNotOverwriteAnAlreadyRegisteredLabel() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.registerCategory("economy", "Economy", "Shop and payments");
        registry.claimDataType("economy", "mail");

        Assertions.assertEquals("Economy", registry.label("economy"));
    }

    @Test
    void aDataTypeCanBeClaimedByMultipleCategories() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        registry.claimDataType("economy", "mail");
        registry.claimDataType("moderation", "mail");

        Assertions.assertEquals(Set.of("mail"), registry.dataTypesFor("economy"));
        Assertions.assertEquals(Set.of("mail"), registry.dataTypesFor("moderation"));
        Assertions.assertEquals(Set.of("economy", "moderation"), registry.categoryKeys());
    }

    @Test
    void unclaimDataTypeRemovesOnlyThatClaim() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.claimDataType("economy", "mail");
        registry.claimDataType("economy", "receipt");

        registry.unclaimDataType("economy", "mail");

        Assertions.assertEquals(Set.of("receipt"), registry.dataTypesFor("economy"));
    }

    @Test
    void unclaimDataTypeOnAnUnknownCategoryIsANoOp() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        Assertions.assertDoesNotThrow(() -> registry.unclaimDataType("nonexistent", "mail"));
    }

    @Test
    void dataTypesForAnUnknownCategoryIsEmpty() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        Assertions.assertEquals(Set.of(), registry.dataTypesFor("nonexistent"));
    }

    @Test
    void labelAndDescriptionForAnUnknownCategoryAreEmpty() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        Assertions.assertEquals("", registry.label("nonexistent"));
        Assertions.assertEquals("", registry.description("nonexistent"));
    }
}
