package io.github.md5sha256.playernotifications.paper.preferences;

import io.github.md5sha256.playernotifications.api.render.NotificationPreferences;
import io.github.md5sha256.playernotifications.api.NotificationSinkRegistry;
import io.github.md5sha256.playernotifications.api.category.DefaultNotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.category.NotificationCategoryRegistry;
import io.github.md5sha256.playernotifications.api.render.DeliveryResult;
import io.github.md5sha256.playernotifications.api.render.NotificationSink;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import io.github.md5sha256.playernotifications.core.category.NotificationCategories;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoriesConfig;
import io.github.md5sha256.playernotifications.core.category.NotificationCategoryDefinition;
import io.github.md5sha256.playernotifications.paper.preferences.session.PreferenceEditSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void selectableMediaExcludesTheSilencedMediumAndSortsAlphabetically() {
        NotificationSinkRegistry registry = new NotificationSinkRegistry();
        registry.registerSink(stubSink("discord"));
        registry.registerSink(stubSink("chat"));
        // Nothing registers the silenced medium today, but if anything ever does it must not become a
        // checkbox: a silence is expressed by checking nothing.
        registry.registerSink(stubSink(NotificationPreferences.SILENCED_MEDIUM));

        List<String> selectable = PreferenceDialogs.selectableMedia(registry);

        Assertions.assertEquals(List.of("chat", "discord"), selectable);
    }

    @Test
    void sortedCategoryKeysIncludesUncategorizedAndSorts() {
        NotificationCategoryRegistry registry = new NotificationCategoryRegistry() {
            @Override
            public void registerCategory(String categoryKey, String label, String description) {}

            @Override
            public void claimDataType(String categoryKey, String dataType) {}

            @Override
            public void unclaimDataType(String categoryKey, String dataType) {}

            @Override
            public Set<String> categoryKeys() {
                return Set.of();
            }

            @Override
            public Set<String> dataTypesFor(String categoryKey) {
                return Set.of();
            }

            @Override
            public String label(String categoryKey) {
                return "";
            }

            @Override
            public String description(String categoryKey) {
                return "";
            }
        };

        NotificationCategories categories = new NotificationCategories(
                new NotificationCategoriesConfig("Other", Map.of(
                        "moderation", new NotificationCategoryDefinition("Moderation", "desc", List.of("warning")),
                        "economy", new NotificationCategoryDefinition("Economy", "desc", List.of("mail")))),
                registry,
                Logger.getLogger("test"));

        List<String> sorted = PreferenceDialogs.sortedCategoryKeys(categories);

        Assertions.assertEquals(List.of("economy", "moderation", "uncategorized"), sorted);
    }

    private static PreferenceEditSession session() {
        return new PreferenceEditSession(UUID.randomUUID(), Map.of("mail", Set.of("chat")), Instant.now());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void stagedSummaryIsAbsentWhileNothingIsStaged() {
        Assertions.assertTrue(PreferenceDialogs.stagedSummary(session()).isEmpty());
    }

    @Test
    void stagedSummaryNamesHowManyChangesAreWaitingToBeApplied() {
        PreferenceEditSession session = session();
        session.toggleDataTypeMedium("mail", "chat", false, Instant.now());

        Component summary = PreferenceDialogs.stagedSummary(session).orElseThrow();

        Assertions.assertEquals("You have 1 unsaved change. Press Apply to save it.", plain(summary));
    }

    @Test
    void stagedSummaryPluralisesForSeveralChanges() {
        PreferenceEditSession session = session();
        Instant now = Instant.now();
        session.toggleDataTypeMedium("mail", "chat", false, now);
        session.toggleDataTypeMedium("test", "chat", true, now);

        Component summary = PreferenceDialogs.stagedSummary(session).orElseThrow();

        Assertions.assertEquals("You have 2 unsaved changes. Press Apply to save them.", plain(summary));
    }

    @Test
    void applyLabelIsBareWhenNothingIsStagedYet() {
        Assertions.assertEquals("Apply", plain(PreferenceDialogs.applyLabel(session())));
    }

    @Test
    void applyLabelCountsWhatIsStaged() {
        PreferenceEditSession session = session();
        Instant now = Instant.now();
        session.toggleDataTypeMedium("mail", "chat", false, now);
        session.toggleDataTypeMedium("test", "chat", true, now);

        Assertions.assertEquals("Apply (2 changed)", plain(PreferenceDialogs.applyLabel(session)));
    }

    /** Categories with one "Mail" category claiming {@code essentials-mail}; everything else falls to "Other". */
    private static NotificationCategories mailCategories() {
        return new NotificationCategories(
                new NotificationCategoriesConfig("Other", Map.of(
                        "mail", new NotificationCategoryDefinition("Mail", "desc", List.of("essentials-mail")))),
                new DefaultNotificationCategoryRegistry(),
                Logger.getLogger("test"));
    }

    @Test
    void dataTypeLabelTitleCasesTheRawKey() {
        Component label = PreferenceDialogs.dataTypeLabel(mailCategories(), "essentials-mail");

        Assertions.assertEquals("Mail: Essentials Mail", plain(label));
    }

    @Test
    void dataTypeLabelTitleCasesASingleWordKey() {
        Component label = PreferenceDialogs.dataTypeLabel(mailCategories(), "test");

        Assertions.assertEquals("Other: Test", plain(label));
    }

    @Test
    void inputKeyIsPositionalAndPrefixed() {
        Assertions.assertEquals("medium_0", PreferenceDialogs.inputKey("medium", 0));
        Assertions.assertEquals("category_3", PreferenceDialogs.inputKey("category", 3));
    }
}
