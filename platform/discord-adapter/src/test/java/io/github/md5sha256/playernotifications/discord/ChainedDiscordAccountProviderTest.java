package io.github.md5sha256.playernotifications.discord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

class ChainedDiscordAccountProviderTest {

    private static final Logger LOGGER = Logger.getLogger(ChainedDiscordAccountProviderTest.class.getName());
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** A provider whose behaviour is fixed at construction, recording whether it was queried. */
    private static final class FakeProvider implements DiscordAccountProvider {

        private final String key;
        private final Optional<Long> result;
        private final boolean available;
        private final RuntimeException failure;
        private boolean queried;

        private FakeProvider(String key, Optional<Long> result, boolean available, RuntimeException failure) {
            this.key = key;
            this.result = result;
            this.available = available;
            this.failure = failure;
        }

        static FakeProvider linking(String key, long id) {
            return new FakeProvider(key, Optional.of(id), true, null);
        }

        static FakeProvider unlinked(String key) {
            return new FakeProvider(key, Optional.empty(), true, null);
        }

        static FakeProvider unavailable(String key) {
            return new FakeProvider(key, Optional.of(1L), false, null);
        }

        static FakeProvider throwing(String key) {
            return new FakeProvider(key, Optional.empty(), true, new IllegalStateException("boom"));
        }

        @Override
        public String providerKey() {
            return this.key;
        }

        @Override
        public boolean isAvailable() {
            return this.available;
        }

        @Override
        public Optional<Long> discordIdFor(UUID playerUuid) {
            this.queried = true;
            if (this.failure != null) {
                throw this.failure;
            }
            return this.result;
        }
    }

    private static DiscordAccountProviderRegistry registryOf(DiscordAccountProvider... providers) {
        DiscordAccountProviderRegistry registry = new DiscordAccountProviderRegistry();
        for (DiscordAccountProvider provider : providers) {
            registry.register(provider);
        }
        return registry;
    }

    @Test
    void theFirstProviderWithALinkWins() {
        FakeProvider first = FakeProvider.linking("first", 111L);
        FakeProvider second = FakeProvider.linking("second", 222L);

        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("first", "second"), registryOf(first, second), LOGGER);

