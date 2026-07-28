package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.api.render.sink.NullSink;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoryDefinition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

class PreferenceDialogsTest {

    private static NotificationSink stubSink(String key) {
        return new NotificationSink() {
            @Override
            public String mediumKey() {
                return key;
            }

            @Override
            public DeliveryResult deliver(RenderableNotification notification, UUID target) {
                return DeliveryResult.DELIVERED;
            }
        };
    }

    @Test
    void selectableMediaExcludesNullSinkAndSortsAlphabetically() {
        NotificationSinkRegistry registry = new NotificationSinkRegistry();
        registry.registerSink(stubSink("discord"));
        registry.registerSink(stubSink("chat"));
        registry.registerSink(new NullSink());

        List<String> selectable = PreferenceDialogs.selectableMedia(registry);

        Assertions.assertEquals(List.of("chat", "discord"), selectable);
    }

    @Test
    void sortedCategoryKeysIncludesUncategorizedAndSorts() {
        NotificationCategories categories = new NotificationCategories(
                new NotificationCategoriesConfig("Other", Map.of(
                        "moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("warning")),
                        "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")))),
                Logger.getLogger("test"));

        List<String> sorted = PreferenceDialogs.sortedCategoryKeys(categories);

        Assertions.assertEquals(List.of("economy", "moderation", "uncategorized"), sorted);
    }

    @Test
    void inputKeyIsPositionalAndPrefixed() {
        Assertions.assertEquals("medium_0", PreferenceDialogs.inputKey("medium", 0));
        Assertions.assertEquals("category_3", PreferenceDialogs.inputKey("category", 3));
    }
}
