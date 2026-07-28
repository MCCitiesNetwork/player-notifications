package io.github.md5sha256.playernotifications.core.category;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Map;

/**
 * Root of {@code categories.yml}: the label shown for data types no category claims, plus every
 * declared category keyed by its category key.
 */
@ConfigSerializable
public record NotificationCategoriesConfig(
        @Setting("uncategorized-label")
        @Required
        String uncategorizedLabel,

        @Setting
        @Required
        Map<String, NotificationCategoryDefinition> categories
) {
}