        Assertions.assertEquals(Optional.of(111L), chain.discordIdFor(PLAYER));
        Assertions.assertFalse(second.queried, "the chain must stop at the first link");
    }

    @Test
    void anEmptyResultFallsThroughToTheNextProvider() {
        FakeProvider first = FakeProvider.unlinked("first");
        FakeProvider second = FakeProvider.linking("second", 222L);

        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("first", "second"), registryOf(first, second), LOGGER);

        Assertions.assertEquals(Optional.of(222L), chain.discordIdFor(PLAYER));
        Assertions.assertTrue(first.queried);
    }

    @Test
    void anUnavailableProviderIsSkippedWithoutBeingQueried() {
        FakeProvider first = FakeProvider.unavailable("first");
        FakeProvider second = FakeProvider.linking("second", 222L);

        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("first", "second"), registryOf(first, second), LOGGER);

        Assertions.assertEquals(Optional.of(222L), chain.discordIdFor(PLAYER));
        Assertions.assertFalse(first.queried, "an unavailable provider must not be queried");
    }

    @Test
    void aThrowingProviderIsSkippedRatherThanAbortingTheChain() {
        FakeProvider first = FakeProvider.throwing("first");
        FakeProvider second = FakeProvider.linking("second", 222L);

        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("first", "second"), registryOf(first, second), LOGGER);

        Assertions.assertEquals(Optional.of(222L), chain.discordIdFor(PLAYER));
    }

    @Test
    void anUnknownKeyIsSkippedAndTheRestOfTheChainStillResolves() {
        FakeProvider known = FakeProvider.linking("known", 333L);

        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("nonexistent", "known"), registryOf(known), LOGGER);

        Assertions.assertEquals(Optional.of(333L), chain.discordIdFor(PLAYER));
    }

    @Test
    void anEmptyChainResolvesToEmpty() {
        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of(), registryOf(), LOGGER);

        Assertions.assertEquals(Optional.empty(), chain.discordIdFor(PLAYER));
    }

    @Test
    void everyProviderUnlinkedResolvesToEmpty() {
        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("a", "b"),
                registryOf(FakeProvider.unlinked("a"), FakeProvider.unlinked("b")),
                LOGGER);

        Assertions.assertEquals(Optional.empty(), chain.discordIdFor(PLAYER));
    }

    @Test
    void theChainIdentifiesItselfAsChain() {
        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of(), registryOf(), LOGGER);

        Assertions.assertEquals("chain", chain.providerKey());
    }

    /** Captures records published to a throwaway logger, so log-only behaviour can be asserted. */
    private static List<LogRecord> captureInto(Logger logger) {
        List<LogRecord> records = new ArrayList<>();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return records;
    }

    private static Logger throwawayLogger() {
        return Logger.getLogger("chain-test-" + UUID.randomUUID());
    }

    @Test
    void reportAvailabilityWarnsWhenNoProviderIsAvailable() {
        Logger logger = throwawayLogger();
        List<LogRecord> records = captureInto(logger);

        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("discordsrv"), registryOf(FakeProvider.unavailable("discordsrv")), logger);
        chain.reportAvailability();

        // Otherwise "every DM is UNSUPPORTED" produces no log at all until a notification is dropped.
        Assertions.assertTrue(records.stream().anyMatch(record -> record.getLevel() == Level.WARNING
                        && record.getMessage().contains(DiscordMedia.DM)),
                "expected a warning naming the discord-dm medium, got: " + records);
    }

    @Test
    void reportAvailabilityDoesNotWarnWhenOneIsAvailable() {
        Logger logger = throwawayLogger();
        List<LogRecord> records = captureInto(logger);

        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("a", "b"),
                registryOf(FakeProvider.unavailable("a"), FakeProvider.unlinked("b")),
                logger);
        chain.reportAvailability();

        Assertions.assertTrue(records.stream().noneMatch(record -> record.getLevel() == Level.WARNING),
                "expected no warning, got: " + records);
    }

    @Test
    void reportAvailabilityWarnsWhenTheChainIsEmpty() {
        Logger logger = throwawayLogger();
        List<LogRecord> records = captureInto(logger);

        // An operator who lists only unknown keys ends up here, and it is just as undeliverable.
        ChainedDiscordAccountProvider.of(List.of(), registryOf(), logger).reportAvailability();

        Assertions.assertTrue(records.stream().anyMatch(record -> record.getLevel() == Level.WARNING),
                "expected a warning for an empty chain, got: " + records);
    }

    @Test
    void reportAvailabilityDoesNotQueryAnyProvider() {
        FakeProvider provider = FakeProvider.linking("a", 1L);

        ChainedDiscordAccountProvider.of(List.of("a"), registryOf(provider), throwawayLogger())
                .reportAvailability();

        Assertions.assertFalse(provider.queried, "reporting must not perform a lookup");
    }

    @Test
    void delegateKeysReportsTheResolvedChainInOrder() {
        ChainedDiscordAccountProvider chain = ChainedDiscordAccountProvider.of(
                List.of("embedded", "nonexistent", "discordsrv"),
                registryOf(FakeProvider.unlinked("embedded"), FakeProvider.unlinked("discordsrv")),
                throwawayLogger());

        // The unknown key is dropped, so this reports what will actually be consulted.
        Assertions.assertEquals(List.of("embedded", "discordsrv"), chain.delegateKeys());
    }

    @Test
    void theRegistryLooksProvidersUpByTheirOwnKey() {
        FakeProvider provider = FakeProvider.linking("discordsrv", 1L);
        DiscordAccountProviderRegistry registry = registryOf(provider);

        Assertions.assertEquals(Optional.of(provider), registry.get("discordsrv"));
        Assertions.assertEquals(Optional.empty(), registry.get("other"));
    }
}
