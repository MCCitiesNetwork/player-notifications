package io.github.md5sha256.playernotifications.paper.inbox;

import io.github.md5sha256.playernotifications.api.NotificationDataTypeRegistry;
import io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoryDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Filter resolution, against a hand-built {@link NotificationCategories} and
 * {@link NotificationDataTypeRegistry} — no mocking framework and no live server, which is the whole
 * reason {@link InboxFilters} holds no Bukkit type.
 */
class InboxFiltersTest {

    /** "mail" claims {@code mail} and {@code parcel}; {@code broadcast} is left uncategorized. */
    private static NotificationCategories mailCategories() {
        return new NotificationCategories(
                new NotificationCategoriesConfig("Other", Map.of(
                        "mail", new NotificationCategoryDefinition("Mail", "desc",
                                List.of("mail", "parcel")))),
                new DefaultNotificationCategoryRegistry(),
                Logger.getLogger("test"));
    }

    private static NotificationDataTypeRegistry registry(String... dataTypes) {
        NotificationDataTypeRegistry registry = new NotificationDataTypeRegistry();
        for (String dataType : dataTypes) {
            registry.registerPayloadMapping(dataType, String.class);
        }
        return registry;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void aCategoryResolvesToEveryDataTypeItClaims() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "parcel", "broadcast"));

        Assertions.assertEquals(Set.of("mail", "parcel"), filters.resolve("mail"));
    }

    @Test
    void uncategorizedResolvesToTheComplementOfEveryClaimedType() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "parcel", "broadcast"));

        Assertions.assertEquals(Set.of("broadcast"),
                filters.resolve(NotificationCategories.UNCATEGORIZED));
    }

    @Test
    void anUnknownCategoryKeyResolvesToAnEmptySetRatherThanNull() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "broadcast"));

        Set<String> resolved = filters.resolve("no-such-category");

        Assertions.assertNotNull(resolved, "an unknown key must match nothing, not everything");
        Assertions.assertEquals(Set.of(), resolved);
    }

    @Test
    void aNullCategoryKeyResolvesToNullMeaningUnfiltered() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail"));

        Assertions.assertNull(filters.resolve(null));
    }

    @Test
    void aDataTypeRegisteredAfterConstructionIsVisibleOnTheNextResolve() {
        NotificationDataTypeRegistry registry = registry("mail");
        InboxFilters filters = new InboxFilters(mailCategories(), registry);
        Assertions.assertEquals(Set.of("mail"), filters.resolve("mail"));

        registry.registerPayloadMapping("parcel", String.class);

        Assertions.assertEquals(Set.of("mail", "parcel"), filters.resolve("mail"));
    }

    @Test
    void categoryKeysAreSortedWithUncategorizedLast() {
        NotificationCategories categories = new NotificationCategories(
                new NotificationCategoriesConfig("Other", Map.of(
                        "mail", new NotificationCategoryDefinition("Mail", "", List.of("mail")),
                        "broadcast", new NotificationCategoryDefinition("Broadcasts", "", List.of("broadcast")))),
                new DefaultNotificationCategoryRegistry(),
                Logger.getLogger("test"));
        InboxFilters filters = new InboxFilters(categories, registry("mail", "broadcast"));

        Assertions.assertEquals(
                List.of("broadcast", "mail", NotificationCategories.UNCATEGORIZED),
                filters.categoryKeys());
    }

    @Test
    void labelOfNullIsAllNotifications() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail"));

        Assertions.assertEquals("All notifications", plain(filters.label(null)));
    }

    @Test
    void labelOfACategoryIsItsConfiguredLabel() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail"));

        Assertions.assertEquals("Mail", plain(filters.label("mail")));
    }

    @Test
    void reloadCategoriesSwapsTheCategoriesUsedByTheNextResolve() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "parcel"));
        Assertions.assertEquals(Set.of("mail", "parcel"), filters.resolve("mail"));

        filters.reloadCategories(new NotificationCategories(
                new NotificationCategoriesConfig("Other", Map.of(
                        "mail", new NotificationCategoryDefinition("Mail", "", List.of("mail")))),
                new DefaultNotificationCategoryRegistry(),
                Logger.getLogger("test")));

        Assertions.assertEquals(Set.of("mail"), filters.resolve("mail"));
    }

    @Test
    void aCategoryHasUnreadWhenAnyDataTypeItClaimsHasAnUnreadCount() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "parcel", "broadcast"));

        Assertions.assertTrue(filters.hasUnread("mail", Map.of("parcel", 2)));
    }

    @Test
    void aCategoryHasNoUnreadWhenOnlyOtherCategoriesDataTypesDo() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "parcel", "broadcast"));

        Assertions.assertFalse(filters.hasUnread("mail", Map.of("broadcast", 5)));
    }

    @Test
    void aNullCategoryKeyHasUnreadWhenAnyDataTypeAtAllDoes() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail"));

        Assertions.assertTrue(filters.hasUnread(null, Map.of("broadcast", 1)));
    }

    @Test
    void aNullCategoryKeyHasNoUnreadWhenNothingDoes() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail"));

        Assertions.assertFalse(filters.hasUnread(null, Map.of()));
    }

    @Test
    void anUnknownCategoryKeyHasNoUnreadEvenWhenOtherDataTypesDo() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "broadcast"));

        Assertions.assertFalse(filters.hasUnread("no-such-category", Map.of("mail", 3)));
    }

    @Test
    void aClaimedDataTypeWithAZeroCountIsNotUnread() {
        InboxFilters filters = new InboxFilters(mailCategories(), registry("mail", "parcel"));

        Assertions.assertFalse(filters.hasUnread("mail", Map.of("mail", 0)));
    }
}
