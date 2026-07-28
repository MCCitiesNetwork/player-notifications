package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

class NotificationCategoriesTest {

    private static NotificationCategories build(Map<String, NotificationCategoryDefinition> categories) {
        return new NotificationCategories(
                new NotificationCategoriesConfig("Other", categories), Logger.getLogger("test"));
    }

    @Test
    void resolvesConfiguredType() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals("economy", categories.resolve("mail"));
    }

    @Test
    void unclaimedTypeFallsBackToUncategorized() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals(NotificationCategories.UNCATEGORIZED, categories.resolve("nothing-registered"));
    }

    @Test
    void duplicateClaimKeepsTheFirstCategoryInConfigOrder() {
        Map<String, NotificationCategoryDefinition> ordered = new LinkedHashMap<>();
        ordered.put("economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")));
        ordered.put("moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("mail")));
        NotificationCategories categories = build(ordered);

        Assertions.assertEquals("economy", categories.resolve("mail"));
    }

    @Test
    void categoryKeysIncludesUncategorized() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))));

        Assertions.assertEquals(Set.of("economy", "uncategorized"), categories.categoryKeys());
    }

    @Test
    void labelAndDescriptionLookup() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals("Economy", categories.label("economy"));
        Assertions.assertEquals("Shop and payments", categories.description("economy"));
        Assertions.assertEquals("Other", categories.label(NotificationCategories.UNCATEGORIZED));
    }

    @Test
    void unknownCategoryKeyFallsBackToTheKeyItself() {
        NotificationCategories categories = build(Map.of());

        Assertions.assertEquals("vanished", categories.label("vanished"));
    }

    @Test
    void typesWithNoPayloadMappingDetectsMissingRegistrations() {
        Map<String, NotificationCategoryDefinition> config = Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")),
                "moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("warning")));
        NotificationCategories categories = build(config);

        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        registry.registerPayloadMapping("mail", String.class);

        Assertions.assertEquals(Set.of("warning"), categories.typesWithNoPayloadMapping(registry));
    }
}
