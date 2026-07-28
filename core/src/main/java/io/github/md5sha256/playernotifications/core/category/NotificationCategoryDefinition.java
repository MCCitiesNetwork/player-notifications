package io.github.md5sha256.playernotifications.core.category;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.List;

/**
 * One category declared in {@code categories.yml}: a player-facing label and description, and the
 * registry data types it groups together.
 */
@ConfigSerializable
public record NotificationCategoryDefinition(
        @Setting @Required String label,
        @Setting @Required String description,
        @Setting @Required List<String> types
) {
}
