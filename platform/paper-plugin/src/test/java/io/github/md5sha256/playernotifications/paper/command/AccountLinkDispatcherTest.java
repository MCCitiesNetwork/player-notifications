package io.github.md5sha256.playernotifications.paper.command;

import io.github.md5sha256.playernotifications.api.link.AccountLinkProvider;
import io.github.md5sha256.playernotifications.api.link.AccountLinkRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

class AccountLinkDispatcherTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final Logger LOGGER = Logger.getLogger(AccountLinkDispatcherTest.class.getName());

    /** Records which action was asked for, so routing can be asserted without a real provider. */
    private static final class RecordingProvider implements AccountLinkProvider {

        private final String key;
        private final List<String> calls = new ArrayList<>();
        private UUID lastPlayer;

        private RecordingProvider(@NotNull String key) {
            this.key = key;
        }

        @Override
        public @NotNull String providerKey() {
            return this.key;
        }

        @Override
        public @NotNull Component begin(@NotNull UUID playerUuid) {
            this.calls.add("begin");
            this.lastPlayer = playerUuid;
            return Component.text("begin reply");
        }

        @Override
        public @NotNull Component status(@NotNull UUID playerUuid) {
            this.calls.add("status");
            this.lastPlayer = playerUuid;
            return Component.text("status reply");
        }

        @Override
        public @NotNull Component unlink(@NotNull UUID playerUuid) {
            this.calls.add("unlink");
            this.lastPlayer = playerUuid;
            return Component.text("unlink reply");
        }
    }

    /** A provider whose every action blows up, standing in for a buggy third-party module. */
    private static final class ThrowingProvider implements AccountLinkProvider {

        @Override
        public @NotNull String providerKey() {
            return "broken";
        }

        @Override
        public @NotNull Component begin(@NotNull UUID playerUuid) {
            throw new IllegalStateException("boom");
        }

        @Override
        public @NotNull Component status(@NotNull UUID playerUuid) {
            throw new IllegalStateException("boom");
        }

        @Override
        public @NotNull Component unlink(@NotNull UUID playerUuid) {
            throw new IllegalStateException("boom");
        }
    }

    private static @NotNull String plain(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static @NotNull AccountLinkDispatcher dispatcherWith(@NotNull AccountLinkProvider... providers) {
        AccountLinkRegistry registry = new AccountLinkRegistry();
        for (AccountLinkProvider provider : providers) {
            registry.registerProvider(provider);
        }
        return new AccountLinkDispatcher(registry, LOGGER);
    }

    @Test
    @DisplayName("each action routes to the matching provider method and returns its reply")
    void routesEachAction() {
        RecordingProvider discord = new RecordingProvider("discord");
        AccountLinkDispatcher dispatcher = dispatcherWith(discord);

        Assertions.assertEquals("begin reply",
                plain(dispatcher.dispatch("discord", PLAYER, AccountLinkDispatcher.Action.BEGIN)));
        Assertions.assertEquals("status reply",
                plain(dispatcher.dispatch("discord", PLAYER, AccountLinkDispatcher.Action.STATUS)));
        Assertions.assertEquals("unlink reply",
                plain(dispatcher.dispatch("discord", PLAYER, AccountLinkDispatcher.Action.UNLINK)));

        Assertions.assertEquals(List.of("begin", "status", "unlink"), discord.calls);
        Assertions.assertEquals(PLAYER, discord.lastPlayer);
    }

    @Test
    @DisplayName("a provider key typed in the wrong case still resolves")
    void dispatchIgnoresCase() {
        RecordingProvider discord = new RecordingProvider("discord");
        AccountLinkDispatcher dispatcher = dispatcherWith(discord);

        dispatcher.dispatch("DiScOrD", PLAYER, AccountLinkDispatcher.Action.BEGIN);

        Assertions.assertEquals(List.of("begin"), discord.calls);
    }

    @Test
    @DisplayName("an unknown provider yields a message, not an exception")
    void unknownProviderIsExplained() {
        AccountLinkDispatcher dispatcher = dispatcherWith(new RecordingProvider("discord"));

        String reply = plain(dispatcher.dispatch("telegram", PLAYER, AccountLinkDispatcher.Action.BEGIN));

        Assertions.assertTrue(reply.contains("not available"), reply);
        Assertions.assertTrue(reply.contains("Telegram"), reply);
    }

    @Test
    @DisplayName("a provider that throws is contained behind a generic reply")
    void throwingProviderIsContained() {
        // A module's bug must not surface as a red Brigadier stack trace in the player's chat.
        AccountLinkDispatcher dispatcher = dispatcherWith(new ThrowingProvider());

        String reply = plain(dispatcher.dispatch("broken", PLAYER, AccountLinkDispatcher.Action.BEGIN));

        Assertions.assertFalse(reply.contains("boom"), reply);
        Assertions.assertTrue(reply.contains("went wrong"), reply);
    }

    @Test
    @DisplayName("listProviders names each registered provider and how to use it")
    void listsRegisteredProviders() {
        AccountLinkDispatcher dispatcher = dispatcherWith(new RecordingProvider("discord"));

        String listing = plain(dispatcher.listProviders());

        Assertions.assertTrue(listing.contains("Discord"), listing);
        Assertions.assertTrue(listing.contains("/notifications link discord"), listing);
    }

    @Test
    @DisplayName("listProviders with nothing registered says so instead of listing nothing")
    void listsNothingWhenEmpty() {
        AccountLinkDispatcher dispatcher = dispatcherWith();

        String listing = plain(dispatcher.listProviders());

        Assertions.assertTrue(listing.contains("no account linking"), listing.toLowerCase());
    }

    @Test
    @DisplayName("suggestions mirror the registered provider keys")
    void suggestsRegisteredKeys() {
        AccountLinkDispatcher dispatcher =
                dispatcherWith(new RecordingProvider("discord"), new RecordingProvider("telegram"));

        Assertions.assertEquals(Set.of("discord", "telegram"), Set.copyOf(dispatcher.suggestions()));
    }

    @Test
    @DisplayName("suggestions follow later registrations, since the command tree is built once")
    void suggestionsAreLive() {
        // The Brigadier node is built when Paper fires the COMMANDS event; a module registering after
        // that must still be reachable and suggestible.
        AccountLinkRegistry registry = new AccountLinkRegistry();
        AccountLinkDispatcher dispatcher = new AccountLinkDispatcher(registry, LOGGER);
        Assertions.assertTrue(dispatcher.suggestions().isEmpty());

        registry.registerProvider(new RecordingProvider("discord"));

        Assertions.assertEquals(Set.of("discord"), Set.copyOf(dispatcher.suggestions()));
    }
}
