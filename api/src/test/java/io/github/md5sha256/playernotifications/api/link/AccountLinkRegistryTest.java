package io.github.md5sha256.playernotifications.api.link;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

class AccountLinkRegistryTest {

    /** A provider that reports which of its actions was invoked, so delegation can be asserted. */
    private static final class RecordingProvider implements AccountLinkProvider {

        private final String key;

        private RecordingProvider(@NotNull String key) {
            this.key = key;
        }

        @Override
        public @NotNull String providerKey() {
            return this.key;
        }

        @Override
        public @NotNull Component begin(@NotNull UUID playerUuid) {
            return Component.text("begin");
        }

        @Override
        public @NotNull Component status(@NotNull UUID playerUuid) {
            return Component.text("status");
        }

        @Override
        public @NotNull Component unlink(@NotNull UUID playerUuid) {
            return Component.text("unlink");
        }
    }

    private static @NotNull String plain(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("a registered provider is found under its own key")
    void registersAndResolves() {
        AccountLinkRegistry registry = new AccountLinkRegistry();
        AccountLinkProvider discord = new RecordingProvider("discord");
        registry.registerProvider(discord);

        Assertions.assertSame(discord, registry.getProvider("discord").orElseThrow());
        Assertions.assertEquals(Set.of("discord"), registry.registeredProviders());
    }

    @Test
    @DisplayName("lookup is case-insensitive")
    void lookupIgnoresCase() {
        // A player typing "/notifications link Discord" must not be told the provider is unknown.
        AccountLinkRegistry registry = new AccountLinkRegistry();
        registry.registerProvider(new RecordingProvider("discord"));

        Assertions.assertTrue(registry.getProvider("Discord").isPresent());
        Assertions.assertTrue(registry.getProvider("DISCORD").isPresent());
    }

    @Test
    @DisplayName("a mixed-case provider key is normalised on registration")
    void keysAreNormalised() {
        AccountLinkRegistry registry = new AccountLinkRegistry();
        registry.registerProvider(new RecordingProvider("DiScOrD"));

        Assertions.assertEquals(Set.of("discord"), registry.registeredProviders());
        Assertions.assertTrue(registry.getProvider("discord").isPresent());
    }

    @Test
    @DisplayName("an unregistered provider resolves to empty")
    void unregisterRemoves() {
        AccountLinkRegistry registry = new AccountLinkRegistry();
        registry.registerProvider(new RecordingProvider("discord"));
        registry.unregisterProvider("discord");

        Assertions.assertTrue(registry.getProvider("discord").isEmpty());
        Assertions.assertEquals(Set.of(), registry.registeredProviders());
    }

    @Test
    @DisplayName("unregistering is case-insensitive too")
    void unregisterIgnoresCase() {
        // The module registers with its own constant and could unregister with a differently-cased one.
        AccountLinkRegistry registry = new AccountLinkRegistry();
        registry.registerProvider(new RecordingProvider("discord"));
        registry.unregisterProvider("DISCORD");

        Assertions.assertTrue(registry.getProvider("discord").isEmpty());
    }

    @Test
    @DisplayName("unregistering an absent key is a no-op")
    void unregisterAbsentIsHarmless() {
        AccountLinkRegistry registry = new AccountLinkRegistry();
        Assertions.assertDoesNotThrow(() -> registry.unregisterProvider("telegram"));
    }

    @Test
    @DisplayName("re-registering the same key replaces the previous provider")
    void reregistrationReplaces() {
        AccountLinkRegistry registry = new AccountLinkRegistry();
        registry.registerProvider(new RecordingProvider("discord"));
        AccountLinkProvider replacement = new RecordingProvider("discord");
        registry.registerProvider(replacement);

        Assertions.assertSame(replacement, registry.getProvider("discord").orElseThrow());
        Assertions.assertEquals(1, registry.registeredProviders().size());
    }

    @Test
    @DisplayName("registeredProviders is a snapshot, not a live view")
    void registeredProvidersIsASnapshot() {
        AccountLinkRegistry registry = new AccountLinkRegistry();
        registry.registerProvider(new RecordingProvider("discord"));
        Set<String> snapshot = registry.registeredProviders();
        registry.unregisterProvider("discord");

        Assertions.assertEquals(Set.of("discord"), snapshot);
    }

    @Test
    @DisplayName("displayName defaults to a title-cased provider key")
    void displayNameDefaultsToTitleCasedKey() {
        Assertions.assertEquals("Discord", plain(new RecordingProvider("discord").displayName()));
        Assertions.assertEquals("Essentials Mail",
                plain(new RecordingProvider("essentials-mail").displayName()));
        Assertions.assertEquals("Some Thing", plain(new RecordingProvider("some_thing").displayName()));
    }
}
