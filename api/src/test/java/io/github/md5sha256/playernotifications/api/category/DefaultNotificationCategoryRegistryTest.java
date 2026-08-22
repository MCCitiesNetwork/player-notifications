package io.github.md5sha256.playernotifications.api.category;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
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
    @Test
    void everyMutationNotifiesListeners() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        AtomicInteger fired = new AtomicInteger();
        registry.addChangeListener(fired::incrementAndGet);

        registry.registerCategory("realty.auction", "Realty auctions", "Bids and outcomes");
        registry.claimDataType("realty.auction", "realty.auction");
        registry.unclaimDataType("realty.auction", "realty.auction");

        Assertions.assertEquals(3, fired.get());
    }

    @Test
    void aListenerSeesTheMutationItWasNotifiedOf() {
        // The whole point of the callback: the host rebuilds its category snapshot from inside the
        // listener, so the claim must already be visible when the listener runs, not after it returns.
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        Set<String> observed = new HashSet<>();
        registry.addChangeListener(() -> observed.addAll(registry.dataTypesFor("realty.lease")));

        registry.claimDataType("realty.lease", "realty.lease");

        Assertions.assertEquals(Set.of("realty.lease"), observed);
    }

    @Test
    void aThrowingListenerNeitherLosesTheRegistrationNorStopsOtherListeners() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();
        AtomicInteger secondFired = new AtomicInteger();
        registry.addChangeListener(() -> {
            throw new IllegalStateException("listener is broken");
        });
        registry.addChangeListener(secondFired::incrementAndGet);

        Assertions.assertDoesNotThrow(() -> registry.registerCategory("realty.offer", "Offers", ""));
        Assertions.assertEquals(Set.of("realty.offer"), registry.categoryKeys());
        Assertions.assertEquals(1, secondFired.get());
    }

    @Test
    void aRegistryWithNoListenersStillMutates() {
        DefaultNotificationCategoryRegistry registry = new DefaultNotificationCategoryRegistry();

        Assertions.assertDoesNotThrow(() -> registry.claimDataType("realty.agent", "realty.agent"));
        Assertions.assertEquals(Set.of("realty.agent"), registry.dataTypesFor("realty.agent"));
    }
}
