package io.github.md5sha256.playernotifications.core.category;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

class NotificationCategoriesTest {

    private static NotificationCategories build(Map<String, NotificationCategoryDefinition> categories) {
        return build(categories, new DefaultNotificationCategoryRegistry());
    }

    private static NotificationCategories build(Map<String, NotificationCategoryDefinition> categories,
                                                 NotificationCategoryRegistry registry) {
        return new NotificationCategories(
                new NotificationCategoriesConfig("Other", categories), registry, Logger.getLogger("test"));
    }

    @Test
    void resolvesConfiguredType() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals(Set.of("economy"), categories.resolve("mail"));
    }

    @Test
    void unclaimedTypeFallsBackToUncategorized() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "Shop and payments", List.of("mail"))));

        Assertions.assertEquals(Set.of(NotificationCategories.UNCATEGORIZED), categories.resolve("nothing-registered"));
    }

    @Test
    void aDataTypeClaimedByTwoCategoriesResolvesToBoth() {
        Map<String, NotificationCategoryDefinition> ordered = new LinkedHashMap<>();
        ordered.put("economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")));
        ordered.put("moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("mail")));
        NotificationCategories categories = build(ordered);

        Assertions.assertEquals(Set.of("economy", "moderation"), categories.resolve("mail"));
    }

    @Test
    void aDataTypeClaimedByOneConfigAndOneCodeCategoryResolvesToBoth() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.claimDataType("essentials-mail-adapter", "mail");
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))), registry);

        Assertions.assertEquals(Set.of("economy", "essentials-mail-adapter"), categories.resolve("mail"));
    }

    @Test
    void categoryKeysIncludesUncategorizedAndCodeRegisteredCategories() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.registerCategory("essentials-mail-adapter", "Mail", "desc");
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))), registry);

        Assertions.assertEquals(Set.of("economy", "essentials-mail-adapter", "uncategorized"),
                categories.categoryKeys());
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
    void configWinsLabelOnKeyCollisionWithCode() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        registry.registerCategory("economy", "Code Economy Label", "code desc");
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Config Economy Label", "config desc", List.of())),
                registry);

        Assertions.assertEquals("Config Economy Label", categories.label("economy"));
        Assertions.assertEquals("config desc", categories.description("economy"));
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

    @Test
    void dataTypesForCategoryReturnsMembersAndUncategorizedReturnsTheComplement() {
        NotificationCategories categories = build(Map.of(
                "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail"))));
        Set<String> allKnownDataTypes = Set.of("mail", "warning");

        Assertions.assertEquals(Set.of("mail"), categories.dataTypesForCategory("economy", allKnownDataTypes));
        Assertions.assertEquals(Set.of("warning"),
                categories.dataTypesForCategory(NotificationCategories.UNCATEGORIZED, allKnownDataTypes));
    }
}
